package com.lightweightbrowser

import android.content.Context
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Local-only HTTPS proxy used to strip YouTube ad data. WebView is pointed at
 * this proxy for youtube.com hosts only; every other URL stays on the normal
 * network path. The bundled CA is trusted only through the app's network
 * security config and the proxy never binds to a public interface.
 */
object YoutubeProxy {

    private const val KEYSTORE_PASSWORD = "lwlocal"

    @Volatile
    var port: Int = 0
        private set

    private var server: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var sslContext: SSLContext? = null

    fun start(context: Context): Int {
        server?.let { return port }
        return try {
            val keyStore = KeyStore.getInstance("PKCS12")
            context.assets.open("lw_mitm.p12").use { input ->
                keyStore.load(input, KEYSTORE_PASSWORD.toCharArray())
            }
            val keyManagers = KeyManagerFactory.getInstance("X509").apply {
                init(keyStore, KEYSTORE_PASSWORD.toCharArray())
            }.keyManagers
            val context = SSLContext.getInstance("TLS").apply {
                init(keyManagers, null, null)
            }
            sslContext = context

            val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            server = socket
            port = socket.localPort
            executor.execute { acceptLoop(socket) }
            port
        } catch (e: Exception) {
            0
        }
    }

    fun stop() {
        try { server?.close() } catch (e: Exception) {}
        server = null
        port = 0
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute { handleClient(client) }
            } catch (e: Exception) {
                if (!socket.isClosed) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        try {
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            if (!parts[0].equals("CONNECT", ignoreCase = true)) {
                // Only HTTPS is routed to this proxy.
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                return
            }

            val target = parts[1]
            val separator = target.lastIndexOf(':')
            val host = if (separator > 0) target.substring(0, separator) else target
            val targetPort = if (separator > 0) {
                target.substring(separator + 1).toIntOrNull() ?: 443
            } else 443

            readHeaders(input) // CONNECT headers are not needed upstream.

            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()

            val ssl = sslContext?.socketFactory?.createSocket(client, host, targetPort, true)
                as? SSLSocket ?: return
            ssl.useClientMode = false
            ssl.startHandshake()
            handleHttps(ssl, host)
        } catch (e: Exception) {
            try { client.close() } catch (ignored: Exception) {}
        }
    }

    private fun handleHttps(socket: SSLSocket, host: String) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            val headers = readHeaders(input)
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) readBytes(input, length) else null

            val connection = URL("https://$host$path").openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Host", host)
            headers.forEach { (name, value) ->
                if (name != "accept-encoding" && name != "connection" && name != "host" &&
                    name != "content-length" && name != "proxy-connection"
                ) {
                    connection.setRequestProperty(name, value)
                }
            }
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (body != null && !method.equals("GET", ignoreCase = true)) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = responseStream?.use { it.readBytes() } ?: ByteArray(0)
            val contentType = connection.contentType.orEmpty()
            val text = String(raw, Charsets.UTF_8)
            val modified = when {
                contentType.contains("json", ignoreCase = true) ->
                    AdBlocker.stripYouTubeAdsFromJson(text)
                contentType.contains("html", ignoreCase = true) ->
                    AdBlocker.stripYouTubeAdsFromHtml(text)
                else -> text
            }
            val responseBytes = modified.toByteArray(Charsets.UTF_8)

            val builder = StringBuilder()
            builder.append("HTTP/1.1 ").append(status).append(' ')
                .append(connection.responseMessage.orEmpty()).append("\r\n")
            connection.headerFields.forEach { (name, values) ->
                if (name != null && !name.equals("Content-Length", ignoreCase = true) &&
                    !name.equals("Transfer-Encoding", ignoreCase = true) &&
                    !name.equals("Content-Encoding", ignoreCase = true)
                ) {
                    values.firstOrNull()?.let { builder.append(name).append(": ").append(it).append("\r\n") }
                }
            }
            builder.append("Content-Length: ").append(responseBytes.size).append("\r\n")
            builder.append("Connection: close\r\n\r\n")
            output.write(builder.toString().toByteArray(Charsets.US_ASCII))
            output.write(responseBytes)
            output.flush()
            connection.disconnect()
        } catch (e: Exception) {
            try { socket.close() } catch (ignored: Exception) {}
        } finally {
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString("UTF-8")
    }

    private fun readHeaders(input: InputStream): MutableMap<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] =
                    line.substring(index + 1).trim()
            }
        }
        return headers
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        val buffer = ByteArray(8 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }
}
