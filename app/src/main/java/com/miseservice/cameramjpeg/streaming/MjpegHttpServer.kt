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
                    path.startsWith("/stream.mjpeg") -> stream(socket)
                    path.startsWith("/snapshot.jpg") -> snapshot(socket)
                    path == "/api/status" -> status(socket)
                    path == "/api/image/save" && (method == "POST" || method == "GET") -> saveImage(socket)
                    path == "/api/image/list" -> listImages(socket)
                    path == "/api/image/delete" && (method == "POST" || method == "GET") -> deleteImage(socket, query["name"])
                    path == "/api/image/clear" && (method == "POST" || method == "GET") -> clearImages(socket)
                    else -> viewer(socket)
                }
            }
        } finally {
            clients.remove(client)
        }
    }

    private fun viewer(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>Viewer</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
            body{background:#0f1115;color:#d8deea;font-family:Arial;margin:0;padding:14px}
            .grid{display:grid;grid-template-columns:repeat(4,minmax(70px,1fr));gap:8px;margin-bottom:12px}
            .kpi{background:#171b23;border:1px solid #2a3242;border-radius:10px;padding:10px;display:flex;align-items:center;justify-content:space-between}
            .kpi svg{width:18px;height:18px;fill:#8fb8ff;opacity:.95}
            .kpi .v{font-size:18px;font-weight:700;font-variant-numeric:tabular-nums}
            img{width:100%;height:auto;border:1px solid #2a3242;border-radius:10px;display:block;background:#000}
            .controls{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}
            button{background:#1d2532;color:#d8deea;border:1px solid #324158;border-radius:8px;padding:8px 10px;cursor:pointer}
            button:hover{background:#263246}
            .list{margin-top:10px;background:#171b23;border:1px solid #2a3242;border-radius:10px;padding:10px;max-height:200px;overflow:auto}
            .row{display:flex;justify-content:space-between;gap:10px;padding:6px 0;border-bottom:1px solid #232b3a}
            .row:last-child{border-bottom:none}
            </style>
            </head>
            <body>
            <div class="grid">
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M4 5h16v14H4zM2 3v18h20V3z"/></svg><div class="v" id="port">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M12 5a7 7 0 1 0 7 7h2a9 9 0 1 1-2.64-6.36L17 7a7 7 0 0 0-5-2z"/></svg><div class="v" id="up">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M3 6h18v12H3zM1 4v16h22V4z"/></svg><div class="v" id="bytes">0</div></div>
              <div class="kpi"><svg viewBox="0 0 24 24"><path d="M12 3l8 4v6c0 5-3.4 8.7-8 9-4.6-.3-8-4-8-9V7l8-4z"/></svg><div class="v" id="saved">0</div></div>
            </div>

            <img src="/stream.mjpeg"/>

            <div class="controls">
              <button onclick="saveImage()">Enregistrer</button>
              <button onclick="refreshList()">Lister</button>
              <button onclick="clearImages()">Vider</button>
              <button onclick="window.open('/snapshot.jpg','_blank')">Snapshot</button>
            </div>

            <div id="list" class="list"></div>

            <script>
            async function api(path, opt){
              const res = await fetch(path, opt || {});
              const txt = await res.text();
              try { return JSON.parse(txt); } catch (_) { return { ok:false, raw:txt }; }
            }
            function setNum(id, value){ document.getElementById(id).textContent = String(value || 0); }
            async function refreshStatus(){
              const s = await api('/api/status');
              if (!s) return;
              setNum('port', s.port);
              setNum('up', s.uptimeSec);
              setNum('bytes', s.latestFrameBytes);
              setNum('saved', s.savedCount);
            }
            async function refreshList(){
              const data = await api('/api/image/list');
              const box = document.getElementById('list');
              const items = (data && data.items) || [];
              if (!items.length) { box.innerHTML = '<div>0</div>'; return; }
              box.innerHTML = items.map(it =>
                `<div class="row"><span>${'$'}{it.name}</span><button onclick="deleteImage('${'$'}{it.name}')">X</button></div>`
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
        val json = """
            {
              "port":$port,
              "clients":${clients.size},
              "uptimeSec":$uptime,
              "latestFrameBytes":${latest?.size ?: 0},
              "savedCount":${imageManagementService.count()}
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
