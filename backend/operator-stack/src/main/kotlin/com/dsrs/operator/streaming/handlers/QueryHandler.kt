package com.dsrs.operator.streaming.handlers

import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ProcessingLogRepository
import com.dsrs.operator.services.QueryService
import com.dsrs.operator.streaming.LedgerEvent
import com.dsrs.operator.streaming.TypedContract
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class QueryHandler(
    private val config: OperatorConfig,
    private val queryService: QueryService,
    private val processingLogRepo: ProcessingLogRepository
) : EventHandler {

    override suspend fun handleCreated(event: LedgerEvent.ContractCreated) {
        if (!config.autoProcessQueries) {
            logger.debug { "Auto-processing disabled, skipping QueryRequest: ${event.contractId}" }
            return
        }

        val typedContract = event.typedContract
        if (typedContract !is TypedContract.QueryRequestContract) {
            logger.warn { "Expected QueryRequestContract but got ${typedContract::class.simpleName}" }
            return
        }

        logger.info { "Processing QueryRequest: ${event.contractId}" }

        try {
            val result = queryService.processQueryRequest(
                contractId = event.contractId,
                queryRequest = typedContract.data
            )

            if (result.rejected) {
                processingLogRepo.logSuccess(
                    eventType = "QueryRequest",
                    contractId = event.contractId,
                    action = "RejectQueryRequest",
                    details = "ResultCount: ${result.resultCount}, Reason: ${result.rejectionReason}"
                )
                logger.info { "Rejected QueryRequest ${event.contractId}: ${result.rejectionReason}" }
            } else {
                processingLogRepo.logSuccess(
                    eventType = "QueryRequest",
                    contractId = event.contractId,
                    action = "ProposeCost",
                    details = "ResultCount: ${result.resultCount}, ProposalCid: ${result.proposalContractId}"
                )
                logger.info { "Processed QueryRequest ${event.contractId}: resultCount=${result.resultCount}" }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to process QueryRequest: ${event.contractId}" }

            processingLogRepo.logError(
                eventType = "QueryRequest",
                contractId = event.contractId,
                action = "ProposeCost",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    override suspend fun handleArchived(event: LedgerEvent.ContractArchived) {
        logger.debug { "QueryRequest archived: ${event.contractId}" }
    }

    suspend fun handleFundedQueryCreated(event: LedgerEvent.ContractCreated) {
        if (!config.autoProcessQueries) {
            logger.debug { "Auto-processing disabled, skipping FundedQuery: ${event.contractId}" }
            return
        }

        val typedContract = event.typedContract
        if (typedContract !is TypedContract.FundedQueryContract) {
            logger.warn { "Expected FundedQueryContract but got ${typedContract::class.simpleName}" }
            return
        }

        logger.info { "Processing FundedQuery: ${event.contractId}" }

        try {
            val result = queryService.processFundedQuery(
                contractId = event.contractId,
                fundedQuery = typedContract.data
            )

            if (result.failed) {
                processingLogRepo.logSuccess(
                    eventType = "FundedQuery",
                    contractId = event.contractId,
                    action = "FailQuery",
                    details = "Reason: ${result.failureReason}"
                )
                logger.info { "Failed (and refunded) FundedQuery ${event.contractId}: ${result.failureReason}" }
            } else {
                processingLogRepo.logSuccess(
                    eventType = "FundedQuery",
                    contractId = event.contractId,
                    action = "CompleteQuery",
                    details = "Insights: ${result.insightCount}, RewardCid: ${result.rewardContractId}"
                )
                logger.info { "Completed FundedQuery ${event.contractId}" }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to process FundedQuery: ${event.contractId}" }

            processingLogRepo.logError(
                eventType = "FundedQuery",
                contractId = event.contractId,
                action = "CompleteQuery",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }
}
