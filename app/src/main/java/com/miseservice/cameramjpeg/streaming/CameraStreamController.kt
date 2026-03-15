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
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CameraStreamController(
    private val context: Context,
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
    private var jpegQuality: Int = 92

    private val _latestFrameData = MutableStateFlow<ByteArray?>(null)
    val latestFrameData: StateFlow<ByteArray?> = _latestFrameData

    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    fun start(useFrontCamera: Boolean, jpegQuality: Int) {
        isUsingFrontCamera = useFrontCamera
        this.jpegQuality = jpegQuality
        startCamera()
    }

    fun switchCamera(useFront: Boolean) {
        isUsingFrontCamera = useFront
        stopInternal()
        startCamera()
    }

    fun updateQuality(newQuality: Int) {
        jpegQuality = newQuality
    }

    fun stop() {
        stopInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        startBackgroundThread()

        currentCameraId = getCameraId(isUsingFrontCamera)
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        val streamConfigs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size: Size = streamConfigs
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)

        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, 2
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        val nv21 = imageToNV21(image)
                        image.close()
                        _latestFrameData.value = nv21
                        val jpeg = nv21ToJpeg(nv21, size.width, size.height)
                        frameStore.publish(jpeg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image: ${e.message}", e)
                }
            }, backgroundHandler)
        }

        Log.d(TAG, "Using camera: $currentCameraId (front=$isUsingFrontCamera)")
        cameraManager.openCamera(currentCameraId, cameraDeviceCallback, backgroundHandler)
    }

    private fun startCaptureSession(camera: CameraDevice) {
        val reader = imageReader ?: return
        val surface: Surface = reader.surface

        @Suppress("DEPRECATION")
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val request = camera
                            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply { addTarget(surface) }
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

    private fun stopInternal() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        handlerThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        handlerThread?.quitSafely()
        try { handlerThread?.join() } catch (_: InterruptedException) { }
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

    private fun imageToNV21(image: Image): ByteArray {
        val planes = image.planes
        val width  = image.width
        val height = image.height
        val ySize  = width * height
        val uvSize = ySize / 4
        val nv21   = ByteArray(ySize + uvSize * 2)

        copyPlane(planes[0].buffer, width,     height,     planes[0].rowStride, planes[0].pixelStride, nv21, 0,          1)
        copyPlane(planes[2].buffer, width / 2, height / 2, planes[2].rowStride, planes[2].pixelStride, nv21, ySize,      2)
        copyPlane(planes[1].buffer, width / 2, height / 2, planes[1].rowStride, planes[1].pixelStride, nv21, ySize + 1,  2)

        return nv21
    }

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
            out.toByteArray()
        }
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
        out: ByteArray, offset: Int, outputStride: Int
    ) {
        val rowData = ByteArray(rowStride)
        var outputPos = offset
        buffer.rewind()
        for (row in 0 until height) {
            val length = if (pixelStride == 1 && outputStride == 1) width
                         else (width - 1) * pixelStride + 1
            buffer.get(rowData, 0, length)
            var inputPos = 0
            repeat(width) {
                out[outputPos] = rowData[inputPos]
                outputPos += outputStride
                inputPos  += pixelStride
            }
            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - length)
            }
        }
    }

    private companion object {
        const val TAG = "CameraStreamController"
    }
}
