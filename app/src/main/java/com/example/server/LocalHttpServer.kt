package com.example.server

import android.util.Log
import com.example.state.MediaBridgeStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native in-memory HTTP server running on a daemon thread.
 * Listens strictly on 127.0.0.1:44444 for GET /now-playing.
 * Operates purely RAM-to-RAM with zero disk I/O and 0.0% idle CPU usage.
 */
object LocalHttpServer {

    private const val TAG = "LocalHttpServer"
    const val PORT = 44444
    const val HOST = "127.0.0.1"

    private val isRunningFlag = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
            isRunningFlag.set(true)
            _isRunning.value = true
            _lastError.value = null

            serverThread = Thread({ runServerLoop() }, "ClawdBridgeHttpDaemon").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Clawd Bridge HTTP daemon started on http://$HOST:$PORT/now-playing")
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
        Log.i(TAG, "Clawd Bridge HTTP daemon stopped")
    }

    private fun runServerLoop() {
        while (isRunningFlag.get()) {
            try {
                // Blocks here with 0.0% CPU when no requests are pending
                val clientSocket = serverSocket?.accept() ?: break
                handleClientConnection(clientSocket)
            } catch (e: Exception) {
                if (!isRunningFlag.get()) {
                    break // Normal server shutdown
                }
                Log.w(TAG, "Exception in client accept loop: ${e.message}")
            }
        }
        _isRunning.value = false
    }

    private fun handleClientConnection(socket: Socket) {
        // Handle request synchronously in RAM and close immediately
        try {
            socket.soTimeout = 3000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = if (parts.isNotEmpty()) parts[0] else "GET"
            val rawPath = if (parts.size > 1) parts[1] else "/"
            val path = rawPath.substringBefore("?")

            // Read remaining headers until blank line
            var line: String? = input.readLine()
            while (!line.isNullOrEmpty()) {
                line = input.readLine()
            }

            if (method.equals("OPTIONS", ignoreCase = true)) {
                sendOptionsResponse(output)
            } else if (path == "/now-playing" || path == "/") {
                sendNowPlayingResponse(output)
            } else {
                sendNotFoundResponse(output, path)
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
