package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GreenApiHttpAdapterTest {
    private lateinit var fake: FakeGreenApiServer

    @BeforeEach
    fun setUp() {
        fake = FakeGreenApiServer()
    }

    @AfterEach
    fun tearDown() {
        fake.close()
    }

    @Test
    fun `normalizes authorized provider state`() {
        val result = adapter().getState()

        assertEquals(GreenApiInstanceState.AUTHORIZED, result.state)
        assertTrue(result.operational)
        assertEquals(1, fake.stateCalls.get())
    }

    @Test
    fun `sends exact quoted message and reads provider id`() {
        val result =
            adapter().sendQuotedMessage(
                SendQuotedMessage("120363000000000000@g.us", "PEGO", "incoming-message-id"),
            )

        assertEquals("provider-out-1", result.providerMessageId)
        assertEquals(1, fake.sendCalls.get())
        val request = fake.requests.single()
        assertTrue(request.path.endsWith("/waInstance123456/sendMessage/test-api-token-123"))
        assertTrue(request.body.contains("\"chatId\":\"120363000000000000@g.us\""))
        assertTrue(request.body.contains("\"message\":\"PEGO\""))
        assertTrue(request.body.contains("\"quotedMessageId\":\"incoming-message-id\""))
    }

    @Test
    fun `classifies provider 4xx without exposing credential`() {
        fake.respondToState(status = 401, body = "{}")

        val failure = assertFailsWith<GreenApiTransportException> { adapter().getState() }

        assertEquals(GreenApiFailureKind.CLIENT_ERROR, failure.kind)
        assertFalse(failure.message.orEmpty().contains("test-api-token"))
    }

    @Test
    fun `classifies provider 5xx`() {
        fake.respondToState(status = 503, body = "{}")

        val failure = assertFailsWith<GreenApiTransportException> { adapter().getState() }

        assertEquals(GreenApiFailureKind.SERVER_ERROR, failure.kind)
    }

    @Test
    fun `classifies timeout`() {
        fake.respondToState(body = """{"stateInstance":"authorized"}""", delay = Duration.ofMillis(300))

        val failure =
            assertFailsWith<GreenApiTransportException> {
                adapter(readTimeout = Duration.ofMillis(50)).getState()
            }

        assertEquals(GreenApiFailureKind.TIMEOUT, failure.kind)
    }

    @Test
    fun `rejects invalid provider JSON`() {
        fake.respondToState(body = "not-json")

        val failure = assertFailsWith<GreenApiTransportException> { adapter().getState() }

        assertEquals(GreenApiFailureKind.INVALID_RESPONSE, failure.kind)
    }

    private fun adapter(readTimeout: Duration = Duration.ofSeconds(1)): GreenApiHttpAdapter =
        GreenApiHttpAdapter(
            ShiftCatcherProperties(
                greenApi =
                    ShiftCatcherProperties.GreenApi(
                        apiUrl = fake.baseUrl,
                        instanceId = "123456",
                        apiToken = "test-api-token-123",
                        webhookToken = "test-webhook-token",
                        connectTimeout = Duration.ofSeconds(1),
                        readTimeout = readTimeout,
                        allowInsecureHttp = true,
                    ),
            ),
        )
}
