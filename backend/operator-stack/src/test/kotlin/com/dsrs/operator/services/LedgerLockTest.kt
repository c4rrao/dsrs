package com.dsrs.operator.services

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerLockTest {

    private fun granted(a: LedgerLock.Acquisition) = a is LedgerLock.Acquisition.Granted

    private val minute = Duration.ofMinutes(5)

    @Test
    fun `a fresh lock is idle and lets either side in`() {
        val lock = LedgerLock(minute)
        assertTrue(lock.isIdle())
        assertTrue(granted(lock.acquireForQuery("PROP-1")))
    }

    @Test
    fun `queries are readers and run concurrently`() {
        val lock = LedgerLock(minute)
        assertTrue(granted(lock.acquireForQuery("PROP-1")))
        assertTrue(granted(lock.acquireForQuery("PROP-2")))
        assertTrue(granted(lock.acquireForQuery("PROP-3")))
        assertTrue(lock.describe().contains("3 query"))
    }

    @Test
    fun `contribution processing is refused while any query holds the set`() {
        val lock = LedgerLock(minute)
        lock.acquireForQuery("PROP-1")

        val refusal = lock.acquireForContribution("REQ-1")
        assertTrue(refusal is LedgerLock.Acquisition.Refused)
        assertTrue((refusal as LedgerLock.Acquisition.Refused).reason.contains("PROP-1"))
    }

    @Test
    fun `contributions are let through once the last query releases`() {
        val lock = LedgerLock(minute)
        lock.acquireForQuery("PROP-1")
        lock.acquireForQuery("PROP-2")

        lock.releaseQuery("PROP-1")
        assertFalse(granted(lock.acquireForContribution("REQ-1")), "one query still holds it")

        lock.releaseQuery("PROP-2")
        assertTrue(granted(lock.acquireForContribution("REQ-1")))
    }

    @Test
    fun `a query is refused while a contribution is being processed`() {
        val lock = LedgerLock(minute)
        lock.acquireForContribution("REQ-1")

        val refusal = lock.acquireForQuery("PROP-1")
        assertTrue(refusal is LedgerLock.Acquisition.Refused)

        lock.releaseContribution("REQ-1")
        assertTrue(granted(lock.acquireForQuery("PROP-1")))
    }

    @Test
    fun `an unfunded lease expires so an idle consumer cannot hold contributions forever`() {
        val lock = LedgerLock(Duration.ofMillis(-1))  // already past its deadline
        lock.acquireForQuery("PROP-1")

        assertEquals(listOf("PROP-1"), lock.takeExpiredLeases())
        assertTrue(granted(lock.acquireForContribution("REQ-1")), "expired lease must not block")
    }

    @Test
    fun `a funded lease outlives the pricing clock, because a paid query cannot be cancelled`() {
        val lock = LedgerLock(Duration.ofMillis(-1))
        lock.acquireForQuery("PROP-1")
        lock.pinForFundedQuery("PROP-1")

        assertTrue(lock.takeExpiredLeases().isEmpty(), "a funded query is not an expirable quote")
        assertFalse(
            granted(lock.acquireForContribution("REQ-1")),
            "the completion and distribution tail must still hold the set"
        )

        lock.releaseQuery("PROP-1")
        assertTrue(granted(lock.acquireForContribution("REQ-1")))
    }

    @Test
    fun `funding re-takes a lease that expired first, rather than distributing over a moving set`() {
        val lock = LedgerLock(Duration.ofMillis(-1))
        lock.acquireForQuery("PROP-1")
        lock.takeExpiredLeases()

        // funded after the lease expired: funding takes the lease again, pinned
        lock.pinForFundedQuery("PROP-1")
        assertFalse(granted(lock.acquireForContribution("REQ-1")))
    }

    @Test
    fun `a pin that outlives its ceiling is released, so a failed payout cannot wedge the writer`() {
        val lock = LedgerLock(minute, pinnedCeiling = Duration.ofMillis(-1))
        lock.acquireForQuery("PROP-1")
        lock.pinForFundedQuery("PROP-1")

        assertTrue(
            granted(lock.acquireForContribution("REQ-1")),
            "a distribution that never completed must not hold the set for ever"
        )
    }

    @Test
    fun `a pin inside its ceiling still holds the set`() {
        val lock = LedgerLock(Duration.ofMillis(-1), pinnedCeiling = Duration.ofMinutes(10))
        lock.acquireForQuery("PROP-1")
        lock.pinForFundedQuery("PROP-1")

        assertFalse(
            granted(lock.acquireForContribution("REQ-1")),
            "the completion and distribution tail must still hold the set"
        )
    }

    @Test
    fun `re-acquiring for the same proposal is a no-op, not a second lease`() {
        val lock = LedgerLock(minute)
        lock.acquireForQuery("PROP-1")
        lock.acquireForQuery("PROP-1")

        lock.releaseQuery("PROP-1")
        assertTrue(granted(lock.acquireForContribution("REQ-1")), "one release must be enough")
    }

    @Test
    fun `releasing a lease nobody holds is harmless`() {
        val lock = LedgerLock(minute)
        lock.releaseQuery("PROP-UNKNOWN")
        lock.releaseContribution("REQ-UNKNOWN")
        assertTrue(lock.isIdle())
    }

    @Test
    fun `a contribution release by a different holder does not free the lock`() {
        val lock = LedgerLock(minute)
        lock.acquireForContribution("REQ-1")
        lock.releaseContribution("REQ-2")
        assertFalse(lock.isIdle(), "only the holder may release")
        lock.releaseContribution("REQ-1")
        assertTrue(lock.isIdle())
    }

    // The lease ends at DistributePerTransaction, which is only safe because DistributeInGroup
    // pays from the copy on RewardGroup and reads no contributions.
    @Test
    fun `a funded query releases the set once its pool has been split`() {
        val lock = LedgerLock(minute)
        lock.acquireForQuery("PROP-1")
        lock.pinForFundedQuery("PROP-1")
        assertFalse(granted(lock.acquireForContribution("REQ-1")))

        lock.releaseQuery("PROP-1")

        assertTrue(lock.isIdle())
        assertTrue(granted(lock.acquireForContribution("REQ-1")))
    }
}
