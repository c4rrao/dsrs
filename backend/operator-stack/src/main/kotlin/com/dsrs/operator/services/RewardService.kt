package com.dsrs.operator.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.contribution.Contribution
import com.dsrs.daml.query.QueryResult
import com.dsrs.daml.reward.Reward
import com.dsrs.daml.reward.RewardGroup
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.QueryProposalRepository
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

data class RewardDistributionResult(
    val distributionId: String,
    val rewardGroupCount: Int,
    val totalContributions: Int
)

data class RewardGroupDistributionResult(
    val groupId: String,
    val contributorCount: Int,
    val totalReward: Long
)

class RewardService(
    private val config: OperatorConfig,
    private val ledgerClient: LedgerClient,
    private val tokenMintAuthorityService: TokenMintAuthorityService,
    private val queryProposalRepo: QueryProposalRepository,
    private val ledgerLock: LedgerLock
) {

    // One RewardGroup per transaction the query matched, from the pinned snapshot: the set the
    // consumer was charged for and the auditor replays, not every active contribution.
    suspend fun processReward(
        rewardContractId: String,
        reward: Reward
    ): RewardDistributionResult {
        logger.info { "Processing Reward: $rewardContractId (query ${reward.queryId})" }

        val matchingQueryResult = ledgerClient.queryContractsTyped(
            config.operatorParty,
            QueryResult.COMPANION
        ).firstOrNull { it.data.queryId == reward.queryId }
            ?: throw IllegalStateException(
                "Could not find QueryResult for reward $rewardContractId (queryId ${reward.queryId})"
            )

        val proposalId = matchingQueryResult.data.proposalId
        logger.info { "Found matching QueryResult: ${matchingQueryResult.id.contractId} (proposal $proposalId)" }

        // The lease is released on failure as well, otherwise a failed distribution would block
        // contribution processing until the pin ceiling, even across restarts.
        return try {
            distribute(rewardContractId, reward, proposalId)
        } catch (e: Exception) {
            ledgerLock.releaseQuery(proposalId)
            throw e
        }
    }

    private suspend fun distribute(
        rewardContractId: String,
        reward: Reward,
        proposalId: String
    ): RewardDistributionResult {
        val snapshot = queryProposalRepo.findByProposalId(proposalId)
            ?: throw IllegalStateException(
                "No pinned snapshot for proposal $proposalId - cannot determine which " +
                    "transactions this query matched, so cannot distribute its reward fairly"
            )

        if (snapshot.matchedTransactionIds.isEmpty()) {
            throw IllegalStateException("Query $proposalId matched no transactions; nothing to distribute")
        }

        logger.info { "Query matched ${snapshot.matchedTransactionIds.size} transaction(s)" }

        // contract ids change when a dispute resolves, so match by transaction id now
        val ledgerContributions = ledgerClient.queryContractsTyped(
            config.operatorParty,
            Contribution.COMPANION
        )
        val byTransactionId = ledgerContributions.associateBy { it.data.transactionId.unpack }

        val contributionContracts = snapshot.matchedTransactionIds.mapNotNull { transactionId ->
            val ledgerContract = byTransactionId[transactionId]
            if (ledgerContract == null) {
                logger.warn {
                    "Matched transaction $transactionId has no active Contribution at distribution " +
                        "time (archived or in dispute since the query ran) - skipping its share"
                }
                return@mapNotNull null
            }
            // the same Decimal the choice sums, so the totals agree exactly
            ledgerContract.id to ledgerContract.data.trustScore.total
        }

        if (contributionContracts.isEmpty()) {
            throw IllegalStateException(
                "None of the ${snapshot.matchedTransactionIds.size} matched transactions for " +
                    "reward $rewardContractId are still on the ledger"
            )
        }

        logger.info { "Distributing across ${contributionContracts.size} matched contribution(s)" }

        val contributionCids = java.util.ArrayList<Contribution.ContractId>()
        contributionContracts.forEach { (contractId, _) -> contributionCids.add(contractId) }

        // the choice recomputes this from the contracts and aborts if the two differ
        val claimedTotalTrustScore = contributionContracts
            .fold(BigDecimal.ZERO) { acc, (_, total) -> acc.add(total) }

        val update = Reward.ContractId(rewardContractId)
            .exerciseDistributePerTransaction(contributionCids, claimedTotalTrustScore)

        ledgerClient.submitUpdate(config.operatorParty, update)

        // DistributeInGroup pays from the copy on each RewardGroup, so nothing after this reads
        // contributions and the set can move again
        ledgerLock.releaseQuery(proposalId)

        logger.info { "Created ${contributionContracts.size} RewardGroups for reward $rewardContractId" }

        return RewardDistributionResult(
            distributionId = reward.distributionId,
            rewardGroupCount = contributionContracts.size,
            totalContributions = contributionContracts.size
        )
    }

    suspend fun processRewardGroup(
        rewardGroupContractId: String,
        rewardGroup: RewardGroup
    ): RewardGroupDistributionResult {
        logger.info { "Processing RewardGroup: $rewardGroupContractId" }

        val tokenMintAuthorityCid = tokenMintAuthorityService.getAuthorityCid()

        // from the group's own copy, so this works even if the Contribution has since been disputed
        val contributorCount = rewardGroup.contributorsData.size

        val holding = ledgerClient.getContractTyped(
            config.operatorParty,
            rewardGroup.holdingCid.contractId,
            com.dsrs.daml.token.holding.Holding.COMPANION
        )

        if (holding == null) {
            throw IllegalStateException("Holding ${rewardGroup.holdingCid.contractId} not found")
        }

        val totalReward = holding.data.amount

        val update = RewardGroup.ContractId(rewardGroupContractId)
            .exerciseDistributeInGroup(tokenMintAuthorityCid)

        ledgerClient.submitUpdate(config.operatorParty, update)

        logger.info { "Distributed rewards to $contributorCount contributors in group $rewardGroupContractId" }

        return RewardGroupDistributionResult(
            groupId = rewardGroup.groupId,
            contributorCount = contributorCount,
            totalReward = totalReward
        )
    }
}
