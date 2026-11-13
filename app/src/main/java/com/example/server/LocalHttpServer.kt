package com.example.server

import android.util.Log
import com.example.state.MediaBridgeStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native concurrent HTTP + SSE server running on 127.0.0.1:44444.
 * Uses cached thread pool so long-lived SSE /events connections never block
 * /now-playing or /artwork requests.
 */
object LocalHttpServer {

    private const val TAG = "LocalHttpServer"
    const val PORT = 44444
    const val HOST = "127.0.0.1"
    private const val SSE_KEEPALIVE_MS = 15000L

    private val isRunningFlag = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var clientExecutor: ExecutorService? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val sseClientCounter = AtomicInteger(0)
    private val _activeSseClients = MutableStateFlow(0)
    val activeSseClients: StateFlow<Int> = _activeSseClients.asStateFlow()

    @Synchronized
    fun start() {
        if (isRunningFlag.get()) {
            Log.d(TAG, "Server already running on $HOST:$PORT")
            return
        }

        try {
            val bindAddress = InetAddress.getByName(HOST)
            serverSocket = ServerSocket(PORT, 50, bindAddress).apply {
                reuseAddress = true
            }
            clientExecutor = Executors.newCachedThreadPool()
            isRunningFlag.set(true)
            _isRunning.value = true
            _lastError.value = null

            serverThread = Thread({ runServerLoop() }, "ClawdBridgeHttpDaemon").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Clawd Bridge HTTP/SSE daemon started on http://$HOST:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server: ${e.message}", e)
            _lastError.value = e.message
            isRunningFlag.set(false)
            _isRunning.value = false
        }
    }

    @Synchronized
    fun stop() {
        isRunningFlag.set(false)
        _isRunning.value = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
        clientExecutor?.shutdownNow()
        clientExecutor = null
        sseClientCounter.set(0)
        _activeSseClients.value = 0
        Log.i(TAG, "Clawd Bridge HTTP/SSE daemon stopped")
    }

    private fun runServerLoop() {
        while (isRunningFlag.get()) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                val exec = clientExecutor
                if (exec != null && !exec.isShutdown) {
                    exec.execute { handleClientConnection(clientSocket) }
                } else {
                    handleClientConnection(clientSocket)
                }
            } catch (e: Exception) {
                if (!isRunningFlag.get()) {
                    break
                }
                Log.w(TAG, "Exception in client accept loop: ${e.message}")
            }
        }
        _isRunning.value = false
    }

    private fun handleClientConnection(socket: Socket) {
        try {
            socket.soTimeout = 3000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = if (parts.isNotEmpty()) parts[0] else "GET"
            val rawPath = if (parts.size > 1) parts[1] else "/"
            val path = rawPath.substringBefore("?")

            var line: String? = input.readLine()
            while (!line.isNullOrEmpty()) {
                line = input.readLine()
            }

            when {
                method.equals("OPTIONS", ignoreCase = true) -> sendOptionsResponse(output)
                path == "/now-playing" || path == "/" -> sendNowPlayingResponse(output)
                path == "/artwork" -> sendArtworkResponse(output)
                path == "/events" -> handleSseStream(socket, output)
                else -> sendNotFoundResponse(output, path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client request: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun sendNowPlayingResponse(output: OutputStream) {
        MediaBridgeStateHolder.recordHttpRequest()
        val data = MediaBridgeStateHolder.getCurrentSnapshot()
        val jsonPayload = data.toJsonString()
        val bodyBytes = jsonPayload.toByteArray(Charsets.UTF_8)

        val header = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            .append("Connection: close\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            .append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            .append("\r\n")

        output.write(header.toString().toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun sendArtworkResponse(output: OutputStream) {
        val bytes = MediaBridgeStateHolder.getCurrentArtworkBytes()
        if (bytes == null || bytes.isEmpty()) {
            val header = StringBuilder()
                .append("HTTP/1.1 204 No Content\r\n")
                .append("Connection: close\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                .append("\r\n")
            output.write(header.toString().toByteArray(Charsets.US_ASCII))
            output.flush()
            return
        }

        val header = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: image/jpeg\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Connection: close\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            .append("\r\n")

        output.write(header.toString().toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun handleSseStream(socket: Socket, output: OutputStream) {
        val count = sseClientCounter.incrementAndGet()
        _activeSseClients.value = count
        try {
            socket.soTimeout = 0
            val header = StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: text/event-stream; charset=utf-8\r\n")
                .append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                .append("Connection: keep-alive\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("\r\n")
            output.write(header.toString().toByteArray(Charsets.US_ASCII))
            output.flush()

            // Immediately push current live state upon connection
            val initialJson = MediaBridgeStateHolder.getCurrentSnapshot().toJsonString()
            writeSseEvent(output, "now_playing", initialJson)

            runBlocking {
                while (isRunningFlag.get() && !socket.isClosed) {
                    val timedOut = withTimeoutOrNull(SSE_KEEPALIVE_MS) {
                        MediaBridgeStateHolder.stateEvents.collect { eventData ->
                            if (!isRunningFlag.get() || socket.isClosed) return@collect
                            writeSseEvent(output, "now_playing", eventData.toJsonString())
                        }
                    }
                    if (timedOut == null && isRunningFlag.get() && !socket.isClosed) {
                        output.write(": keepalive\n\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
            }
        } catch (_: Exception) {
            // Client disconnected normally
        } finally {
            _activeSseClients.value = sseClientCounter.decrementAndGet().coerceAtLeast(0)
        }
    }

    internal fun formatSseFrame(eventName: String, jsonData: String): String {
        return "event: $eventName\ndata: $jsonData\n\n"
    }

    private fun writeSseEvent(output: OutputStream, eventName: String, jsonData: String) {
        val frame = formatSseFrame(eventName, jsonData)
        output.write(frame.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendOptionsResponse(output: OutputStream) {
        val header = StringBuilder()
            .append("HTTP/1.1 204 No Content\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            .append("Access-Control-Allow-Headers: *\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
        output.write(header.toString().toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun sendNotFoundResponse(output: OutputStream, path: String) {
        val safePath = path.replace("\\", "\\\\").replace("\"", "\\\"")
        val message = """{"error":"Not Found","path":"$safePath"}"""
        val bodyBytes = message.toByteArray(Charsets.UTF_8)
        val header = StringBuilder()
            .append("HTTP/1.1 404 Not Found\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            .append("Connection: close\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("\r\n")
        output.write(header.toString().toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }
}
