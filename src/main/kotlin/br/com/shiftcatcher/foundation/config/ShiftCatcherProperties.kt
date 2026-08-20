package br.com.shiftcatcher.foundation.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("shift-catcher")
data class ShiftCatcherProperties(
    val security: Security = Security(),
    val poc: Poc = Poc(),
) {
    data class Security(
        val adminApiToken: String = "",
    )

    data class Poc(
        val stage: String = "TRANSPORT",
    )
}
