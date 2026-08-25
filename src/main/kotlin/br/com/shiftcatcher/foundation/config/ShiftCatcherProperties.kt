package br.com.shiftcatcher.foundation.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("shift-catcher")
data class ShiftCatcherProperties(
    val security: Security = Security(),
    val poc: Poc = Poc(),
    val greenApi: GreenApi = GreenApi(),
    val detection: Detection = Detection(),
    val ai: Ai = Ai(),
    val claim: Claim = Claim(),
    val retention: Retention = Retention(),
) {
    /**
     * How long things are kept. Message content is redacted in place rather than deleted; audit,
     * spent outbox intents and old benchmark reports are deleted outright.
     *
     * [dryRun] defaults to true and that is deliberate. This is the only code here that destroys
     * data, so it reports what it would do until somebody has read the numbers and armed it.
     *
     * [messageContent] is the longest period on purpose: the message log is where the real corpus
     * for `WP-POC-008` comes from, and shortening this destroys corpus material that cannot be
     * recovered.
     */
    data class Retention(
        val enabled: Boolean = true,
        val dryRun: Boolean = true,
        val messageContent: java.time.Duration = java.time.Duration.ofDays(180),
        val auditTrail: java.time.Duration = java.time.Duration.ofDays(90),
        val spentOutbox: java.time.Duration = java.time.Duration.ofDays(90),
        val benchmarkReports: java.time.Duration = java.time.Duration.ofDays(90),
    )

    /**
     * The retry budget is the one fixed in `02-Architecture/Transactionality-and-Idempotency.md`.
     * It is deliberately short: a shift offer that took three seconds to answer is already gone.
     */
    data class Claim(
        val retryDelaysMs: List<Long> = listOf(0, 150, 400, 800, 1500),
        val workerEnabled: Boolean = true,
        val leaseSeconds: Long = 60,
        /** A provider observation older than this is treated as no answer, which blocks auto-claim. */
        val healthFreshnessSeconds: Long = 90,
        /** How long a good observation is trusted before it is refreshed. */
        val healthRefreshSuccessSeconds: Long = 60,
        /** After a failed observation, retry this soon: a blip must not block claims for a minute. */
        val healthRefreshFailureSeconds: Long = 5,
        /** Auto-claim stays off until the operator turns it on here *and* in the active rule set. */
        val autoClaimEnabled: Boolean = false,
        /**
         * Automatic evaluation only computes verdicts, never sends, so it is on by default: without
         * it an opportunity would sit in `EVALUATING` forever.
         */
        val autoEvaluateEnabled: Boolean = true,
    )

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
        /** Ollama base URL, e.g. `http://garimpo-zap-ollama-1:11434`. Empty means no adapter. */
        val baseUrl: String = "",
        val model: String = "qwen2.5:3b",
        /**
         * Hard ceiling on a single inference. The model answers in seconds on this hardware, but a
         * cold load takes far longer, and nothing in the pipeline may wait indefinitely.
         */
        val timeout: java.time.Duration = java.time.Duration.ofSeconds(30),
        /** Identical texts are common in these groups; re-inferring them costs seconds for nothing. */
        val cacheEnabled: Boolean = true,
    ) {
        fun isConfigured(): Boolean = enabled && baseUrl.isNotBlank()
    }

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
