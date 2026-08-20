package br.com.shiftcatcher.integration.greenapi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FakeGreenApiServer : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val stateResponse = AtomicReference(FakeResponse(200, """{"stateInstance":"authorized"}"""))
    private val sendResponse = AtomicReference(FakeResponse(200, """{"idMessage":"provider-out-1"}"""))

    val stateCalls = AtomicInteger()
    val sendCalls = AtomicInteger()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/", ::handle)
        server.start()
    }

    fun reset() {
        stateResponse.set(FakeResponse(200, """{"stateInstance":"authorized"}"""))
        sendResponse.set(FakeResponse(200, """{"idMessage":"provider-out-1"}"""))
        stateCalls.set(0)
        sendCalls.set(0)
        requests.clear()
    }

    fun respondToState(
        status: Int = 200,
        body: String,
        delay: Duration = Duration.ZERO,
    ) {
        stateResponse.set(FakeResponse(status, body, delay))
    }

    fun respondToSend(
        status: Int = 200,
        body: String,
        delay: Duration = Duration.ZERO,
    ) {
        sendResponse.set(FakeResponse(status, body, delay))
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
        requests += RecordedRequest(exchange.requestMethod, exchange.requestURI.path, body)
        val response =
            when {
                exchange.requestURI.path.contains("/getStateInstance/") -> {
                    stateCalls.incrementAndGet()
                    stateResponse.get()
                }

                exchange.requestURI.path.contains("/sendMessage/") -> {
                    sendCalls.incrementAndGet()
                    sendResponse.get()
                }

                else -> {
                    FakeResponse(404, "{}")
                }
            }
        if (!response.delay.isZero) {
            Thread.sleep(response.delay.toMillis())
        }
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

data class FakeResponse(
    val status: Int,
    val body: String,
    val delay: Duration = Duration.ZERO,
)

data class RecordedRequest(
    val method: String,
    val path: String,
    val body: String,
)
