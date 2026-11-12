package com.dsrs.auditor.models

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class VerifyPricingRequest(
    val queryId: String? = null,
    val proposalId: String? = null
) {
    init {
        require(queryId != null || proposalId != null) {
            "Either queryId or proposalId must be provided"
        }
    }
}

@Serializable
data class VerifyRewardRequest(
    val queryId: String,
    val transactionId: String,
    // the answer only covers the requester's own share, and non-contributors are refused
    val requester: String
)

@Serializable
data class VerifyInclusionRequest(
    val transactionId: String,
    val fromTime: String,
    val toTime: String
)

@Serializable
data class PricingVerificationResult(
    val queryId: String,
    val expected: Long,
    val actual: Long,
    // the price agrees and, once the query has completed, so do the delivered statistics
    val matches: Boolean,
    // within the rounding tolerance
    val priceMatches: Boolean = matches,
    // false before the query completed
    val resultChecked: Boolean = false,
    // null when not checked
    val resultMatches: Boolean? = null,
    val resultDiscrepancies: List<StatisticDiscrepancyDto> = emptyList(),
    val pricingConfigId: String,
    val verifiedAt: String,
    // the formula's inputs, re-derived independently
    val claimedNumTransactions: Long? = null,
    val derivedNumTransactions: Long? = null,
    val metadataMatches: Boolean? = null,
    val metadataDiscrepancies: List<MetadataDiscrepancyDto> = emptyList(),
    // QueryResult.executionTimestamp
    val replayedAt: String? = null,
    // A completed query that created no reward groups collected money it never distributed,
    // which the price arithmetic alone would report as correct.
    val rewardGroupsCreated: Int? = null,
    val paymentProducedDistribution: Boolean? = null,
    // Before completion there is no pinned executionTimestamp, so the replay uses the time the
    // proposal was observed and the metadata comparison is only indicative.
    val replayTimeApproximate: Boolean = false
)

@Serializable
data class StatisticDiscrepancyDto(
    val field: String,
    val delivered: String,
    val derived: String
)

@Serializable
data class MetadataDiscrepancyDto(
    val field: String,
    val claimed: String,
    val derived: String
)

/**
 * What a reward check tells the requester: the share they were owed beside the one they received,
 * and a verdict on each link of the chain that derived it. Other contributors' figures and the
 * query's price and pool stay with the auditor. Nothing is withheld that the requester could not
 * check themselves from the Contribution they observe.
 */
@Serializable
data class RewardVerificationResult(
    val queryId: String,
    val transactionId: String,
    val contributorResult: ContributorRewardResult,
    val matches: Boolean,
    val verifiedAt: String,
    // Every group of the query against the pool left after fees. Each group can be right on its
    // own while the query as a whole paid out less than it collected.
    val queryTotalMatches: Boolean? = null,
    val groupTotalMatches: Boolean? = null,
    val withinGroupMatches: Boolean? = null,
    // the failed links, named without the figures behind them
    val failureModes: List<String> = emptyList()
)

@Serializable
data class ContributorRewardResult(
    val contributor: String,
    val expectedAmount: Long,
    val actualAmount: Long,
    val matches: Boolean
)

@Serializable
data class InclusionVerificationResult(
    val transactionId: String,
    val fromTime: String,
    val toTime: String,
    val queries: List<QueryInclusionInfo>,
    val included: Boolean,
    val verifiedAt: String,
    // queries that used the transaction but produced no reward group for it
    val matchedButNotRewarded: Int = 0,
    // could not be replayed, so evidence for neither
    val unverifiable: List<String> = emptyList()
)

@Serializable
data class QueryInclusionInfo(
    val queryId: String,
    val completionDate: String,
    val rewardAmount: Long?,
    val rewarded: Boolean = false
)

@Serializable
data class MintObservationResult(
    val observed: Int,
    val mintedByTokenType: Map<String, Long>,
    // audit tokens minted outside any workflow; should be zero
    val anomalous: Int,
    val note: String,
    val mints: List<MintObservation>
)

@Serializable
data class MintObservation(
    val transactionOffset: String,
    val tokenType: String,
    val totalAmount: Long,
    val accompaniedBy: String?,
    val reason: String?,
    val sourceEventId: String?,
    val anomalous: Boolean
)

@Serializable
data class MintApprovalResult(
    val holdingContractId: String,
    val recipient: String,
    val tokenType: String,
    val amount: Long,
    val approvedAt: String
)

@Serializable
data class AuditLogEntry(
    val id: Long,
    val requestType: String,
    val requestParams: String,
    val result: String,
    val requesterParty: String?,
    val createdAt: String
)

@Serializable
data class ContractEventLog(
    val eventId: Long,
    val eventType: String,
    val contractId: String,
    val templateName: String,
    val payload: String?,
    val offset: String,
    val timestamp: String
)
