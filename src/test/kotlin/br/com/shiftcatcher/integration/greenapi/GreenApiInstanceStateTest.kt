package br.com.shiftcatcher.integration.greenapi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GreenApiInstanceStateTest {
    @Test
    fun `maps every frozen non-operational state fail safe`() {
        val expected =
            mapOf(
                "starting" to GreenApiInstanceState.STARTING,
                "sleepMode" to GreenApiInstanceState.SLEEP_MODE,
                "notAuthorized" to GreenApiInstanceState.NOT_AUTHORIZED,
                "blocked" to GreenApiInstanceState.BLOCKED,
                "suspended" to GreenApiInstanceState.SUSPENDED,
                "yellowCard" to GreenApiInstanceState.SUSPENDED,
                "futureProviderState" to GreenApiInstanceState.UNKNOWN,
            )

        expected.forEach { (raw, normalized) ->
            assertEquals(normalized, GreenApiInstanceState.fromProvider(raw))
        }
    }
}
