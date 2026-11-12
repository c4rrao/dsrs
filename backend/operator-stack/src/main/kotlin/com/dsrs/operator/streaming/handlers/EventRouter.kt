package com.dsrs.operator.streaming.handlers

import com.dsrs.operator.streaming.LedgerEvent
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

interface EventHandler {
    suspend fun handleCreated(event: LedgerEvent.ContractCreated)
    suspend fun handleArchived(event: LedgerEvent.ContractArchived)
}

class EventRouter(
    private val contributionHandler: ContributionHandler,
    private val queryHandler: QueryHandler,
    private val rewardHandler: RewardHandler,
    private val syncHandler: SyncHandler
) {
    suspend fun route(event: LedgerEvent) {
        val templateName = when (event) {
            is LedgerEvent.ContractCreated -> event.templateName
            is LedgerEvent.ContractArchived -> event.templateName
        }

        logger.debug { "Routing event for template: $templateName" }

        // sync to the database before any handler runs
        when (event) {
            is LedgerEvent.ContractCreated -> syncHandler.handleCreated(event)
            is LedgerEvent.ContractArchived -> syncHandler.handleArchived(event)
        }

        when (templateName) {
            "ContributionRequest" -> {
                when (event) {
                    is LedgerEvent.ContractCreated -> contributionHandler.handleCreated(event)
                    is LedgerEvent.ContractArchived -> contributionHandler.handleArchived(event)
                }
            }

            "QueryRequest" -> {
                when (event) {
                    is LedgerEvent.ContractCreated -> queryHandler.handleCreated(event)
                    is LedgerEvent.ContractArchived -> queryHandler.handleArchived(event)
                }
            }

            "FundedQuery" -> {
                when (event) {
                    is LedgerEvent.ContractCreated -> queryHandler.handleFundedQueryCreated(event)
                    is LedgerEvent.ContractArchived -> { /* handled by sync */ }
                }
            }

            "Reward" -> {
                when (event) {
                    is LedgerEvent.ContractCreated -> rewardHandler.handleCreated(event)
                    is LedgerEvent.ContractArchived -> rewardHandler.handleArchived(event)
                }
            }

            "RewardGroup" -> {
                when (event) {
                    is LedgerEvent.ContractCreated -> rewardHandler.handleRewardGroupCreated(event)
                    is LedgerEvent.ContractArchived -> { /* handled by sync */ }
                }
            }

            else -> {
                logger.debug { "No specific handler for $templateName" }
            }
        }
    }
}
