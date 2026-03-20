package com.miseservice.cameramjpeg.streaming

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Log
import com.miseservice.cameramjpeg.util.ExifInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Embedded HTTP server for MJPEG streaming and monitoring endpoints.
 * Handles multiple clients, provides status, battery, camera formats endpoints,
 * and serves the monitoring HTML page.
 *
 * Snapshot EXIF injection
 * ───────────────────────
 * GET /snapshot.jpg injects EXIF metadata (DateTime, Orientation,
 * ExposureTime, FNumber, ISOSpeedRatings) via [ExifInjector] before
 * sending the JPEG to the client. Overhead: ~0.47 ms, +186 bytes.
 * The MJPEG stream is NOT affected — frames are sent raw.
 *
 * To enable EXIF on snapshots, supply [snapshotRotationDegrees],
 * [getCaptureResult], and [cameraCharacteristics] at construction time.
 * All three are optional; ExifInjector degrades gracefully when null.
 *
 * @param port                    TCP listening port.
 * @param frameStore              Store for the latest JPEG frame.
 * @param batteryStatusProvider   Battery status provider.
 * @param faviconProvider         Favicon provider.
 * @param cameraFormatsProvider   Camera formats provider (JSON).
 * @param snapshotRotationDegrees Rotation applied by the pipeline (0/90/180/270).
 * @param getCaptureResult        Lambda returning the latest [CaptureResult] (nullable).
 * @param cameraCharacteristics   Active camera characteristics (nullable).
 */
