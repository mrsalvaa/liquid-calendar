package dev.liquidcalendar.core.sync

import dev.liquidcalendar.core.dav.DavException
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {
    // Sin variación aleatoria: nextDouble() = 0,5 → factor 1.
    private val policy = RetryPolicy(random = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = 0.5
    })

    @Test
    fun unauthorizedStopsUntilThePasswordChanges() {
        assertEquals(RetryDecision.NeedsPassword, policy.decide(DavException.Unauthorized(), 0))
        assertEquals(RetryDecision.NeedsPassword, policy.decide(DavException.Unauthorized(), 5))
    }

    @Test
    fun retryAfterWins() {
        val d = policy.decide(DavException.RetryLater(429, Duration.ofMinutes(7)), 3)
        assertEquals(RetryDecision.RetryIn(Duration.ofMinutes(7)), d)
    }

    @Test
    fun withoutRetryAfterUsesBackoff() {
        assertEquals(RetryDecision.RetryIn(Duration.ofSeconds(60)), policy.decide(DavException.RetryLater(503, null), 1))
    }

    @Test
    fun exponentialBackoffForNetworkAnd5xx() {
        assertEquals(RetryDecision.RetryIn(Duration.ofSeconds(30)), policy.decide(DavException.Network("Timeout"), 0))
        assertEquals(RetryDecision.RetryIn(Duration.ofSeconds(120)), policy.decide(DavException.ServerError(502), 2))
        assertEquals(RetryDecision.RetryIn(Duration.ofHours(6)), policy.decide(DavException.ServerError(500), 40))
    }

    @Test
    fun otherErrorsAreNotRetriedAutomatically() {
        listOf(DavException.Forbidden(), DavException.NotFound(), DavException.RedirectRefused(), DavException.Tls("SSLHandshakeException"))
            .forEach { assertIs<RetryDecision.GiveUp>(policy.decide(it, 0)) }
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        val real = RetryPolicy(random = Random(42))
        repeat(200) {
            val ms = real.backoff(3).toMillis()
            assertTrue(ms in 192_000..288_000, "$ms")
        }
    }

    @Test
    fun loginGuardBlocksBeforeTheServerWould() {
        val guard = LoginGuard(limit = 3, window = Duration.ofMinutes(10))
        val t0 = Instant.parse("2026-10-29T10:00:00Z")
        repeat(2) { guard.recordFailure(t0.plusSeconds(it * 10L)) }
        assertNull(guard.waitBeforeNextAttempt(t0.plusSeconds(30)))
        guard.recordFailure(t0.plusSeconds(40))
        assertEquals(Duration.ofSeconds(570), guard.waitBeforeNextAttempt(t0.plusSeconds(30)))
        // Pasados 10 minutos del primer fallo vuelve a dejar intentar.
        assertNull(guard.waitBeforeNextAttempt(t0.plus(Duration.ofMinutes(10)).plusSeconds(1)))
    }

    @Test
    fun loginGuardSurvivesRestarts() {
        val t0 = Instant.parse("2026-10-29T10:00:00Z")
        val a = LoginGuard().apply { repeat(3) { recordFailure(t0) } }
        val b = LoginGuard().apply { restore(a.snapshot(t0), t0) }
        assertNotNull(b.waitBeforeNextAttempt(t0.plusSeconds(1)))
    }
}
