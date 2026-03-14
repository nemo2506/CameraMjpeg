package com.miseservice.cameramjpeg.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class MjpegHttpServer(
    private val port: Int,
    private val frameStore: FrameStore
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            serverSocket = ServerSocket(port)
            while (isActive) {
                val client = serverSocket?.accept() ?: continue
                launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            while (reader.readLine()?.isNotEmpty() == true) {
                // Skip headers.
            }

            when {
                path.startsWith("/stream.mjpeg") -> stream(socket)
                path.startsWith("/snapshot.jpg") -> snapshot(socket)
                else -> viewer(socket)
            }
        }
    }

    private fun viewer(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset=\"utf-8\"><title>Camera MJPEG</title></head>
            <body style=\"background:#111;color:#eee;font-family:Arial;\">
            <h2>Camera MJPEG</h2>
            <img src=\"/stream.mjpeg\" style=\"max-width:100%;height:auto;border:1px solid #444;\"/>
            </body>
            </html>
        """.trimIndent()

        val payload = html.toByteArray(StandardCharsets.UTF_8)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.flush()
    }

    private fun snapshot(socket: Socket) {
        val frame = frameStore.latest() ?: return
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: image/jpeg\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(frame)
        output.flush()
    }

    private fun stream(socket: Socket) {
        val boundary = "frame"
        val output = socket.getOutputStream()

        output.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Cache-Control: no-store\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Pragma: no-cache\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Connection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        var sequence = 0L
        while (!socket.isClosed) {
            val next = frameStore.awaitNext(sequence, 5_000) ?: continue
            sequence = next.first
            val frame = next.second
            output.write("--$boundary\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("Content-Type: image/jpeg\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(frame)
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }
}

