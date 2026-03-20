package com.miseservice.cameramjpeg.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors

// ─────────────────────────────────────────────────────────────────────────────
// Data types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A recycled NV21 buffer slot managed by [FrameBufferPool].
 *
 * Allocated once per stream resolution; circulated through the pipeline
 * to avoid per-frame heap allocation at full sensor resolution.
 *
 * After the consumer (encoder) finishes, it calls [recycle] to return
 * the slot to the pool.
 */
class NV21Frame(val width: Int, val height: Int) {
    val data: ByteArray = ByteArray(width * height * 3 / 2)
    /** Returns this slot to its owning pool. Set by [FrameBufferPool]. */
    var recycle: () -> Unit = {}
}

// ─────────────────────────────────────────────────────────────────────────────
// Buffer pool
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fixed-size pool of [NV21Frame] slots.
 *
 * Three slots cover the pipeline stages:
 *   • slot 0 — being filled by the ImageReader callback
 *   • slot 1 — waiting in the encode queue
 *   • slot 2 — being JPEG-encoded / returned
 *
 * [acquire] is non-blocking: returns null when all slots are in use so
 * the capture callback can drop the incoming frame without stalling.
 */
class FrameBufferPool(width: Int, height: Int, capacity: Int = 3) {

    private val pool = ArrayBlockingQueue<NV21Frame>(capacity)

    init {
        repeat(capacity) {
            val frame = NV21Frame(width, height)
            frame.recycle = { pool.offer(frame) }
            pool.offer(frame)
        }
    }

    /** Returns a free slot or null if all slots are currently in use. */
    fun acquire(): NV21Frame? = pool.poll()
}

// ─────────────────────────────────────────────────────────────────────────────
// CameraStreamController
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CameraStreamController
 *
 * Controls the camera stream for MJPEG output at the sensor's maximum native
 * resolution. Designed for quality-first, LAN streaming.
 *
 * Pipeline
 * ────────
 *  ImageReader  ──►  YUV→NV21  ──►  FrameBufferPool
 *  (CameraBackground thread)              │
 *                                  EncodeQueue (capacity 1)
 *                                         │
 *                                  EncodeThread
 *                                  Rotate + JPEG compress
 *                                         │
 *                                  FrameStore.publish()
 *
 * Memory strategy
 * ───────────────
 * • [FrameBufferPool] — 3 pre-allocated NV21 slots, reused across frames.
 *   Zero heap allocation at capture time after initialisation.
 * • [jpegOutputStream] — single ByteArrayOutputStream, reset() before each
 *   frame to avoid internal reallocation.
 * • [encodeQueue] capacity = 1. When the encoder is busy the new frame is
 *   dropped (slot recycled) so frames never accumulate in memory.
 *
 * Rotation strategy
 * ─────────────────
 * Angle = (sensorOrientation ± deviceRotation) % 360, read from
 * [CameraCharacteristics.SENSOR_ORIENTATION] + current display rotation.
 * No hard-coded 180° assumption; works for all device/camera combos.
 *
 * @param context    Application context.
 * @param frameStore Destination for encoded JPEG frames.
 */
