package br.com.shiftcatcher.integration.greenapi

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Clock

@Component
class GreenApiHttpAdapter(
    private val properties: ShiftCatcherProperties,
    private val clock: Clock = Clock.systemUTC(),
) : WhatsAppInstanceHealth,
    WhatsAppMessageSender {
    private val restClient: RestClient by lazy {
        val requestFactory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(properties.greenApi.connectTimeout)
                setReadTimeout(properties.greenApi.readTimeout)
            }
        RestClient.builder().requestFactory(requestFactory).build()
    }

    override fun getState(): GreenApiInstanceHealth {
        val payload: StatePayload =
            execute("state") {
                restClient
                    .get()
                    .uri(endpoint("getStateInstance"))
                    .retrieve()
                    .body(StatePayload::class.java)
                    ?: throw GreenApiTransportException(
                        GreenApiFailureKind.INVALID_RESPONSE,
                        "GREEN-API state response was empty",
                    )
            }
        val rawState =
            payload.stateInstance?.takeIf { it.isNotBlank() }
                ?: throw GreenApiTransportException(
                    GreenApiFailureKind.INVALID_RESPONSE,
                    "GREEN-API state response was invalid",
                )
        return GreenApiInstanceHealth(
            state = GreenApiInstanceState.fromProvider(rawState),
            rawState = rawState,
            observedAt = clock.instant(),
        )
    }

    override fun sendQuotedMessage(command: SendQuotedMessage): ProviderSendReceipt {
        val payload: SendPayload =
            execute("send") {
                restClient
                    .post()
                    .uri(endpoint("sendMessage"))
                    .body(
                        SendRequest(
                            chatId = command.chatId,
                            message = command.message,
                            quotedMessageId = command.quotedMessageId,
                        ),
                    ).retrieve()
                    .body(SendPayload::class.java)
                    ?: throw GreenApiTransportException(
                        GreenApiFailureKind.INVALID_RESPONSE,
                        "GREEN-API send response was empty",
                    )
            }
        val providerMessageId =
            payload.idMessage?.takeIf { it.isNotBlank() }
                ?: throw GreenApiTransportException(
                    GreenApiFailureKind.INVALID_RESPONSE,
                    "GREEN-API send response did not contain idMessage",
                )
        return ProviderSendReceipt(providerMessageId = providerMessageId, acceptedAt = clock.instant())
    }

    private fun endpoint(method: String): URI {
        val config = properties.greenApi
        if (!config.isProviderConfigured()) {
            throw GreenApiNotConfiguredException()
        }
        require(config.instanceId.matches(INSTANCE_ID_PATTERN)) { "GREEN-API instance ID is invalid" }
        require(config.apiToken.matches(TOKEN_PATTERN)) { "GREEN-API API token format is invalid" }

        val base =
            runCatching { URI.create(config.apiUrl) }
                .getOrElse { throw IllegalArgumentException("GREEN-API API URL is invalid") }
        val secure = base.scheme.equals("https", ignoreCase = true)
        val localTest = config.allowInsecureHttp && base.scheme.equals("http", ignoreCase = true)
        require(secure || localTest) { "GREEN-API API URL must use HTTPS" }
        require(base.host != null && base.userInfo == null && base.query == null && base.fragment == null) {
            "GREEN-API API URL is invalid"
        }

        return UriComponentsBuilder
            .fromUri(base)
            .pathSegment("waInstance${config.instanceId}", method, config.apiToken)
            .build()
            .encode()
            .toUri()
    }

    private fun <T> execute(
        operation: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (exception: GreenApiTransportException) {
            throw exception
        } catch (exception: RestClientResponseException) {
            val kind =
                if (exception.statusCode.is5xxServerError) {
                    GreenApiFailureKind.SERVER_ERROR
                } else {
                    GreenApiFailureKind.CLIENT_ERROR
                }
            throw GreenApiTransportException(kind, "GREEN-API $operation request was rejected")
        } catch (exception: ResourceAccessException) {
            throw GreenApiTransportException(GreenApiFailureKind.TIMEOUT, "GREEN-API $operation request timed out")
        } catch (exception: RestClientException) {
            throw GreenApiTransportException(
                GreenApiFailureKind.INVALID_RESPONSE,
                "GREEN-API $operation response was invalid",
            )
        }

    private data class StatePayload(
        val stateInstance: String? = null,
    )

    private data class SendRequest(
        val chatId: String,
        val message: String,
        val quotedMessageId: String,
    )

    private data class SendPayload(
        val idMessage: String? = null,
    )

    private companion object {
        val INSTANCE_ID_PATTERN = Regex("^[0-9]{1,32}$")
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9._~-]{8,256}$")
    }
}
