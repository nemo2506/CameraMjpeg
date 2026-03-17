package com.miseservice.cameramjpeg.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size

/**
 * CameraStreamController
 *
 * Controls the camera stream for MJPEG output. Handles camera initialization, frame acquisition,
 * YUV to NV21 conversion, rotation, and JPEG encoding. Buffers are reused for performance.
 *
 * @param context Application context
 * @param frameStore FrameStore instance to publish JPEG frames
 */
class CameraStreamController(
    context: Context,
    private val frameStore: FrameStore
) {
    /** Android CameraManager service */
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentCameraId = ""
    private var isUsingFrontCamera = true
    private var jpegQuality: Int = 80  // JPEG compression quality (default: 80)
    /**
     * Reusable buffers for NV21 and rotated NV21 data.
     * These are allocated once per stream size to reduce GC pressure.
     */
    private var nv21Buffer: ByteArray?    = null
    private var rotatedBuffer: ByteArray? = null

    /**
     * CameraDevice callback to handle camera state changes.
     */
    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startCaptureSession(camera)
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close(); cameraDevice = null
        }
    }

    /**
     * Start the camera stream with the selected camera and JPEG quality.
     * @param useFrontCamera true to use the front camera, false for back
     * @param jpegQuality JPEG compression quality (0-100)
     */
    fun start(useFrontCamera: Boolean, jpegQuality: Int) {
        isUsingFrontCamera = useFrontCamera
        this.jpegQuality   = jpegQuality
        startCamera()
    }

    /**
     * Switch between front and back camera.
     * @param useFront true for front camera, false for back
     */
    fun switchCamera(useFront: Boolean) {
        isUsingFrontCamera = useFront
        stopInternal()
        startCamera()
    }

    /**
     * Update JPEG compression quality.
     * @param newQuality JPEG quality (0-100)
     */
    fun updateQuality(newQuality: Int) { jpegQuality = newQuality }
    /** Stop the camera stream and release resources. */
    fun stop() { stopInternal() }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        startBackgroundThread()
        currentCameraId = getCameraId(isUsingFrontCamera)

        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        val streamConfigs   = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        /**
         * Select the maximum available resolution for streaming (YUV_420_888).
         * Fallback to 1280x720 if not available.
         */
        val size: Size = streamConfigs
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)

        Log.d(TAG, "Stream size: ${size.width}x${size.height}")

        // Allocate buffers for NV21 and rotated NV21 data
        val ySize = size.width * size.height
        nv21Buffer    = ByteArray(ySize + ySize / 2)
        rotatedBuffer = ByteArray(ySize + ySize / 2)

        /**
         * Create an ImageReader for YUV_420_888 frames and set the listener to process each frame.
         * The listener converts the image to NV21, rotates it 180°, encodes to JPEG, and publishes.
         */
        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, 3
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    imageToNV21Fast(image, nv21Buffer!!, size.width, size.height)
                    image.close()

                    rotateNv21_180InPlace(
                        nv21Buffer!!, rotatedBuffer!!,
                        size.width, size.height
                    )

                    val jpeg = nv21ToJpeg(rotatedBuffer!!, size.width, size.height)
                    frameStore.publish(jpeg)

                } catch (e: Exception) {
                    try { image.close() } catch (_: Exception) {}
                    Log.e(TAG, "Error processing image: ${e.message}", e)
                }
            }, backgroundHandler)
        }

        cameraManager.openCamera(currentCameraId, cameraDeviceCallback, backgroundHandler)
    }

    /**
     * Start a camera capture session for the given camera device.
     * Configures the session for preview and disables unnecessary processing.
     * @param camera The opened CameraDevice
     */
    private fun startCaptureSession(camera: CameraDevice) {
        val surface = imageReader?.surface ?: return

        @Suppress("DEPRECATION")
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        requestBuilder.addTarget(surface)
                        // Disable edge, noise, and aberration corrections for performance
                        requestBuilder.set(
                            android.hardware.camera2.CaptureRequest.EDGE_MODE,
                            android.hardware.camera2.CaptureRequest.EDGE_MODE_OFF
                        )
                        requestBuilder.set(
                            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_OFF
                        )
                        requestBuilder.set(
                            android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                            android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
                        )
                        requestBuilder.set(
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                        val request = requestBuilder.build()
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

    /**
     * Convert a YUV_420_888 Image to NV21 format using the provided buffer.
     * This method avoids allocations and uses bulk copy when possible.
     * @param image The YUV_420_888 Image
     * @param nv21 The output NV21 buffer
     * @param width Image width
     * @param height Image height
     */
    private fun imageToNV21Fast(image: android.media.Image, nv21: ByteArray, width: Int, height: Int) {
        val ySize  = width * height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Copy Y plane
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

        // Copy interleaved VU planes
        val vBuffer       = vPlane.buffer
        val uBuffer       = uPlane.buffer
        val uvRowStride   = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        var offset        = ySize

        if (uvPixelStride == 1) {
            repeat(height / 2) { row ->
                vBuffer.position(row * uvRowStride)
                uBuffer.position(row * uvRowStride)
                repeat(width / 2) { col ->
                    nv21[offset++] = vBuffer.get(col)
                    nv21[offset++] = uBuffer.get(col)
                }
            }
        } else {
            // Already interleaved, bulk copy
            repeat(height / 2) { row ->
                vBuffer.position(row * uvRowStride)
                vBuffer.get(nv21, offset, width - 1)
                offset += width
            }
        }
    }

    /**
     * Rotate an NV21 image 180 degrees in-place using a pre-allocated buffer.
     * @param src Source NV21 buffer
     * @param dst Destination buffer for rotated NV21
     * @param width Image width
     * @param height Image height
     */
    private fun rotateNv21_180InPlace(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        var out   = 0

        for (i in ySize - 1 downTo 0) {
            dst[out++] = src[i]
        }
        for (i in src.size - 2 downTo ySize step 2) {
            dst[out++] = src[i]
            dst[out++] = src[i + 1]
        }
    }

    /**
     * Encode an NV21 image to JPEG using the specified quality.
     * @param nv21 NV21 image buffer
     * @param width Image width
     * @param height Image height
     * @return JPEG-encoded byte array
     */
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
        return out.toByteArray()
    }

    /**
     * Stop and release all camera and background resources.
     */
    private fun stopInternal() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        imageReader?.close();    imageReader     = null
        stopBackgroundThread()
    }

    /**
     * Start a background thread for camera operations.
     */
    private fun startBackgroundThread() {
        handlerThread = HandlerThread("CameraBackground", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    /**
     * Stop the background thread and release resources.
     */
    private fun stopBackgroundThread() {
        handlerThread?.quitSafely()
        try { handlerThread?.join() } catch (_: InterruptedException) {}
        handlerThread = null
        backgroundHandler = null
    }

    /**
     * Get the camera ID for the requested facing (front or back).
     * @param useFront true for front camera, false for back
     * @return Camera ID string
     */
    private fun getCameraId(useFront: Boolean): String {
        val facing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
                     else          CameraCharacteristics.LENS_FACING_BACK
        return cameraManager.cameraIdList.first { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    private companion object {
        const val TAG = "CameraStreamController"
    }
}
