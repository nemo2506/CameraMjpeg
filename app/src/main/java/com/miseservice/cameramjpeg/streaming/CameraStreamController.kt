package com.miseservice.cameramjpeg.streaming

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraStreamController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val frameStore: FrameStore
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var useFrontCamera: Boolean = false
    private var jpegQuality: Int = 92

    suspend fun start(useFrontCamera: Boolean, jpegQuality: Int) {
        this.useFrontCamera = useFrontCamera
        this.jpegQuality = jpegQuality
        val provider = cameraProvider ?: createProvider().also { cameraProvider = it }
        bind(provider)
    }

    fun switchCamera(useFront: Boolean) {
        useFrontCamera = useFront
        cameraProvider?.let { bind(it) }
    }

    fun updateQuality(newQuality: Int) {
        jpegQuality = newQuality
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }

    private suspend fun createProvider(): ProcessCameraProvider = withContext(Dispatchers.Main) {
        val future = ProcessCameraProvider.getInstance(context)
        suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
    }

    private fun bind(provider: ProcessCameraProvider) {
        provider.unbindAll()

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                    handleImage(image)
                }
            }

        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        provider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
    }

    private fun handleImage(image: ImageProxy) {
        try {
            val jpeg = image.toJpeg(jpegQuality)
            frameStore.publish(jpeg)
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toJpeg(quality: Int): ByteArray {
        val nv21 = yuv420ToNv21(planes, width, height)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return ByteArrayOutputStream().use { stream ->
            yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, stream)
            stream.toByteArray()
        }
    }

    private fun yuv420ToNv21(planes: Array<ImageProxy.PlaneProxy>, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        copyPlane(
            buffer = planes[0].buffer,
            width = width,
            height = height,
            rowStride = planes[0].rowStride,
            pixelStride = planes[0].pixelStride,
            out = nv21,
            offset = 0,
            outputStride = 1
        )
        copyPlane(
            buffer = planes[2].buffer,
            width = width / 2,
            height = height / 2,
            rowStride = planes[2].rowStride,
            pixelStride = planes[2].pixelStride,
            out = nv21,
            offset = ySize,
            outputStride = 2
        )
        copyPlane(
            buffer = planes[1].buffer,
            width = width / 2,
            height = height / 2,
            rowStride = planes[1].rowStride,
            pixelStride = planes[1].pixelStride,
            out = nv21,
            offset = ySize + 1,
            outputStride = 2
        )

        return nv21
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        out: ByteArray,
        offset: Int,
        outputStride: Int
    ) {
        val rowData = ByteArray(rowStride)
        var outputPos = offset
        buffer.rewind()
        for (row in 0 until height) {
            val length = if (pixelStride == 1 && outputStride == 1) width else (width - 1) * pixelStride + 1
            buffer.get(rowData, 0, length)
            var inputPos = 0
            repeat(width) {
                out[outputPos] = rowData[inputPos]
                outputPos += outputStride
                inputPos += pixelStride
            }
            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - length)
            }
        }
    }
}
