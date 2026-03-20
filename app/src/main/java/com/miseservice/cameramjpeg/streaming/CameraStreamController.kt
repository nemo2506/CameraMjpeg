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
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraStreamController
 *
 * Controls the camera stream for MJPEG output. Handles camera initialisation,
 * frame acquisition, YUV→NV21 conversion, 180° rotation, and JPEG encoding.
 *
 * Memory strategy (OOM fix):
 * - [nv21Buffer] and [rotatedBuffer] are allocated once per stream resolution
 *   and reused across frames.
 * - [jpegOutputStream] is pre-allocated with a generous initial capacity and
 *   reset() between frames — avoids the double-copy that ByteArrayOutputStream
 *   + toByteArray() causes when the stream needs to grow.
 * - [frameInProgress] is an atomic guard: if the background thread is still
 *   encoding the previous frame, the new image is dropped immediately.
 *   This prevents frame accumulation when encoding is slower than capture.
 * - Resolution is capped at [MAX_STREAM_WIDTH] × [MAX_STREAM_HEIGHT] so that
 *   per-frame heap demand stays predictable.
 * - ImageReader maxImages is set to 2 — the minimum to avoid stalling the
 *   camera pipeline without allowing more than one extra frame to queue up.
 *
 * @param context   Application context (used to obtain the CameraManager service).
 * @param frameStore FrameStore instance to publish encoded JPEG frames.
 */
