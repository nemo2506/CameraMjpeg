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
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class MjpegHttpServer(
    private val port: Int,
    private val frameStore: FrameStore
) {
    private var scope: CoroutineScope = newScope()
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()

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
                } catch (e: Exception) {
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
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                while (reader.readLine()?.isNotEmpty() == true) { /* skip headers */ }
                socket.soTimeout = 0  // pas de timeout pendant l'écriture
                when {
                    path.startsWith("/stream.mjpeg") -> stream(socket)
                    path.startsWith("/snapshot.jpg") -> snapshot(socket)
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
            <head><meta charset="utf-8"><title>Camera MJPEG</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head>
            <body style="background:#111;color:#eee;font-family:Arial;margin:0;padding:16px">
            <h2>Camera MJPEG</h2>
            <img src="/stream.mjpeg" style="max-width:100%;height:auto;border:1px solid #444;display:block;"/>
            <p><a href="/snapshot.jpg" style="color:#8af">Snapshot</a></p>
            </body>
            </html>
        """.trimIndent()
        val payload = html.toByteArray(StandardCharsets.UTF_8)
        BufferedOutputStream(socket.getOutputStream()).also { out ->
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
            )
            out.write(payload)
            out.flush()
        }
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
        fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
