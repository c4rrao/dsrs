package com.dsrs.operator.streaming.handlers

import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ProcessingLogRepository
import com.dsrs.operator.services.RewardService
import com.dsrs.operator.streaming.LedgerEvent
import com.dsrs.operator.streaming.TypedContract
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class RewardHandler(
    private val config: OperatorConfig,
    private val rewardService: RewardService,
    private val processingLogRepo: ProcessingLogRepository
) : EventHandler {

    override suspend fun handleCreated(event: LedgerEvent.ContractCreated) {
        if (!config.autoProcessRewards) {
            logger.debug { "Auto-processing disabled, skipping Reward: ${event.contractId}" }
            return
        }

        val typedContract = event.typedContract
        if (typedContract !is TypedContract.RewardContract) {
            logger.warn { "Expected RewardContract but got ${typedContract::class.simpleName}" }
            return
        }

        logger.info { "Processing Reward: ${event.contractId}" }

        try {
            val result = rewardService.processReward(
                rewardContractId = event.contractId,
                reward = typedContract.data
            )

            processingLogRepo.logSuccess(
                eventType = "Reward",
                contractId = event.contractId,
                action = "DistributePerTransaction",
                details = "Groups: ${result.rewardGroupCount}, Contributions: ${result.totalContributions}"
            )

            logger.info { "Processed Reward ${event.contractId}: ${result.rewardGroupCount} groups created" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to process Reward: ${event.contractId}" }

            processingLogRepo.logError(
                eventType = "Reward",
                contractId = event.contractId,
                action = "DistributePerTransaction",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    override suspend fun handleArchived(event: LedgerEvent.ContractArchived) {
        logger.debug { "Reward archived: ${event.contractId}" }
    }

    suspend fun handleRewardGroupCreated(event: LedgerEvent.ContractCreated) {
        if (!config.autoProcessRewards) {
            logger.debug { "Auto-processing disabled, skipping RewardGroup: ${event.contractId}" }
            return
        }

        val typedContract = event.typedContract
        if (typedContract !is TypedContract.RewardGroupContract) {
            logger.warn { "Expected RewardGroupContract but got ${typedContract::class.simpleName}" }
            return
        }

        logger.info { "Processing RewardGroup: ${event.contractId}" }

        try {
            val result = rewardService.processRewardGroup(
                rewardGroupContractId = event.contractId,
                rewardGroup = typedContract.data
            )

            processingLogRepo.logSuccess(
                eventType = "RewardGroup",
                contractId = event.contractId,
                action = "DistributeInGroup",
                details = "GroupId: ${typedContract.data.groupId}, Contributors: ${result.contributorCount}, TotalReward: ${result.totalReward}"
            )

            logger.info { "Processed RewardGroup ${event.contractId}: distributed to ${result.contributorCount} contributors" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to process RewardGroup: ${event.contractId}" }

            processingLogRepo.logError(
                eventType = "RewardGroup",
                contractId = event.contractId,
                action = "DistributeInGroup",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }
}