class MjpegHttpServer(
    private val port: Int,
    private val frameStore: FrameStore,
    private val batteryStatusProvider: () -> BatteryStatus?,
    private val faviconProvider: () -> ByteArray?,
    private val cameraFormatsProvider: () -> String,
    private val snapshotRotationDegrees: Int = 0,
    private val getCaptureResult: () -> CaptureResult? = { null },
    private val cameraCharacteristics: CameraCharacteristics? = null
) {
    private val tag = "MjpegHttpServer"
    private var scope: CoroutineScope = newScope()
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val startedAtMs = System.currentTimeMillis()
    private val metricsLock = Any()

    @Volatile private var totalFramesSent: Long = 0
    @Volatile private var totalBytesSent: Long = 0
    @Volatile private var activeStreamClients: Int = 0
    @Volatile private var fpsEstimate: Int = 0
    private var fpsWindowStartMs: Long = System.currentTimeMillis()
    private var fpsWindowFrames: Int = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Starts the HTTP server and begins accepting client connections. */
    fun start() {
        if (acceptJob?.isActive == true) return
        if (!scope.isActive) scope = newScope()
        acceptJob = scope.launch {
            try {
                val ss = ServerSocket().also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(port))
                }
                serverSocket = ss
                while (isActive) {
                    val client = try { ss.accept() } catch (_: Exception) { break }
                    clients += client
                    launch {
                        runCatching { handleClient(client) }
                            .onFailure { error ->
                                Log.e(tag, "Client handling failed", error)
                                runCatching {
                                    sendText(
                                        client, 500,
                                        "application/json; charset=utf-8",
                                        "{\"ok\":false,\"code\":500,\"message\":\"internal_error\"}"
                                    )
                                }
                                runCatching { client.close() }
                            }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "HTTP server accept loop failed", e)
            }
        }
    }

    /** Stops the HTTP server and disconnects all clients. */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request routing
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 10_000
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
                )
                val requestLine = reader.readLine() ?: return
                val parts  = requestLine.split(" ")
                val target = parts.getOrNull(1) ?: "/"
                val path   = target.substringBefore("?")
                while (reader.readLine()?.isNotEmpty() == true) { /* skip headers */ }
                socket.soTimeout = 0
                when {
                    path == "/" || path == "/monitor" || path == "/viewer" -> monitorPage(socket)
                    path == "/favicon.ico"          -> favicon(socket)
                    path.startsWith("/stream.mjpeg") -> stream(socket)
                    path.startsWith("/snapshot.jpg") -> snapshot(socket)
                    path == "/api/status"            -> status(socket)
                    path == "/api/battery"           -> battery(socket)
                    path == "/api/camera/formats"    -> cameraFormats(socket)
                    else                             -> monitorPage(socket)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Request handling failed", e)
            runCatching {
                sendText(
                    client, 500,
                    "application/json; charset=utf-8",
                    "{\"ok\":false,\"code\":500,\"message\":\"internal_error\"}"
                )
            }
        } finally {
            clients.remove(client)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Endpoint handlers
    // ─────────────────────────────────────────────────────────────────────────

    private fun monitorPage(socket: Socket) {
        val pageTitle = escapeHtml(deviceModelNumber())
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>$pageTitle</title>
            <link rel="icon" href="/favicon.ico" type="image/png">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
            html,body{width:100%;height:100%;margin:0}
            body{background:#07090d;color:#d8deea;font-family:Arial,Helvetica,sans-serif;overflow:hidden}
            .stage{position:relative;width:100%;height:100%}
            .stream-wrap{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:#000}
            #stream{max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain}
            .bottom-frame{
              position:absolute;left:50%;bottom:16px;transform:translateX(-50%);
              display:flex;align-items:center;gap:16px;padding:10px 14px;
              border:1px solid #2c3748;border-radius:12px;
              background:rgba(16,21,30,.86);backdrop-filter:blur(6px);
            }
            .snapshot-btn{width:44px;height:44px;border:1px solid #3e4d66;border-radius:10px;background:#1d2532;display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0}
            .snapshot-btn:hover{background:#263246}
            .snapshot-btn svg{width:20px;height:20px;fill:#d8deea}
            .tech{display:flex;gap:14px;font-size:14px;font-variant-numeric:tabular-nums}
            .chip{display:flex;align-items:center;gap:6px;padding:6px 8px;border:1px solid #2c3748;border-radius:8px;background:#111824}
            .chip svg{width:14px;height:14px;fill:#8fb8ff}
            .chip-ok{border-color:#28553c;color:#b9f4cb}
            .chip-mid{border-color:#6a5120;color:#ffd892}
            .chip-low{border-color:#6f2f38;color:#ffc4cb}
            .chip-muted{border-color:#2c3748;color:#c4cede}
            </style>
            </head>
            <body>
            <div class="stage">
              <div class="stream-wrap"><img id="stream" src="/stream.mjpeg"/></div>
              <div class="bottom-frame">
                <button class="snapshot-btn" onclick="window.open('/snapshot.jpg','_blank')" title="Snapshot" aria-label="Snapshot">
                  <svg viewBox="0 0 24 24"><path d="M9 4l-2 2H4v14h16V6h-3l-2-2zm3 4a5 5 0 1 1 0 10 5 5 0 0 1 0-10z"/></svg>
                </button>
                <div class="tech">
                  <div class="chip"><svg viewBox="0 0 24 24"><path d="M12 3l9 4v6c0 5-3.5 8.8-9 9-5.5-.2-9-4-9-9V7l9-4z"/></svg><span id="fps">0 fps</span></div>
                  <div class="chip"><svg viewBox="0 0 24 24"><path d="M4 4h16v16H4z"/></svg><span id="res">0x0</span></div>
                  <div class="chip chip-muted" id="battery-chip"><svg viewBox="0 0 24 24"><path d="M16 7V6a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v1H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2zm-6-1h4v1h-4zm3.5 10.5H9.75V9.5h3.75a1.75 1.75 0 1 1 0 3.5h-2v3.5h-1.5V11.5h2a.25.25 0 1 0 0-.5H9.75v5.5h3.75z"/></svg><span id="battery">--%</span></div>
                </div>
              </div>
            </div>
            <script>
            async function api(path){const r=await fetch(path);const t=await r.text();try{return JSON.parse(t);}catch(_){return{ok:false};}}
            async function refreshStatus(){const s=await api('/api/status');if(!s)return;document.getElementById('fps').textContent=String(s.fps||0)+' fps';}
            async function refreshBattery(){
              try{
                const b=await api('/api/battery');
                const el=document.getElementById('battery');
                const chip=document.getElementById('battery-chip');
                chip.className='chip chip-muted';
                if(!b||b.ok===false||typeof b.levelPercent!=='number'){el.textContent='batterie --';return;}
                const parts=[String(b.levelPercent)+'%'];
                if(typeof b.temperatureC==='number')parts.push(String(Math.round(b.temperatureC))+'°C');
                if(b.isCharging===true)parts.push('⚡');
                el.textContent=parts.join(' • ');
                if(b.levelPercent<20)chip.className='chip chip-low';
                else if(b.levelPercent<50)chip.className='chip chip-mid';
                else chip.className='chip chip-ok';
              }catch(_){document.getElementById('battery').textContent='batterie --';}
            }
            function refreshResolution(){const img=document.getElementById('stream');document.getElementById('res').textContent=(img.naturalWidth||0)+'x'+(img.naturalHeight||0);}
            refreshStatus();refreshBattery();refreshResolution();
            setInterval(refreshStatus,1000);setInterval(refreshBattery,5000);setInterval(refreshResolution,1000);
            </script>
            </body></html>
        """.trimIndent()
        sendText(socket, 200, "text/html; charset=utf-8", html)
    }

    private fun status(socket: Socket) {
        val latest  = frameStore.latest()
        val uptime  = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0)
        val json = """
            {
              "port":$port,
              "clients":${clients.size},
              "streamClients":${activeStreamClients.coerceAtLeast(0)},
              "fps":$fpsEstimate,
              "uptimeSec":$uptime,
              "latestFrameBytes":${latest?.size ?: 0},
              "totalFrames":$totalFramesSent,
              "totalBytes":$totalBytesSent
            }
        """.trimIndent()
        sendText(socket, 200, "application/json; charset=utf-8", json)
    }

    private fun battery(socket: Socket) {
        val b = batteryStatusProvider()
        if (b == null) {
            sendText(socket, 503, "application/json; charset=utf-8",
                "{\"ok\":false,\"code\":503,\"message\":\"battery_unavailable\"}")
            return
        }
        val json = """
            {
              "ok":true,
              "levelPercent":${b.levelPercent},
              "isCharging":${b.charging},
              "temperatureC":${b.temperatureC?.toString() ?: "null"},
              "timestampMs":${b.timestampMs}
            }
        """.trimIndent()
        sendText(socket, 200, "application/json; charset=utf-8", json)
    }

    private fun cameraFormats(socket: Socket) {
        val payload = runCatching { cameraFormatsProvider() }
            .getOrElse { "{\"ok\":false,\"code\":500,\"message\":\"camera_formats_error\"}" }
        sendText(socket, 200, "application/json; charset=utf-8", payload)
    }

    private fun favicon(socket: Socket) {
        val icon = faviconProvider()
        if (icon == null || icon.isEmpty()) {
            sendText(socket, 404, "text/plain; charset=utf-8", "favicon not found")
            return
        }
        sendBytes(socket, 200, "image/png", icon)
    }

    /**
     * Serves a single JPEG snapshot with EXIF metadata injected.
     *
     * EXIF fields added (overhead ~0.47 ms, +186 bytes):
     *   • DateTime / DateTimeOriginal — current system time
     *   • Orientation                 — from [snapshotRotationDegrees]
     *   • ExposureTime                — from [getCaptureResult] (if available)
     *   • FNumber                     — from [cameraCharacteristics] (if available)
     *   • ISOSpeedRatings             — from [getCaptureResult] (if available)
     *
     * The MJPEG stream frames are NOT affected by this injection.
     */
    private fun snapshot(socket: Socket) {
        val raw = frameStore.latest()
        val out = BufferedOutputStream(socket.getOutputStream())

        if (raw == null) {
            out.write(
                ("HTTP/1.1 503 Service Unavailable\r\n" +
                 "Content-Length: 0\r\n" +
                 "Connection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII)
            )
            out.flush()
            return
        }

        // Inject EXIF — < 1 ms, safe to call on the IO coroutine thread.
        val frame = ExifInjector.inject(
            jpeg            = raw,
            rotationDegrees = snapshotRotationDegrees,
            captureResult   = getCaptureResult(),
            characteristics = cameraCharacteristics
        )

        out.write(
            ("HTTP/1.1 200 OK\r\n" +
             "Content-Type: image/jpeg\r\n" +
             "Content-Length: ${frame.size}\r\n" +
             "Cache-Control: no-store\r\n" +
             "Connection: close\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII)
        )
        out.write(frame)
        out.flush()
    }

    /**
     * Streams MJPEG frames using multipart/x-mixed-replace.
     * Raw frames only — no EXIF injection on the stream.
     */
    private fun stream(socket: Socket) {
        val boundary = "mjpegframe"
        val out      = BufferedOutputStream(socket.getOutputStream())
        synchronized(metricsLock) { activeStreamClients += 1 }
        try {
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                 "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n" +
                 "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                 "Pragma: no-cache\r\n" +
                 "Connection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII)
            )
            out.flush()

            var sequence = 0L
            while (!socket.isClosed) {
                val next  = frameStore.awaitNext(sequence, 3_000) ?: continue
                sequence  = next.first
                val frame = next.second
                registerFrameSent(frame.size)
                out.write(
                    ("--$boundary\r\n" +
                     "Content-Type: image/jpeg\r\n" +
                     "Content-Length: ${frame.size}\r\n\r\n")
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                out.write(frame)
                out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            }
        } catch (_: Exception) {
            // Client disconnected or server stopped — normal exit.
        } finally {
            synchronized(metricsLock) {
                activeStreamClients = (activeStreamClients - 1).coerceAtLeast(0)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metrics
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerFrameSent(size: Int) {
        synchronized(metricsLock) {
            totalFramesSent += 1
            totalBytesSent  += size.toLong()
            fpsWindowFrames += 1
            val now     = System.currentTimeMillis()
            val elapsed = now - fpsWindowStartMs
            if (elapsed >= 1000L) {
                fpsEstimate       = ((fpsWindowFrames * 1000L) / elapsed).toInt()
                fpsWindowFrames   = 0
                fpsWindowStartMs  = now
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — static helpers
    // ─────────────────────────────────────────────────────────────────────────

    private companion object {

        fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&").mapNotNull {
                val key = it.substringBefore("=", "").takeIf { k -> k.isNotBlank() }
                    ?: return@mapNotNull null
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(
                    it.substringAfter("=", ""), "UTF-8"
                )
            }.toMap()
        }

        fun escapeHtml(value: String) = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        fun deviceModelNumber(): String =
            Build.MODEL.cleanDeviceNamePart() ?: "Appareil"

        fun String?.cleanDeviceNamePart(): String? {
            val v = this?.trim()?.replace("_", " ")
                ?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }
                ?: return null
            return if (v.lowercase() in setOf("unknown", "generic", "android")) null else v
        }

        fun sendText(socket: Socket, code: Int, contentType: String, body: String) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            BufferedOutputStream(socket.getOutputStream()).also { out ->
                out.write(
                    ("HTTP/1.1 $code ${statusText(code)}\r\n" +
                     "Content-Type: $contentType\r\n" +
                     "Content-Length: ${payload.size}\r\n" +
                     "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                )
                out.write(payload)
                out.flush()
            }
        }

        fun sendBytes(socket: Socket, code: Int, contentType: String, payload: ByteArray) {
            BufferedOutputStream(socket.getOutputStream()).also { out ->
                out.write(
                    ("HTTP/1.1 $code ${statusText(code)}\r\n" +
                     "Content-Type: $contentType\r\n" +
                     "Content-Length: ${payload.size}\r\n" +
                     "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                )
                out.write(payload)
                out.flush()
            }
        }

        fun statusText(code: Int) = when (code) {
            200  -> "OK"
            400  -> "Bad Request"
            404  -> "Not Found"
            500  -> "Internal Server Error"
            503  -> "Service Unavailable"
            else -> "OK"
        }

        fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BatteryStatus
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Battery status snapshot for the /api/battery endpoint.
 *
 * @property levelPercent  Battery level [0, 100].
 * @property charging      True when a charger is connected.
 * @property status        Human-readable status string ("charging", "discharging", …).
 * @property temperatureC  Battery temperature in °C, null if unavailable.
 * @property timestampMs   Wall-clock timestamp when this snapshot was taken.
 */
data class BatteryStatus(
    val levelPercent: Int,
    val charging: Boolean,
    val status: String,
    val temperatureC: Float?,
    val timestampMs: Long
)