class CameraStreamController(
    private val context: Context,
    private val frameStore: FrameStore
) {

    // ── Companion ────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "CameraStreamCtrl"

        /** Visually near-lossless for LAN. */
        const val DEFAULT_JPEG_QUALITY = 92

        /**
         * ImageReader buffer count.
         * 3 = one being copied, one queued for encode, one spare.
         */
        const val IMAGE_READER_MAX_IMAGES = 3

        /** Encode queue depth: 1 = "encode latest, drop the rest". */
        const val ENCODE_QUEUE_CAPACITY = 1

        /** Initial capacity of the reusable JPEG stream (4 MB). */
        const val JPEG_STREAM_INITIAL_BYTES = 4 * 1024 * 1024
    }

    // ── Android services ─────────────────────────────────────────────────────

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager

    // ── Camera state ─────────────────────────────────────────────────────────

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    /** Dedicated single-thread executor for JPEG encoding. */
    private var encodeExecutor = Executors.newSingleThreadExecutor()
    private var encodeQueue    = ArrayBlockingQueue<NV21Frame>(ENCODE_QUEUE_CAPACITY)

    private var currentCameraId    = ""
    private var isUsingFrontCamera = true
    private var jpegQuality        = DEFAULT_JPEG_QUALITY

    // ── Buffer pool (allocated per resolution on start) ───────────────────────

    private var bufferPool: FrameBufferPool? = null

    /**
     * Reusable JPEG output stream. reset() before each encode call empties
     * the internal buffer without releasing its backing array.
     */
    private val jpegOutputStream = ByteArrayOutputStream(JPEG_STREAM_INITIAL_BYTES)

    // ── Camera device callbacks ───────────────────────────────────────────────

    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startCaptureSession(camera)
        }
        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera device error: $error")
            camera.close(); cameraDevice = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start streaming from the selected camera.
     * @param useFrontCamera true = front-facing camera.
     * @param jpegQuality    JPEG quality [0, 100].
     */
    fun start(useFrontCamera: Boolean, jpegQuality: Int = DEFAULT_JPEG_QUALITY) {
        isUsingFrontCamera = useFrontCamera
        this.jpegQuality   = jpegQuality
        startCamera()
    }

    /**
     * Switch between front and back cameras.
     * Resources are released before the new camera is opened.
     */
    fun switchCamera(useFront: Boolean) {
        isUsingFrontCamera = useFront
        stopInternal()
        startCamera()
    }

    /** Adjust JPEG quality on the fly — takes effect on the next frame. */
    fun updateQuality(newQuality: Int) { jpegQuality = newQuality }

    /** Stop the stream and release all resources. */
    fun stop() = stopInternal()

    // ─────────────────────────────────────────────────────────────────────────
    // Camera initialisation
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        startCameraThread()

        currentCameraId     = getCameraId(isUsingFrontCamera)
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        val streamMap       = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // Maximum native sensor resolution — quality first, no downscale.
        val size: Size = streamMap
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(1920, 1080)

        Log.i(TAG, "Stream: ${size.width}×${size.height}  " +
                   "cam=$currentCameraId  quality=$jpegQuality")

        // One pool per resolution — recreated on switchCamera().
        bufferPool = FrameBufferPool(size.width, size.height, capacity = 3)

        // Fresh executor + queue for this session.
        encodeExecutor = Executors.newSingleThreadExecutor()
        encodeQueue    = ArrayBlockingQueue(ENCODE_QUEUE_CAPACITY)
        startEncodeLoop(size, characteristics)

        imageReader = ImageReader.newInstance(
            size.width, size.height,
            ImageFormat.YUV_420_888,
            IMAGE_READER_MAX_IMAGES
        ).also { reader ->
            reader.setOnImageAvailableListener(
                { onImageAvailable(it) },
                cameraHandler
            )
        }

        cameraManager.openCamera(currentCameraId, cameraDeviceCallback, cameraHandler)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame acquisition  (CameraBackground thread)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by [ImageReader] on each new camera frame.
     *
     * Acquires the latest image (older buffered frames are discarded
     * automatically), copies YUV data into a pooled [NV21Frame], then
     * offers the slot to [encodeQueue]. If the queue is full (encoder busy)
     * the slot is recycled immediately — no memory accumulation.
     */
    private fun onImageAvailable(reader: ImageReader) {
        val image: Image = reader.acquireLatestImage() ?: return

        val frame = bufferPool?.acquire()
        if (frame == null) {
            // All slots busy — drop frame to prevent OOM.
            image.close()
            return
        }

        try {
            imageToNV21(image, frame.data, frame.width, frame.height)
        } catch (e: Exception) {
            Log.e(TAG, "YUV→NV21 error: ${e.message}", e)
            frame.recycle()
            image.close()
            return
        } finally {
            image.close()
        }

        // Non-blocking offer: drop if encoder is still busy.
        if (!encodeQueue.offer(frame)) {
            frame.recycle()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Encode loop  (dedicated single-thread executor)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Blocks on [encodeQueue], rotates the NV21 buffer, encodes to JPEG,
     * publishes to [frameStore], then recycles the slot.
     *
     * The rotated output buffer is pre-allocated once here, not per frame.
     */
    private fun startEncodeLoop(size: Size, characteristics: CameraCharacteristics) {
        encodeExecutor.submit {
            val angle = computeRotationAngle(characteristics)
            Log.i(TAG, "Encode loop — rotation=$angle°")

            // For 90°/270° width and height swap in the output.
            val (outW, outH) = when (angle) {
                90, 270 -> size.height to size.width
                else    -> size.width  to size.height
            }
            val rotated = ByteArray(outW * outH * 3 / 2)

            while (!Thread.currentThread().isInterrupted) {
                val frame = try {
                    encodeQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    rotateNv21(frame.data, rotated, frame.width, frame.height, angle)
                    val jpeg = encodeJpeg(rotated, outW, outH)
                    frameStore.publish(jpeg)
                } catch (e: Exception) {
                    Log.e(TAG, "Encode error: ${e.message}", e)
                } finally {
                    frame.recycle()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rotation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes the rotation to apply so output frames are upright
     * for the current device orientation.
     *
     *   Back camera  : (sensorOrientation - deviceRotation + 360) % 360
     *   Front camera : (sensorOrientation + deviceRotation + 270) % 360
     */
    private fun computeRotationAngle(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val device = try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            when (wm.defaultDisplay.rotation) {
                Surface.ROTATION_0   ->   0
                Surface.ROTATION_90  ->  90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else                 ->   0
            }
        } catch (_: Exception) { 0 }

        return if (isUsingFrontCamera) (sensor + device + 270) % 360
               else                    (sensor - device + 360) % 360
    }

    /**
     * Rotates an NV21 [src] buffer by [degrees] (0 / 90 / 180 / 270) into [dst].
     *
     * [dst] must be sized for the output:
     *  - 0° / 180°  → width × height unchanged
     *  - 90° / 270° → height × width (dimensions swapped)
     */
    private fun rotateNv21(
        src: ByteArray, dst: ByteArray,
        width: Int, height: Int, degrees: Int
    ) {
        when (degrees) {
            180  -> rotate180(src, dst, width, height)
            90   -> rotate90 (src, dst, width, height)
            270  -> rotate270(src, dst, width, height)
            else -> src.copyInto(dst)
        }
    }

    private fun rotate180(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        var out   = 0
        for (i in ySize - 1 downTo 0)
            dst[out++] = src[i]
        for (i in src.size - 2 downTo ySize step 2) {
            dst[out++] = src[i]
            dst[out++] = src[i + 1]
        }
    }

    private fun rotate90(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        var i = 0
        for (x in 0 until width)
            for (y in height - 1 downTo 0)
                dst[i++] = src[y * width + x]
        var j = ySize
        for (x in 0 until width step 2)
            for (y in height / 2 - 1 downTo 0) {
                val uv = ySize + y * width + x
                dst[j++] = src[uv]; dst[j++] = src[uv + 1]
            }
    }

    private fun rotate270(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        var i = 0
        for (x in width - 1 downTo 0)
            for (y in 0 until height)
                dst[i++] = src[y * width + x]
        var j = ySize
        for (x in width - 1 downTo 1 step 2)
            for (y in 0 until height / 2) {
                val uv = ySize + y * width + x - 1
                dst[j++] = src[uv]; dst[j++] = src[uv + 1]
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JPEG encoding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Encodes an NV21 buffer to JPEG.
     *
     * [jpegOutputStream] is reset (not re-created) before each call,
     * so its internal byte array is reused — no heap allocation per frame.
     */
    private fun encodeJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        jpegOutputStream.reset()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), jpegQuality, jpegOutputStream)
        return jpegOutputStream.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV → NV21 conversion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a [YUV_420_888][ImageFormat.YUV_420_888] [Image] to NV21 in-place.
     *
     * Uses bulk ByteBuffer.get() when plane layout allows it (stride == width)
     * to minimise per-pixel overhead.
     *
     * NV21 layout: Y plane (width×height) + interleaved VU pairs (width×height/2).
     */
    private fun imageToNV21(image: Image, nv21: ByteArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val ySize  = width * height

        // Y plane
        val yBuf = yPlane.buffer
        if (yPlane.rowStride == width) {
            yBuf.get(nv21, 0, ySize)
        } else {
            var offset = 0
            repeat(height) { row ->
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(nv21, offset, width)
                offset += width
            }
        }

        // VU plane
        val vBuf          = vPlane.buffer
        val uBuf          = uPlane.buffer
        val uvRowStride   = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        var offset        = ySize

        if (uvPixelStride == 1) {
            // Fully planar — interleave V and U manually.
            repeat(height / 2) { row ->
                vBuf.position(row * uvRowStride)
                uBuf.position(row * uvRowStride)
                repeat(width / 2) { col ->
                    nv21[offset++] = vBuf.get(col)
                    nv21[offset++] = uBuf.get(col)
                }
            }
        } else {
            // Semi-planar (pixel stride ≥ 2) — bulk copy per row.
            repeat(height / 2) { row ->
                vBuf.position(row * uvRowStride)
                vBuf.get(nv21, offset, width - 1)
                offset += width
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture session
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun startCaptureSession(camera: CameraDevice) {
        val surface = imageReader?.surface ?: return
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val request = camera
                            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply {
                                addTarget(surface)
                                // Disable post-processing — reduce latency.
                                set(CaptureRequest.EDGE_MODE,
                                    CaptureRequest.EDGE_MODE_OFF)
                                set(CaptureRequest.NOISE_REDUCTION_MODE,
                                    CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                                // Continuous AF for sharp images at full resolution.
                                set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            .build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "setRepeatingRequest failed: ${e.message}", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            },
            cameraHandler
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource management
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopInternal() {
        encodeExecutor.shutdownNow()
        encodeQueue.clear()
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        imageReader?.close();    imageReader     = null
        bufferPool = null
        jpegOutputStream.reset()
        stopCameraThread()
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread(
            "CameraBackground",
            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).also { it.start(); cameraHandler = Handler(it.looper) }
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (_: InterruptedException) {}
        cameraThread = null; cameraHandler = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun getCameraId(useFront: Boolean): String {
        val facing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
                     else          CameraCharacteristics.LENS_FACING_BACK
        return cameraManager.cameraIdList.first { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    /**
     * Returns a JSON list of all YUV_420_888 resolutions available on the
     * current camera, sorted largest first.
     */
    fun buildCameraOutputMapJson(): String {
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        val streamMap       = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val formats = streamMap
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
            ?.map { JSONObject().apply { put("width", it.width); put("height", it.height) } }
            ?: emptyList()
        return JSONObject().apply {
            put("ok", true)
            put("formats", JSONArray(formats))
        }.toString()
    }
}
