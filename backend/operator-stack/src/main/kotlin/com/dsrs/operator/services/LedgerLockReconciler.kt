package com.dsrs.operator.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.contribution.ContributionRequest
import com.dsrs.daml.query.QueryProposal
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ProcessingLogRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import mu.KotlinLogging
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Picks up contribution requests the lock turned away, and withdraws quotes whose lease ran out.
 * Stateless: each pass asks the ledger which ContributionRequests are still active, which also
 * catches requests missed for any other reason. Runs on its own coroutine, never on the stream.
 */
class LedgerLockReconciler(
    private val config: OperatorConfig,
    private val ledgerClient: LedgerClient,
    private val contributionService: ContributionService,
    private val processingLogRepo: ProcessingLogRepository,
    private val ledgerLock: LedgerLock,
    private val intervalMillis: Long = 5_000
) {

    suspend fun run() {
        logger.info {
            "Ledger lock reconciler started (every ${intervalMillis}ms, " +
                "lease ${config.contributionSetLease})"
        }
        while (coroutineContext.isActive) {
            try {
                withdrawExpiredQuotes()
                releaseVanishedQuotes()
                processDeferredContributions()
            } catch (e: Exception) {
                logger.error(e) { "Reconciler pass failed; will try again" }
            }
            delay(intervalMillis)
        }
    }

    // Only unfunded quotes can expire. No funds are locked before LockFundsForQuery, so the
    // consumer just requests again and gets a price over the current data.
    private fun withdrawExpiredQuotes() {
        val expired = ledgerLock.takeExpiredLeases()
        if (expired.isEmpty()) return

        logger.warn { "Withdrawing ${expired.size} quote(s) whose contribution-set lease ran out" }

        val proposals = ledgerClient.queryContractsTyped(config.operatorParty, QueryProposal.COMPANION)
            .associateBy { it.data.proposalId }

        expired.forEach { proposalId ->
            val proposal = proposals[proposalId]
            if (proposal == null) {
                // already funded, rejected or gone; the lease was dropped anyway
                logger.info { "Expired proposal $proposalId is no longer on the ledger; nothing to withdraw" }
                return@forEach
            }
            try {
                val update = proposal.id.exerciseExpireQueryProposal(
                    "the quote's contribution-set lease (${config.contributionSetLease}) ran out " +
                        "before it was funded; please request the query again to be re-priced"
                )
                ledgerClient.submitUpdate(config.operatorParty, update)
                processingLogRepo.logSuccess(
                    eventType = "QueryProposal",
                    contractId = proposal.id.contractId,
                    action = "ExpireQueryProposal",
                    details = "ProposalId: $proposalId, lease: ${config.contributionSetLease}"
                )
                logger.info { "Withdrew expired quote $proposalId" }
            } catch (e: Exception) {
                logger.error(e) { "Could not withdraw expired quote $proposalId" }
            }
        }
    }

    // A rejected quote archives its proposal, which nothing in the stream maps back to the lease.
    // Asking the ledger which proposals still exist is simpler; funded ones are pinned and not
    // in this list.
    private fun releaseVanishedQuotes() {
        val unfunded = ledgerLock.unfundedLeases()
        if (unfunded.isEmpty()) return

        val live = ledgerClient.queryContractsTyped(config.operatorParty, QueryProposal.COMPANION)
            .map { it.data.proposalId }
            .toSet()

        unfunded.filterNot { it in live }.forEach { proposalId ->
            logger.info {
                "Quote $proposalId is no longer on the ledger (rejected, or never funded); " +
                    "releasing its hold on the contribution set"
            }
            ledgerLock.releaseQuery(proposalId)
        }
    }

    // One request per pass, taking the lock the normal way.
    private suspend fun processDeferredContributions() {
        if (!config.autoProcessContributions) return
        if (!ledgerLock.isIdle()) return

        val pending = ledgerClient.queryContractsTyped(config.operatorParty, ContributionRequest.COMPANION)
        if (pending.isEmpty()) return

        logger.info { "Reconciler found ${pending.size} unprocessed contribution request(s)" }

        for (request in pending) {
            val requestId = request.data.requestId
            when (val acquisition = ledgerLock.acquireForContribution(requestId)) {
                is LedgerLock.Acquisition.Refused -> {
                    // a query took the set again; the rest waits for the next pass
                    logger.debug { "Stopping this pass: ${acquisition.reason}" }
                    return
                }
                is LedgerLock.Acquisition.Granted -> Unit
            }

            try {
                val result = contributionService.processContributionRequest(
                    contractId = request.id.contractId,
                    request = request.data
                )
                processingLogRepo.logSuccess(
                    eventType = "ContributionRequest",
                    contractId = request.id.contractId,
                    action = result.action,
                    details = "(deferred, processed by reconciler) ${result.details}"
                )
                logger.info { "Reconciler processed deferred request $requestId: ${result.action}" }
            } catch (e: Exception) {
                if (e is io.grpc.StatusRuntimeException && e.status.code == io.grpc.Status.Code.NOT_FOUND) {
                    logger.debug { "Request $requestId already archived" }
                } else {
                    logger.error(e) { "Reconciler could not process deferred request $requestId" }
                    processingLogRepo.logError(
                        eventType = "ContributionRequest",
                        contractId = request.id.contractId,
                        action = "process",
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            } finally {
                ledgerLock.releaseContribution(requestId)
            }
        }
    }
}
