package com.miseservice.cameramjpeg.util

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.miseservice.cameramjpeg.streaming.FrameStore

/**
 * SnapshotHandler
 *
 * Handles HTTP GET /snapshot.jpg requests.
 *
 * Retrieves the latest JPEG frame from [FrameStore], injects EXIF metadata
 * via [ExifInjector] (< 1 ms overhead), and writes the result to the
 * HTTP response.
 *
 * Integration — call handleSnapshot() from your existing HTTP server
 * wherever you handle the /snapshot.jpg route.
 *
 * Example with NanoHTTPD:
 * ──────────────────────
 *   override fun serve(session: IHTTPSession): Response {
 *       return when (session.uri) {
 *           "/snapshot.jpg" -> snapshotHandler.handleSnapshot()
 *           else            -> super.serve(session)
 *       }
 *   }
 *
 * @param frameStore        Source of the latest JPEG frame.
 * @param rotationDegrees   Rotation applied by the pipeline (0/90/180/270).
 *                          Pass CameraStreamController.lastRotationAngle if exposed.
 * @param getCaptureResult  Lambda returning the most recent [CaptureResult] (nullable).
 * @param characteristics   [CameraCharacteristics] for the active camera (nullable).
 */
class SnapshotHandler(
    private val frameStore: FrameStore,
    private val rotationDegrees: Int = 0,
    private val getCaptureResult: () -> CaptureResult? = { null },
    private val characteristics: CameraCharacteristics? = null
) {

    /**
     * Returns the latest frame with EXIF injected, ready for HTTP delivery.
     *
     * Returns null if no frame is available yet (camera not started).
     *
     * Performance:
     *   FrameStore.latest()   ~0 ms  (in-memory reference)
     *   ExifInjector.inject() ~0.47 ms
     *   Total overhead        < 1 ms
     */
    fun getSnapshotBytes(): ByteArray? {
        val raw = frameStore.latest() ?: return null

        return ExifInjector.inject(
            jpeg            = raw,
            rotationDegrees = rotationDegrees,
            captureResult   = getCaptureResult(),
            characteristics = characteristics
        )
    }

    // ── NanoHTTPD response helper (adapt to your HTTP server) ────────────────

    /**
     * Builds a complete HTTP/1.1 200 response for /snapshot.jpg.
     *
     * Headers set:
     *   Content-Type  : image/jpeg
     *   Cache-Control : no-store  (always serve fresh frame)
     *   Content-Length: <bytes>
     *
     * Returns a 503 response body string if no frame is available.
     */
    fun buildHttpResponse(): SnapshotResponse {
        val bytes = getSnapshotBytes()
            ?: return SnapshotResponse(
                statusCode = 503,
                body       = "No frame available yet".toByteArray(),
                mimeType   = "text/plain"
            )

        return SnapshotResponse(
            statusCode = 200,
            body       = bytes,
            mimeType   = "image/jpeg",
            headers    = mapOf(
                "Cache-Control" to "no-store",
                "Content-Length" to bytes.size.toString()
            )
        )
    }
}

/**
 * Simple HTTP response container — adapt to your actual HTTP server API.
 */
data class SnapshotResponse(
    val statusCode: Int,
    val body:       ByteArray,
    val mimeType:   String,
    val headers:    Map<String, String> = emptyMap()
)
