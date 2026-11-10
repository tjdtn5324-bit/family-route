package ai.familyroute.demo.phone

import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.FamilyJson
import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 같은 Wi-Fi의 가족 폰 브라우저에 결정 결과를 실시간으로 보내는 작은 HTTP 서버.
 * - GET  /?k=코드          폰 화면(assets/phone/index.html)
 * - GET  /events?k=코드    Server-Sent Events: state(현재 요약), msg(새 소식)
 * - POST /api/event?k=코드 FamilyEvent JSON(가족 폰에서 제안·투표·급한 요청)
 * 접속 코드가 맞아야 응답한다. 외부 인터넷은 쓰지 않는다.
 */
class PhoneLinkServer(
    private val assets: AssetManager,
    private val scope: CoroutineScope,
    val port: Int = 8765,
    val code: String,
    private val onEvent: (FamilyEvent) -> Unit,
    private val snapshot: () -> String,
) {
    private class Client(val out: OutputStream)

    private val clients = CopyOnWriteArrayList<Client>()

    /** 보내기는 백그라운드 스레드 하나에서만(Android는 메인 스레드 네트워크 쓰기를 막는다) */
    private val outbox = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _connected = MutableStateFlow(0)
    val connected: StateFlow<Int> = _connected.asStateFlow()
    private var server: ServerSocket? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            for (bytes in outbox) {
                for (c in clients) {
                    val ok = runCatching { synchronized(c) { c.out.write(bytes); c.out.flush() } }.isSuccess
                    if (!ok) remove(c)
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                ServerSocket(port).use { ss ->
                    server = ss
                    while (isActive) {
                        val socket = ss.accept()
                        launch(Dispatchers.IO) { runCatching { handle(socket) } }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
    }

    fun broadcast(event: String, json: String) {
        if (clients.isEmpty()) return
        outbox.trySend("event: $event\ndata: $json\n\n".toByteArray())
    }

    private fun remove(c: Client) {
        if (clients.remove(c)) _connected.value = clients.size
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 0
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return socket.close()
        val parts = requestLine.split(" ")
        if (parts.size < 2) return socket.close()
        val method = parts[0]
        val target = parts[1]
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        val path = target.substringBefore("?")
        val query = target.substringAfter("?", "").split("&").filter { it.contains("=") }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val out = socket.getOutputStream()
        if (query["k"] != code) return respond(out, socket, 403, "text/plain", "접속 코드가 맞지 않아요".toByteArray())

        when {
            method == "GET" && (path == "/" || path == "/index.html") -> {
                val html = assets.open("phone/index.html").use { it.readBytes() }
                respond(out, socket, 200, "text/html; charset=utf-8", html)
            }
            method == "GET" && path == "/events" -> {
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n").toByteArray(),
                )
                val client = Client(out)
                synchronized(client) { out.write("event: state\ndata: ${snapshot()}\n\n".toByteArray()); out.flush() }
                clients += client
                _connected.value = clients.size
                // 연결이 끊길 때까지 15초마다 신호를 보낸다
                while (scope.isActive) {
                    Thread.sleep(15_000)
                    val ok = runCatching { synchronized(client) { out.write(": ping\n\n".toByteArray()); out.flush() } }.isSuccess
                    if (!ok) break
                }
                remove(client)
                socket.close()
            }
            method == "POST" && path == "/api/event" -> {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val event = runCatching { FamilyJson.decodeFromString(FamilyEvent.serializer(), String(buf, 0, read)) }.getOrNull()
                if (event == null) {
                    respond(out, socket, 400, "text/plain", "잘못된 이벤트".toByteArray())
                } else {
                    onEvent(event)
                    respond(out, socket, 204, "text/plain", ByteArray(0))
                }
            }
            else -> respond(out, socket, 404, "text/plain", "없음".toByteArray())
        }
    }

    private fun respond(out: OutputStream, socket: Socket, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found"
        }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
        socket.close()
    }

    fun close() {
        outbox.close()
    }

    /** 이 기기의 사설 IPv4 주소(없으면 null) */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
