package com.dsrs.operator.services

import mu.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Serializes query pricing against contribution processing.
 *
 * A query is priced over the contribution set at one instant, but distribution resolves each
 * matched transaction to whatever contribution is live when the pool is split. A corroboration in
 * between changes the weights the consumer was quoted; a dispute archives a matched contribution
 * and its share is skipped. Every choice that removes or alters an active Contribution is the
 * operator's own, so serializing here is enough. Vote and ProposeDisputeData act on a Dispute and
 * can only bring a contribution back.
 *
 * Queries are the readers and may overlap; contribution processing is the exclusive writer. An
 * unfunded query's lease expires, since funding has no deadline. Once funded the lease is pinned
 * until DistributePerTransaction, the last step that reads contributions (RewardGroup carries a
 * copy of what DistributeInGroup needs). A pin still has a ceiling, so a distribution that failed
 * cannot block contributions forever, even across restarts that replay it.
 *
 * Simple rather than fine-grained: it stops all contribution processing, not just the matched
 * records, and has no protection against writer starvation.
 */
class LedgerLock(
    private val leaseDuration: Duration,
    private val pinnedCeiling: Duration = Duration.ofMinutes(10)
) {

    private data class Lease(val deadline: Instant?, val pinned: Boolean)

    private val guard = ReentrantLock()

    // proposalId -> lease; non-empty means queries hold the read side
    private val queryLeases = mutableMapOf<String, Lease>()

    // non-null means contribution processing holds the write side
    private var contributionHolder: String? = null

    sealed class Acquisition {
        object Granted : Acquisition()
        data class Refused(val reason: String) : Acquisition()
    }

    // Re-acquiring for a proposal that already holds a lease is a no-op, since the reconciler can
    // see a request the stream already handled.
    fun acquireForQuery(proposalId: String): Acquisition = guard.withLock {
        dropExpiredLeases()

        contributionHolder?.let {
            return Acquisition.Refused("contribution request $it is being processed")
        }

        if (queryLeases.containsKey(proposalId)) return Acquisition.Granted

        queryLeases[proposalId] = Lease(Instant.now().plus(leaseDuration), pinned = false)
        logger.info {
            "Query $proposalId took a contribution-set lease for $leaseDuration " +
                "(${queryLeases.size} query(ies) now holding)"
        }
        Acquisition.Granted
    }

    // The consumer has funded, so the lease is held until distribution instead of expiring.
    fun pinForFundedQuery(proposalId: String) = guard.withLock {
        val existing = queryLeases[proposalId]
        if (existing == null) {
            // the lease expired before funding, or the operator restarted: take it again, pinned
            logger.warn {
                "Funded query $proposalId held no lease (expired, or the operator restarted); " +
                    "taking a pinned one so its distribution still runs against a still set"
            }
        }
        queryLeases[proposalId] = Lease(Instant.now().plus(pinnedCeiling), pinned = true)
    }

    fun releaseQuery(proposalId: String) = guard.withLock {
        if (queryLeases.remove(proposalId) != null) {
            logger.info {
                "Query $proposalId released its lease (${queryLeases.size} still holding)"
            }
        }
    }

    /**
     * Never wait on a refusal. The ledger stream is sequential, so blocking here would hold up the
     * FundedQuery and Reward events that release the read side. Refused requests stay on the
     * ledger and the reconciler picks them up.
     */
    fun acquireForContribution(requestId: String): Acquisition = guard.withLock {
        dropExpiredLeases()

        if (queryLeases.isNotEmpty()) {
            val holders = queryLeases.keys.joinToString(", ")
            return Acquisition.Refused("query/queries pricing or distributing: $holders")
        }

        contributionHolder?.let {
            return Acquisition.Refused("contribution request $it is already being processed")
        }

        contributionHolder = requestId
        Acquisition.Granted
    }

    fun releaseContribution(requestId: String) = guard.withLock {
        if (contributionHolder == requestId) contributionHolder = null
    }

    // Removes them here too, so contributions can move again without waiting on the ledger.
    fun takeExpiredLeases(): List<String> = guard.withLock {
        val now = Instant.now()
        val expired = queryLeases.filter { (_, lease) ->
            !lease.pinned && lease.deadline != null && lease.deadline.isBefore(now)
        }.keys.toList()
        expired.forEach { queryLeases.remove(it) }
        expired
    }

    // Unfunded quotes only. The reconciler checks them against the ledger, since a rejected
    // proposal will never be funded. Pinned leases belong to funded queries, whose proposal is
    // archived legitimately.
    fun unfundedLeases(): List<String> = guard.withLock {
        queryLeases.filterValues { !it.pinned }.keys.toList()
    }

    fun isIdle(): Boolean = guard.withLock {
        dropExpiredLeases()
        queryLeases.isEmpty() && contributionHolder == null
    }

    fun describe(): String = guard.withLock {
        when {
            contributionHolder != null -> "processing contribution ${contributionHolder}"
            queryLeases.isEmpty() -> "idle"
            else -> "held by ${queryLeases.size} query(ies): " +
                queryLeases.entries.joinToString(", ") { (id, lease) ->
                    if (lease.pinned) "$id (funded, pinned)" else id
                }
        }
    }

    private fun dropExpiredLeases() {
        val now = Instant.now()
        val done = queryLeases.filterValues { it.deadline != null && it.deadline.isBefore(now) }

        done.forEach { (proposalId, lease) ->
            if (lease.pinned) {
                // distribution is sub-second, so reaching the ceiling means it failed
                logger.error {
                    "Funded query $proposalId held the contribution set for $pinnedCeiling " +
                        "without its distribution completing; releasing it. The payout for this " +
                        "query did not happen - an inclusion audit will report it as a query " +
                        "that used data and paid nothing for it."
                }
            } else {
                logger.warn {
                    "Query $proposalId lease expired before funding; releasing the contribution set"
                }
            }
            queryLeases.remove(proposalId)
        }
    }
}
