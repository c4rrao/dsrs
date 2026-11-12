package com.dsrs.auditor.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

val ALL_TABLES = arrayOf(
    ContractEventsTable,
    AuditLogsTable,
    SyncStateTable,
    ContributionSnapshotsTable,
    QuerySnapshotsTable,
    RewardSnapshotsTable,
    PricingConfigSnapshotsTable,
    MintObservationsTable
)

/**
 * Every mint the auditor has observed. Audit tokens are only ever earned inside a workflow, never
 * sold, so an audit-token mint that is not nested under a workflow choice has no legitimate
 * explanation. Reward tokens are minted by the operator against payments taken off the platform,
 * so arriving on their own is expected.
 */
object MintObservationsTable : LongIdTable("mint_observations") {
    val transactionOffset = varchar("transaction_offset", 255)
    val tokenType = varchar("token_type", 50)
    val totalAmount = long("total_amount")
    val recipientsJson = text("recipients_json")

    // business-event contract in the same transaction, or null if the mint arrived alone
    val accompaniedBy = varchar("accompanied_by", 100).nullable()

    // from the MintEntitlement, when one was visible
    val reason = varchar("reason", 100).nullable()
    val sourceEventId = varchar("source_event_id", 255).nullable()

    val observedAt = datetime("observed_at")

    init {
        uniqueIndex(transactionOffset, tokenType)
    }
}

// Immutable log of every contract event the auditor has observed.
object ContractEventsTable : LongIdTable("contract_events") {
    val eventType = varchar("event_type", 50)
    val contractId = varchar("contract_id", 255)
    val templateName = varchar("template_name", 100)
    val payload = text("payload").nullable()
    val offset = varchar("offset", 255)
    val timestamp = datetime("timestamp")
}

object AuditLogsTable : LongIdTable("audit_logs") {
    val requestType = varchar("request_type", 50)
    val requestParams = text("request_params")
    val result = text("result")
    val matches = bool("matches")
    val requesterParty = varchar("requester_party", 255).nullable()
    val tokensCost = long("tokens_cost").default(0)
    val createdAt = datetime("created_at")
}

object SyncStateTable : Table("sync_state") {
    val key = varchar("key", 50)
    val value = text("value")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(key)
}

// One row per version of each Contribution, with the interval during which it was the live one.
object ContributionSnapshotsTable : LongIdTable("contribution_snapshots") {
    val contractId = varchar("contract_id", 255)
    val contributionId = varchar("contribution_id", 255)
    val transactionId = varchar("transaction_id", 255)
    val state = varchar("state", 20)
    val contributorCount = integer("contributor_count")
    val isActive = bool("is_active")                  // contributorCount > 1
    val totalTrustScore = decimal("total_trust_score", 20, 10)
    val contributorsDataJson = text("contributors_data_json")
    val contributionDataJson = text("contribution_data_json")
    val trustScoreJson = text("trust_score_json")
    val disputeDatesJson = text("dispute_dates_json")
    val version = integer("version")
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()      // null for the current version
    val archivedAt = datetime("archived_at").nullable()
    val createdAt = datetime("created_at")

    init {
        index(false, contractId, version)
        index(false, transactionId)
        index(false, state, validFrom, validTo)
    }
}

object QuerySnapshotsTable : LongIdTable("query_snapshots") {
    val queryId = varchar("query_id", 255)
    val proposalId = varchar("proposal_id", 255).nullable()
    val consumer = varchar("consumer", 255)
    val escrow = varchar("escrow", 255).nullable()
    val status = varchar("status", 50)
    val tokenCost = long("token_cost").nullable()
    val queryParamsJson = text("query_params_json")
    val pricingMetadataJson = text("pricing_metadata_json").nullable()
    val resultsJson = text("results_json").nullable()
    val completionDate = datetime("completion_date").nullable()
    // when the operator read the contribution set (pinned at pricing); the replay instant
    val executionTimestamp = datetime("execution_timestamp").nullable()
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val createdAt = datetime("created_at")

    init {
        index(false, queryId)
        index(false, proposalId)
        index(false, completionDate)
        index(false, validFrom, validTo)
    }
}

// Inserted as pending when a RewardGroup is created, and filled in with the observed transfers
// once DistributeInGroup archives it (HistorySynchronizer.correlateRewardDistributions).
object RewardSnapshotsTable : LongIdTable("reward_snapshots") {
    val distributionId = varchar("distribution_id", 255)
    val contractId = varchar("contract_id", 255)
    val queryId = varchar("query_id", 255).nullable()
    val transactionId = varchar("transaction_id", 255).nullable()
    val escrow = varchar("escrow", 255)
    val contributorPartiesJson = text("contributor_parties_json")
    val totalAmount = long("total_amount")
    val recipientsJson = text("recipients_json")      // observed {party, amount} transfers
    val distributed = bool("distributed").default(false)
    val distributionDate = datetime("distribution_date")
    val createdAt = datetime("created_at")

    init {
        index(false, distributionId)
        index(false, contractId)
        index(false, queryId)
        index(false, transactionId)
        index(false, distributionDate)
    }
}

object PricingConfigSnapshotsTable : LongIdTable("pricing_config_snapshots") {
    val contractId = varchar("contract_id", 255)
    val version = integer("version")
    val basePrice = long("base_price")
    val level1Multiplier = decimal("level1_multiplier", 10, 2)
    val level2Multiplier = decimal("level2_multiplier", 10, 2)
    val coverageWeight = decimal("coverage_weight", 10, 4)
    val coverageSaturation = decimal("coverage_saturation", 10, 4)
    val narrownessWeight = decimal("narrowness_weight", 10, 4)
    val trustWeight = decimal("trust_weight", 10, 4)
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()     // null for the current version
    val createdAt = datetime("created_at")

    init {
        index(false, contractId)
        index(false, validFrom, validTo)
    }
}