class CameraStreamController(
    context: Context,
    private val frameStore: FrameStore
) {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private companion object {
        const val TAG = "CameraStreamController"

        /** Cap the stream resolution to keep per-frame allocations predictable. */
        const val MAX_STREAM_WIDTH  = 1280
        const val MAX_STREAM_HEIGHT = 720

        /** Initial capacity for the reusable JPEG output stream (1 MB). */
        const val JPEG_INITIAL_CAPACITY = 1_000_000

        /** Number of ImageReader buffers — 2 prevents camera stalls without queuing frames. */
        const val IMAGE_READER_MAX_IMAGES = 2
    }

    // -------------------------------------------------------------------------
    // System services
    // -------------------------------------------------------------------------

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager

    // -------------------------------------------------------------------------
    // Camera state
    // -------------------------------------------------------------------------

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentCameraId = ""
    private var isUsingFrontCamera = true
    private var jpegQuality: Int = 80

    // -------------------------------------------------------------------------
    // Reusable frame buffers — allocated once per stream resolution (OOM fix)
    // -------------------------------------------------------------------------

    private var nv21Buffer: ByteArray? = null
    private var rotatedBuffer: ByteArray? = null

    /**
     * Reused across frames. reset() empties it without freeing the internal
     * byte array, so no re-allocation occurs between frames.
     */
    private val jpegOutputStream = ByteArrayOutputStream(JPEG_INITIAL_CAPACITY)

    /**
     * Atomic guard: true while a frame is being encoded.
     * When true, incoming frames are dropped to avoid memory build-up.
     */
    private val frameInProgress = AtomicBoolean(false)

    // -------------------------------------------------------------------------
    // Camera device callback
    // -------------------------------------------------------------------------

    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startCaptureSession(camera)
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera device error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start the camera stream.
     *
     * @param useFrontCamera true to use the front-facing camera.
     * @param jpegQuality    JPEG compression quality in [0, 100].
     */
    fun start(useFrontCamera: Boolean, jpegQuality: Int) {
        isUsingFrontCamera = useFrontCamera
        this.jpegQuality   = jpegQuality
        startCamera()
    }

    /**
     * Switch between front and back cameras without stopping the service.
     *
     * @param useFront true for the front-facing camera.
     */
    fun switchCamera(useFront: Boolean) {
        isUsingFrontCamera = useFront
        stopInternal()
        startCamera()
    }

    /**
     * Update JPEG compression quality on the fly (no restart needed).
     *
     * @param newQuality JPEG quality in [0, 100].
     */
    fun updateQuality(newQuality: Int) {
        jpegQuality = newQuality
    }

    /** Stop the camera stream and release all resources. */
    fun stop() = stopInternal()

    // -------------------------------------------------------------------------
    // Camera initialisation
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        startBackgroundThread()

        currentCameraId = getCameraId(isUsingFrontCamera)
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        val streamConfigs   = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // Cap resolution to MAX_STREAM_WIDTH × MAX_STREAM_HEIGHT.
        // Using the maximum native resolution causes ~5 MB/frame allocations → OOM.
        val size: Size = streamConfigs
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width <= MAX_STREAM_WIDTH && it.height <= MAX_STREAM_HEIGHT }
            ?.maxByOrNull { it.width * it.height }
            ?: Size(MAX_STREAM_WIDTH, MAX_STREAM_HEIGHT)

        Log.d(TAG, "Stream resolution: ${size.width}×${size.height}")

        // Allocate NV21 buffers once for this resolution.
        val pixelCount = size.width * size.height
        nv21Buffer    = ByteArray(pixelCount + pixelCount / 2)
        rotatedBuffer = ByteArray(pixelCount + pixelCount / 2)

        // maxImages = 2: one being processed, one queued — no more accumulation.
        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, IMAGE_READER_MAX_IMAGES
        ).also { reader ->
            reader.setOnImageAvailableListener(
                { r -> onImageAvailable(r, size) },
                backgroundHandler
            )
        }

        cameraManager.openCamera(currentCameraId, cameraDeviceCallback, backgroundHandler)
    }

    // -------------------------------------------------------------------------
    // Frame processing
    // -------------------------------------------------------------------------

    /**
     * Called by ImageReader for every new frame.
     *
     * Back-pressure guard: if [frameInProgress] is already set, the image is
     * closed immediately and the frame is dropped. This prevents frames from
     * piling up in memory when encoding lags behind capture.
     */
    private fun onImageAvailable(reader: ImageReader, size: Size) {
        // acquireLatestImage() discards older buffered frames automatically.
        val image = reader.acquireLatestImage() ?: return

        if (!frameInProgress.compareAndSet(false, true)) {
            // Encoder still busy — drop this frame to avoid OOM.
            image.close()
            return
        }

        try {
            val nv21     = nv21Buffer    ?: return
            val rotated  = rotatedBuffer ?: return

            imageToNV21Fast(image, nv21, size.width, size.height)
            image.close()

            rotateNv21_180(nv21, rotated, size.width, size.height)

            val jpeg = nv21ToJpeg(rotated, size.width, size.height)
            frameStore.publish(jpeg)

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}", e)
            try { image.close() } catch (_: Exception) {}
        } finally {
            frameInProgress.set(false)
        }
    }

    /**
     * Convert a YUV_420_888 [android.media.Image] to NV21 into [nv21].
     *
     * Uses bulk ByteBuffer.get() when the row stride matches the image width
     * to avoid per-pixel overhead.
     */
    private fun imageToNV21Fast(
        image: android.media.Image,
        nv21: ByteArray,
        width: Int,
        height: Int
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val ySize  = width * height

        // --- Y plane ---
        val yBuffer = yPlane.buffer
        if (yPlane.rowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var offset = 0
            repeat(height) { row ->
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.get(nv21, offset, width)
                offset += width
            }
        }

        // --- VU interleave ---
        val vBuffer       = vPlane.buffer
        val uBuffer       = uPlane.buffer
        val uvRowStride   = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        var offset        = ySize

        if (uvPixelStride == 1) {
            // Planar U / V — interleave manually.
            repeat(height / 2) { row ->
                vBuffer.position(row * uvRowStride)
                uBuffer.position(row * uvRowStride)
                repeat(width / 2) { col ->
                    nv21[offset++] = vBuffer.get(col)
                    nv21[offset++] = uBuffer.get(col)
                }
            }
        } else {
            // Already semi-planar (VU) — bulk copy per row.
            repeat(height / 2) { row ->
                vBuffer.position(row * uvRowStride)
                vBuffer.get(nv21, offset, width - 1)
                offset += width
            }
        }
    }

    /**
     * Rotate an NV21 buffer 180° into [dst] using a simple reverse pass.
     * Both Y and UV planes are handled correctly.
     */
    private fun rotateNv21_180(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        var out   = 0

        // Reverse Y plane.
        for (i in ySize - 1 downTo 0) {
            dst[out++] = src[i]
        }
        // Reverse UV pairs (keep V/U order intact).
        for (i in src.size - 2 downTo ySize step 2) {
            dst[out++] = src[i]
            dst[out++] = src[i + 1]
        }
    }

    /**
     * Encode an NV21 buffer to JPEG using [jpegOutputStream].
     *
     * [jpegOutputStream] is reset() before each call — its internal byte array
     * is reused without re-allocation, eliminating the double-copy that a fresh
     * ByteArrayOutputStream would cause.
     */
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        jpegOutputStream.reset()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, jpegOutputStream)
        return jpegOutputStream.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Capture session
    // -------------------------------------------------------------------------

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
                                // Disable post-processing — reduces per-frame CPU work.
                                set(CaptureRequest.EDGE_MODE,
                                    CaptureRequest.EDGE_MODE_OFF)
                                set(CaptureRequest.NOISE_REDUCTION_MODE,
                                    CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                                set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            .build()
                        session.setRepeatingRequest(request, null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Repeating request failed: ${e.message}", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            },
            backgroundHandler
        )
    }

    // -------------------------------------------------------------------------
    // Resource management
    // -------------------------------------------------------------------------

    private fun stopInternal() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        imageReader?.close();    imageReader     = null
        nv21Buffer    = null
        rotatedBuffer = null
        jpegOutputStream.reset()
        frameInProgress.set(false)
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        handlerThread = HandlerThread(
            "CameraBackground",
            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        handlerThread?.quitSafely()
        try { handlerThread?.join() } catch (_: InterruptedException) {}
        handlerThread   = null
        backgroundHandler = null
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Returns the camera ID for the requested lens facing.
     * Throws [NoSuchElementException] if no matching camera is found.
     */
    private fun getCameraId(useFront: Boolean): String {
        val facing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
        else          CameraCharacteristics.LENS_FACING_BACK
        return cameraManager.cameraIdList.first { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    /**
     * Returns a JSON description of the YUV_420_888 output resolutions
     * available on the current camera, capped at [MAX_STREAM_WIDTH] ×
     * [MAX_STREAM_HEIGHT].
     */
    fun buildCameraOutputMapJson(): String {
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        val streamConfigs   = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val formats = streamConfigs
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width <= MAX_STREAM_WIDTH && it.height <= MAX_STREAM_HEIGHT }
            ?.map { JSONObject().apply { put("width", it.width); put("height", it.height) } }
            ?: emptyList()

        return JSONObject().apply {
            put("ok", true)
            put("formats", JSONArray(formats))
        }.toString()
    }
}