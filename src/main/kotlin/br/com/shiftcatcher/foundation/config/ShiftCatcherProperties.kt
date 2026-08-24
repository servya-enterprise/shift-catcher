package br.com.shiftcatcher.foundation.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("shift-catcher")
data class ShiftCatcherProperties(
    val security: Security = Security(),
    val poc: Poc = Poc(),
    val greenApi: GreenApi = GreenApi(),
    val detection: Detection = Detection(),
    val ai: Ai = Ai(),
) {
    data class Security(
        val adminApiToken: String = "",
    )

    data class Poc(
        val stage: String = "TRANSPORT",
    )

    data class Detection(
        val knownLocations: List<String> = emptyList(),
        val knownCities: List<String> = emptyList(),
        val timezone: java.time.ZoneId = java.time.ZoneId.of("America/Sao_Paulo"),
    )

    /**
     * The AI parser is opt-in and stays disabled until a real adapter exists. `DEC-004` forbids
     * calling a model for every message, and `07-AI/Fallback-Policy.md` requires an enabled adapter
     * before the fallback may run at all.
     */
    data class Ai(
        val enabled: Boolean = false,
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
