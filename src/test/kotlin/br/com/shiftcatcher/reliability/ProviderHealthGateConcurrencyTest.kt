package br.com.shiftcatcher.reliability

import br.com.shiftcatcher.foundation.config.ShiftCatcherProperties
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceHealth
import br.com.shiftcatcher.integration.greenapi.GreenApiInstanceState
import br.com.shiftcatcher.integration.greenapi.WhatsAppInstanceHealth
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The schedulers now get a thread each, so jobs that used to take turns can overlap.
 *
 * The one place that matters is here: refreshing reads the stored observation, calls a rate-limited
 * provider and writes the result back. This asserts the provider is asked once even when several
 * threads want an answer at the same moment, and that the threads which did not get to ask are given
 * an answer that blocks rather than one that permits.
 */
class ProviderHealthGateConcurrencyTest {
    @Test
    fun `several threads wanting an answer ask the provider once`() {
        val provider = CountingHealth()
        val repository = InMemoryHealth()
        val gate = ProviderHealthGate(repository, provider, ShiftCatcherProperties(), Clock.systemUTC())

        // The provider is made slow on purpose. The guard collapses calls that genuinely overlap;
        // against an instant provider there is nothing to collapse, and asserting otherwise would be
        // asserting a property nobody has.
        provider.onCall = { Thread.sleep(OVERLAP_MS) }

        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val results =
            (1..threads).map {
                pool.submit<ProviderHealthObservation> {
                    ready.countDown()
                    go.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                    gate.refresh()
                }
            }
        ready.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        go.countDown()
        val observations = results.map { it.get(AWAIT_SECONDS, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, provider.calls.get(), "one live call, not one per thread")
        // Nobody was made to wait behind the network call: that is what would stall a claim.
        assertEquals(threads, observations.size)
    }

    @Test
    fun `a thread that could not ask is never told it may act`() {
        val provider = CountingHealth()
        val repository = InMemoryHealth()
        val gate = ProviderHealthGate(repository, provider, ShiftCatcherProperties(), Clock.systemUTC())

        // Nothing stored yet and a refresh already under way: the honest answer is "I do not know",
        // and not knowing is never permission.
        val blocked = CountDownLatch(1)
        val entered = CountDownLatch(1)
        provider.onCall = {
            entered.countDown()
            blocked.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        }
        val pool = Executors.newFixedThreadPool(2)
        val slow = pool.submit { gate.refresh() }
        entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)

        val whileBusy = gate.refresh()
        assertFalse(whileBusy.operational, "an unknown provider state must block, never allow")
        assertEquals("UNKNOWN", whileBusy.state)

        blocked.countDown()
        slow.get(AWAIT_SECONDS, TimeUnit.SECONDS)
        pool.shutdownNow()
        assertEquals(1, provider.calls.get())
    }

    private class CountingHealth : WhatsAppInstanceHealth {
        val calls = AtomicInteger()

        @Volatile
        var onCall: (() -> Unit)? = null

        override fun getState(): GreenApiInstanceHealth {
            calls.incrementAndGet()
            onCall?.invoke()
            return GreenApiInstanceHealth(
                state = GreenApiInstanceState.AUTHORIZED,
                rawState = GreenApiInstanceState.AUTHORIZED.name,
                observedAt = Instant.now(),
            )
        }
    }

    /** Stands in for the single-row table, without pretending to be a database. */
    private class InMemoryHealth : ProviderHealthRepository(mock(JdbcTemplate::class.java)) {
        @Volatile
        private var stored: ProviderHealthObservation? = null

        override fun record(observation: ProviderHealthObservation) {
            stored = observation
        }

        override fun latest(): ProviderHealthObservation? = stored
    }

    private companion object {
        const val AWAIT_SECONDS = 10L
        const val OVERLAP_MS = 300L
    }
}
