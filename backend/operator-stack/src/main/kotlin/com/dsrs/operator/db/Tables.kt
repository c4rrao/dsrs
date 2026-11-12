package com.dsrs.operator.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.date

object Contributions : Table("contributions") {
    val contractId = varchar("contract_id", 255)
    val contributionId = varchar("contribution_id", 255)
    val transactionId = varchar("transaction_id", 255).uniqueIndex()

    // "active" | "in_dispute" | "archived"
    val state = varchar("state", 20).default("active").index()

    // "pending" until the ledger confirms the contract, then "confirmed"
    val status = varchar("status", 20).default("confirmed").index()
    val contributionData = text("contribution_data")
    val contributorsData = text("contributors_data")

    // Denormalized from contributorsData so the corroboration filter (at least two parties) can be
    // an indexed WHERE clause. ContributionRepository keeps both in step.
    val contributorCount = integer("contributor_count").default(1).index()
    val trustScore = text("trust_score")
    val totalTrustScore = decimal("total_trust_score", 20, 10)
    val originalSubmissionDate = datetime("original_submission_date")
    val disputeDates = text("dispute_dates")

    // denormalized for querying
    val propertyCity = varchar("property_city", 255).nullable().index()
    val propertyCountry = varchar("property_country", 100).nullable()
    val propertyPostalCode = varchar("property_postal_code", 50).nullable()
    val salePrice = decimal("sale_price", 20, 2).nullable().index()
    val saleDate = date("sale_date").nullable().index()
    val propertyType = varchar("property_type", 100).nullable().index()
    val squareMeters = decimal("square_meters", 10, 2).nullable()
    val numberOfRooms = integer("number_of_rooms").nullable()
    val yearBuilt = integer("year_built").nullable()
    val energyRating = varchar("energy_rating", 2).nullable().index()
    val tags = text("tags").nullable()

    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val archivedAt = datetime("archived_at").nullable()

    override val primaryKey = PrimaryKey(contractId)
}

object SyncState : Table("sync_state") {
    val id = integer("id").default(1)
    val lastOffset = varchar("last_offset", 500).nullable()
    val lastSyncTime = datetime("last_sync_time").nullable()
    val contractsSynced = integer("contracts_synced").default(0)
    val syncStatus = varchar("sync_status", 50).default("idle")
    val syncError = text("sync_error").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ProcessingLog : Table("processing_log") {
    val id = long("id").autoIncrement()
    val eventType = varchar("event_type", 100).index()
    val contractId = varchar("contract_id", 255).index()
    val action = varchar("action", 100)
    val status = varchar("status", 50)
    val details = text("details").nullable()
    val errorMessage = text("error_message").nullable()
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(id)
}

// History of pricing configs, so a query can be re-priced with the config in force when it ran.
object PricingConfigs : Table("pricing_configs") {
    val contractId = varchar("contract_id", 255)
    val version = integer("version")
    val basePrice = long("base_price")
    val level1Multiplier = decimal("level1_multiplier", 10, 2)
    val level2Multiplier = decimal("level2_multiplier", 10, 2)
    val coverageWeight = decimal("coverage_weight", 10, 4)
    val coverageSaturation = decimal("coverage_saturation", 10, 4)
    val narrownessWeight = decimal("narrowness_weight", 10, 4)
    val trustWeight = decimal("trust_weight", 10, 4)
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(contractId)
}

/**
 * The insights a query was priced from, frozen at pricing time with their executionTimestamp and
 * replayed verbatim into CompleteQuery. Recomputing at funding time would deliver a different
 * dataset from the one quoted, and the auditor re-executes the query at that same timestamp.
 */
object QueryProposals : Table("query_proposals") {
    val proposalId = varchar("proposal_id", 255)
    val insightsJson = text("insights_json")
    val executionTimestamp = datetime("execution_timestamp")
    val resultCount = integer("result_count")
    // reward distribution pays exactly the transactions the query matched
    val matchedTransactionIdsJson = text("matched_transaction_ids_json")
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(proposalId)
}

// Only what needs SQL lives here; everything else (disputes, queries, holdings) is read from the ledger.
val ALL_TABLES = arrayOf(
    Contributions,
    SyncState,
    ProcessingLog,
    PricingConfigs,
    QueryProposals
)
