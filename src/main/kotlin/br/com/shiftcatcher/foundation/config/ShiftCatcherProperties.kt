package br.com.shiftcatcher.foundation.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("shift-catcher")
data class ShiftCatcherProperties(
    val security: Security = Security(),
    val poc: Poc = Poc(),
    val greenApi: GreenApi = GreenApi(),
) {
    data class Security(
        val adminApiToken: String = "",
    )

    data class Poc(
        val stage: String = "TRANSPORT",
    )

    data class GreenApi(
        val apiUrl: String = "",
        val instanceId: String = "",
        val apiToken: String = "",
        val webhookToken: String = "",
        val connectTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
        val readTimeout: java.time.Duration = java.time.Duration.ofSeconds(5),
        val allowInsecureHttp: Boolean = false,
    ) {
        fun isProviderConfigured(): Boolean = apiUrl.isNotBlank() && instanceId.isNotBlank() && apiToken.isNotBlank()
    }
}
