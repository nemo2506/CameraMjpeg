package com.miseservice.cameramjpeg.streaming

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

class MjpegHttpServer(
    private val port: Int,
    private val frameStore: FrameStore,
    private val imageManagementService: ImageManagementService
) {
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

    fun start() {
        if (acceptJob?.isActive == true) return
        if (!scope.isActive) scope = newScope()
        acceptJob = scope.launch {
            val ss = ServerSocket().also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(port))
            }
            serverSocket = ss
            while (isActive) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                clients += client
                launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 10_000
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
                )
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore("?")
                val query = parseQuery(target.substringAfter("?", ""))
                while (reader.readLine()?.isNotEmpty() == true) { /* skip headers */ }
                socket.soTimeout = 0  // pas de timeout pendant l'écriture
                when {
                    path == "/" || path == "/monitor" -> monitorPage(socket)
                    path == "/viewer" -> monitorPage(socket)
                    path.startsWith("/stream.mjpeg") -> stream(socket)
                    path.startsWith("/snapshot.jpg") -> snapshot(socket)
                    path == "/api/status" -> status(socket)
                    path == "/api/image/save" && (method == "POST" || method == "GET") -> saveImage(socket)
                    path == "/api/image/list" -> listImages(socket)
                    path == "/api/image/delete" && (method == "POST" || method == "GET") -> deleteImage(socket, query["name"])
                    path == "/api/image/clear" && (method == "POST" || method == "GET") -> clearImages(socket)
                    else -> monitorPage(socket)
                }
            }
        } finally {
            clients.remove(client)
        }
    }

    private fun monitorPage(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>Monitoring</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
            body{background:#0f1115;color:#d8deea;font-family:Arial;margin:0;padding:12px}
            .layout{display:grid;grid-template-columns:2fr 1fr;gap:10px}
            .panel{background:#171b23;border:1px solid #2a3242;border-radius:10px;padding:10px}
            .grid{display:grid;grid-template-columns:repeat(4,minmax(60px,1fr));gap:8px;margin-bottom:10px}
            .kpi{background:#121723;border:1px solid #2a3242;border-radius:8px;padding:10px;display:flex;align-items:center;justify-content:space-between}
            .kpi svg{width:18px;height:18px;fill:#8fb8ff;opacity:.95}
            .kpi .v{font-size:16px;font-weight:700;font-variant-numeric:tabular-nums}
            img{width:100%;height:auto;border:1px solid #2a3242;border-radius:10px;display:block;background:#000;min-height:220px}
            .controls{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
            .icon-btn{background:#1d2532;color:#d8deea;border:1px solid #324158;border-radius:8px;width:36px;height:36px;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;padding:0}
            .icon-btn:hover{background:#263246}
            .icon-btn svg{width:18px;height:18px;fill:#d8deea}
            .list{margin-top:8px;background:#121723;border:1px solid #2a3242;border-radius:8px;padding:8px;max-height:320px;overflow:auto}
            .row{display:flex;justify-content:space-between;gap:10px;padding:6px 0;border-bottom:1px solid #232b3a}
            .row:last-child{border-bottom:none}
            .mono{font-family:Consolas,monospace;font-size:12px;opacity:.9}
            @media (max-width:900px){ .layout{grid-template-columns:1fr;} }
            </style>
            </head>
            <body>
            <div class="grid">
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M4 5h16v14H4zM2 3v18h20V3z"/></svg><div class="v" id="port">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M12 3l9 4v6c0 5-3.5 8.8-9 9-5.5-.2-9-4-9-9V7l9-4z"/></svg><div class="v" id="fps">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M4 4h16v16H4z"/></svg><div class="v" id="clients">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 10 10h-2a8 8 0 1 1-2.34-5.66L16 8a6 6 0 1 0 1.76 4.24h2.24A8 8 0 1 1 12 2z"/></svg><div class="v" id="up">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M3 6h18v12H3zM1 4v16h22V4z"/></svg><div class="v" id="bytes">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M6 4h12l1 4H5l1-4zm-1 6h14v10H5z"/></svg><div class="v" id="saved">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M4 20h16v-2H4v2zm0-4h16v-2H4v2zm0-4h16V6H4v6z"/></svg><div class="v" id="frames">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M4 4h16v16H4zM8 8h8v8H8z"/></svg><div class="v" id="tbytes">0</div></div>
            </div>

            <div class="layout">
              <div class="panel">
                <img src="/stream.mjpeg"/>
                <div class="controls">
                  <button class="icon-btn" onclick="saveImage()" title="Enregistrer" aria-label="Enregistrer"><svg viewBox="0 0 24 24"><path d="M5 3h11l3 3v15H5zM7 5v5h8V5zm0 9h10v5H7z"/></svg></button>
                  <button class="icon-btn" onclick="window.open('/snapshot.jpg','_blank')" title="Snapshot" aria-label="Snapshot"><svg viewBox="0 0 24 24"><path d="M9 4l-2 2H4v14h16V6h-3l-2-2zm3 4a5 5 0 1 1 0 10 5 5 0 0 1 0-10z"/></svg></button>
                  <button class="icon-btn" onclick="refreshStatus()" title="Rafraichir" aria-label="Rafraichir"><svg viewBox="0 0 24 24"><path d="M12 4a8 8 0 1 0 7.75 10h-2.1A6 6 0 1 1 16 8l-2 2h6V4l-2.4 2.4A7.96 7.96 0 0 0 12 4z"/></svg></button>
                  <button class="icon-btn" onclick="window.location='/monitor'" title="Monitoring" aria-label="Monitoring"><svg viewBox="0 0 24 24"><path d="M3 3h18v18H3zm2 2v14h14V5zm2 10h2v2H7zm4-4h2v6h-2zm4-3h2v9h-2z"/></svg></button>
                </div>
                <div class="mono" id="meta">0</div>
              </div>
              <div class="panel">
                <div class="controls">
                  <button class="icon-btn" onclick="refreshList()" title="Lister" aria-label="Lister"><svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z"/></svg></button>
                  <button class="icon-btn" onclick="clearImages()" title="Vider" aria-label="Vider"><svg viewBox="0 0 24 24"><path d="M6 7h12l-1 13H7L6 7zm3-3h6l1 2H8l1-2z"/></svg></button>
                </div>
                <div id="list" class="list"></div>
              </div>
            </div>

            <script>
            async function api(path, opt){
              const res = await fetch(path, opt || {});
              const txt = await res.text();
              try { return JSON.parse(txt); } catch (_) { return { ok:false, raw:txt }; }
            }
            function setNum(id, value){ document.getElementById(id).textContent = String(value || 0); }
            function fmtBytes(n){
              const v = Number(n||0);
              if (v < 1024) return v + 'B';
              if (v < 1024*1024) return (v/1024).toFixed(1) + 'K';
              return (v/(1024*1024)).toFixed(1) + 'M';
            }
            async function refreshStatus(){
              const s = await api('/api/status');
              if (!s) return;
              setNum('port', s.port);
              setNum('fps', s.fps);
              setNum('clients', s.streamClients);
              setNum('up', s.uptimeSec);
              setNum('bytes', fmtBytes(s.latestFrameBytes));
              setNum('saved', s.savedCount);
              setNum('frames', s.totalFrames);
              setNum('tbytes', fmtBytes(s.totalBytes));
              document.getElementById('meta').textContent = `http:${'$'}{s.port} | c:${'$'}{s.clients} | sc:${'$'}{s.streamClients} | f:${'$'}{s.totalFrames}`;
            }
            async function refreshList(){
              const data = await api('/api/image/list');
              const box = document.getElementById('list');
              const items = (data && data.items) || [];
              if (!items.length) { box.innerHTML = '<div>0</div>'; return; }
              box.innerHTML = items.map(it =>
                `<div class="row"><span>${'$'}{it.name}</span><button class="icon-btn" title="Supprimer" aria-label="Supprimer" onclick="deleteImage('${'$'}{it.name}')"><svg viewBox="0 0 24 24"><path d="M6 7h12l-1 13H7L6 7zm3-3h6l1 2H8l1-2z"/></svg></button></div>`
              ).join('');
            }
            async function saveImage(){ await api('/api/image/save', { method:'POST' }); await refreshStatus(); await refreshList(); }
            async function clearImages(){ await api('/api/image/clear', { method:'POST' }); await refreshStatus(); await refreshList(); }
            async function deleteImage(name){ await api('/api/image/delete?name=' + encodeURIComponent(name), { method:'POST' }); await refreshStatus(); await refreshList(); }
            refreshStatus();
            refreshList();
            setInterval(refreshStatus, 1000);
            </script>
            </body>
            </html>
        """.trimIndent()
        sendText(socket, 200, "text/html; charset=utf-8", html)
    }

    private fun status(socket: Socket) {
        val latest = frameStore.latest()
        val uptime = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0)
        val streamClients = activeStreamClients.coerceAtLeast(0)
        val json = """
            {
              "port":$port,
              "clients":${clients.size},
              "streamClients":$streamClients,
              "fps":$fpsEstimate,
              "uptimeSec":$uptime,
              "latestFrameBytes":${latest?.size ?: 0},
              "savedCount":${imageManagementService.count()},
              "totalFrames":$totalFramesSent,
              "totalBytes":$totalBytesSent
            }
        """.trimIndent()
        sendText(socket, 200, "application/json; charset=utf-8", json)
    }

    private fun saveImage(socket: Socket) {
        val saved = imageManagementService.saveLatest(frameStore)
        if (saved == null) {
            sendText(socket, 503, "application/json; charset=utf-8", "{\"ok\":false,\"code\":503}")
            return
        }
        val json = "{\"ok\":true,\"name\":\"${escapeJson(saved.name)}\",\"size\":${saved.sizeBytes}}"
        sendText(socket, 200, "application/json; charset=utf-8", json)
    }

    private fun listImages(socket: Socket) {
        val items = imageManagementService.list()
            .joinToString(",") { "{\"name\":\"${escapeJson(it.name)}\",\"size\":${it.sizeBytes},\"modifiedAt\":${it.modifiedAt}}" }
        sendText(socket, 200, "application/json; charset=utf-8", "{\"ok\":true,\"items\":[${items}]}")
    }

    private fun deleteImage(socket: Socket, name: String?) {
        if (name.isNullOrBlank()) {
            sendText(socket, 400, "application/json; charset=utf-8", "{\"ok\":false,\"code\":400}")
            return
        }
        val ok = imageManagementService.delete(name)
        val code = if (ok) 200 else 404
        sendText(socket, code, "application/json; charset=utf-8", "{\"ok\":$ok}")
    }

    private fun clearImages(socket: Socket) {
        val deleted = imageManagementService.clear()
        sendText(socket, 200, "application/json; charset=utf-8", "{\"ok\":true,\"deleted\":$deleted}")
    }

    private fun snapshot(socket: Socket) {
        val frame = frameStore.latest()
        BufferedOutputStream(socket.getOutputStream()).also { out ->
            if (frame == null) {
                out.write(
                    ("HTTP/1.1 503 Service Unavailable\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                )
                out.flush()
                return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
            )
            out.write(frame)
            out.flush()
        }
    }

    private fun stream(socket: Socket) {
        val boundary = "mjpegframe"
        val out = BufferedOutputStream(socket.getOutputStream())
        synchronized(metricsLock) { activeStreamClients += 1 }
        try {
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
            )
            out.flush()

            var sequence = 0L
            while (!socket.isClosed) {
                val next = frameStore.awaitNext(sequence, 3_000) ?: continue
                sequence = next.first
                val frame = next.second
                registerFrameSent(frame.size)
                out.write(
                    ("--$boundary\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                )
                out.write(frame)
                out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            }
        } catch (_: Exception) {
            // Client déconnecté ou serveur arrêté
        } finally {
            synchronized(metricsLock) {
                activeStreamClients = (activeStreamClients - 1).coerceAtLeast(0)
            }
        }
    }

    private fun registerFrameSent(size: Int) {
        synchronized(metricsLock) {
            totalFramesSent += 1
            totalBytesSent += size.toLong()
            fpsWindowFrames += 1
            val now = System.currentTimeMillis()
            val elapsed = now - fpsWindowStartMs
            if (elapsed >= 1000L) {
                fpsEstimate = ((fpsWindowFrames * 1000L) / elapsed).toInt()
                fpsWindowFrames = 0
                fpsWindowStartMs = now
            }
        }
    }

    private companion object {
        fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&")
                .mapNotNull {
                    val key = it.substringBefore("=", "")
                    if (key.isBlank()) return@mapNotNull null
                    val value = it.substringAfter("=", "")
                    URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
                }
                .toMap()
        }

        fun escapeJson(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        }

        fun sendText(socket: Socket, code: Int, contentType: String, body: String) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            val statusText = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                503 -> "Service Unavailable"
                else -> "OK"
            }
            BufferedOutputStream(socket.getOutputStream()).also { out ->
                out.write(
                    ("HTTP/1.1 $code $statusText\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                )
                out.write(payload)
                out.flush()
            }
        }

        fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
