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
// ...existing imports...
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size

class CameraStreamController(
    context: Context,
    private val frameStore: FrameStore
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentCameraId = ""
    private var isUsingFrontCamera = true
    private var jpegQuality: Int = 80  // ← 80 au lieu de 92
    // Buffers réutilisables
    private var nv21Buffer: ByteArray?    = null
    private var rotatedBuffer: ByteArray? = null

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

    fun start(useFrontCamera: Boolean, jpegQuality: Int) {
        isUsingFrontCamera = useFrontCamera
        this.jpegQuality   = jpegQuality
        startCamera()
    }

    fun switchCamera(useFront: Boolean) {
        isUsingFrontCamera = useFront
        stopInternal()
        startCamera()
    }

    fun updateQuality(newQuality: Int) { jpegQuality = newQuality }
    fun stop() { stopInternal() }

    // ...existing code...

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        startBackgroundThread()
        currentCameraId = getCameraId(isUsingFrontCamera)

        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        val streamConfigs   = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // ✅ Résolution maximale streaming : prendre la plus grande possible
        val size: Size = streamConfigs
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)

        Log.d(TAG, "Stream size: ${size.width}x${size.height}")

        // Pré-allouer tous les buffers
        val ySize = size.width * size.height
        nv21Buffer    = ByteArray(ySize + ySize / 2)
        rotatedBuffer = ByteArray(ySize + ySize / 2)

        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, 3 // ← 3 buffers
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    imageToNV21Fast(image, nv21Buffer!!, size.width, size.height)
                    image.close() // ← fermer le plus tôt possible

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

    private fun startCaptureSession(camera: CameraDevice) {
        val surface = imageReader?.surface ?: return

        @Suppress("DEPRECATION")
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
                                // ✅ Désactiver les traitements inutiles
                                set(android.hardware.camera2.CaptureRequest.EDGE_MODE,
                                    android.hardware.camera2.CaptureRequest.EDGE_MODE_OFF)
                                set(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                                    android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                set(android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                                    android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                                set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
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

    // Conversion optimisée YUV_420_888 → NV21 (buffer réutilisé, bulk copy si possible)
    private fun imageToNV21Fast(image: Image, nv21: ByteArray, width: Int, height: Int) {
        val ySize  = width * height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Plan Y
        val yBuffer = yPlane.buffer.also { it.position(0) }
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

        // Plans U/V
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
            // Déjà entrelacé → bulk copy
            repeat(height / 2) { row ->
                vBuffer.position(row * uvRowStride)
                vBuffer.get(nv21, offset, width - 1)
                offset += width
            }
        }
    }

    // Rotation 180° dans buffer pré-alloué
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

    // Conversion NV21 → JPEG
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
        return out.toByteArray()
    }

    private fun stopInternal() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        imageReader?.close();    imageReader     = null
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        handlerThread = HandlerThread("CameraBackground", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        handlerThread?.quitSafely()
        try { handlerThread?.join() } catch (_: InterruptedException) {}
        handlerThread = null
        backgroundHandler = null
    }

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
