package com.dsrs.operator.streaming.handlers

import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ProcessingLogRepository
import com.dsrs.operator.services.ContributionService
import com.dsrs.operator.services.LedgerLock
import com.dsrs.operator.streaming.LedgerEvent
import com.dsrs.operator.streaming.TypedContract
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ContributionHandler(
    private val config: OperatorConfig,
    private val contributionService: ContributionService,
    private val processingLogRepo: ProcessingLogRepository,
    private val ledgerLock: LedgerLock
) : EventHandler {

    override suspend fun handleCreated(event: LedgerEvent.ContractCreated) {
        if (!config.autoProcessContributions) {
            logger.debug { "Auto-processing disabled, skipping ContributionRequest: ${event.contractId}" }
            return
        }

        val typedContract = event.typedContract
        if (typedContract !is TypedContract.ContributionRequestContract) {
            logger.warn { "Expected ContributionRequestContract but got ${typedContract::class.simpleName}" }
            return
        }

        // Contribution processing is the exclusive side of the lock. On refusal the request is
        // left on the ledger for the reconciler; waiting here would hold up the stream, and with
        // it the events that release the lock.
        val requestId = typedContract.data.requestId
        when (val acquisition = ledgerLock.acquireForContribution(requestId)) {
            is LedgerLock.Acquisition.Refused -> {
                logger.info {
                    "Deferring ContributionRequest ${event.contractId}: ${acquisition.reason}"
                }
                return
            }
            is LedgerLock.Acquisition.Granted -> Unit
        }

        logger.info { "Processing ContributionRequest: ${event.contractId}" }

        try {
            val result = contributionService.processContributionRequest(
                contractId = event.contractId,
                request = typedContract.data
            )

            processingLogRepo.logSuccess(
                eventType = "ContributionRequest",
                contractId = event.contractId,
                action = result.action,
                details = result.details
            )

            logger.info { "Processed ContributionRequest ${event.contractId}: ${result.action}" }

        } catch (e: Exception) {
            // NOT_FOUND is expected when catching up on contracts already processed
            if (e is io.grpc.StatusRuntimeException && e.status.code == io.grpc.Status.Code.NOT_FOUND) {
                logger.debug { "Contract already archived (expected during catch-up): ${event.contractId}" }
            } else {
                logger.error(e) { "Failed to process ContributionRequest: ${event.contractId}" }
            }

            processingLogRepo.logError(
                eventType = "ContributionRequest",
                contractId = event.contractId,
                action = "process",
                errorMessage = e.message ?: "Unknown error"
            )
        } finally {
            // always released, since the write side has no expiry to fall back on
            ledgerLock.releaseContribution(requestId)
        }
    }

    override suspend fun handleArchived(event: LedgerEvent.ContractArchived) {
        logger.debug { "ContributionRequest archived: ${event.contractId}" }
    }
}
