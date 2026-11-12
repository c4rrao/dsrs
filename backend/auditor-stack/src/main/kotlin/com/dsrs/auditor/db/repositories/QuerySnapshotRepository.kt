package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.QuerySnapshotsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class QuerySnapshotEntity(
    val id: Long = 0,
    val queryId: String,
    val proposalId: String? = null,
    val consumer: String,
    val escrow: String? = null,
    val status: String,
    val tokenCost: Long? = null,
    val queryParamsJson: String,
    val pricingMetadataJson: String? = null,
    val resultsJson: String? = null,
    val completionDate: LocalDateTime? = null,
    val executionTimestamp: LocalDateTime? = null,
    val validFrom: LocalDateTime,
    val validTo: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

class QuerySnapshotRepository {

    fun insert(snapshot: QuerySnapshotEntity): Long = transaction {
        QuerySnapshotsTable.update({
            (QuerySnapshotsTable.queryId eq snapshot.queryId) and
            QuerySnapshotsTable.validTo.isNull()
        }) {
            it[validTo] = snapshot.validFrom
        }

        QuerySnapshotsTable.insertAndGetId {
            it[queryId] = snapshot.queryId
            it[proposalId] = snapshot.proposalId
            it[consumer] = snapshot.consumer
            it[escrow] = snapshot.escrow
            it[status] = snapshot.status
            it[tokenCost] = snapshot.tokenCost
            it[queryParamsJson] = snapshot.queryParamsJson
            it[pricingMetadataJson] = snapshot.pricingMetadataJson
            it[resultsJson] = snapshot.resultsJson
            it[completionDate] = snapshot.completionDate
            it[executionTimestamp] = snapshot.executionTimestamp
            it[validFrom] = snapshot.validFrom
            it[validTo] = snapshot.validTo
            it[createdAt] = snapshot.createdAt
        }.value
    }

    fun findCurrentByQueryId(queryId: String): QuerySnapshotEntity? = transaction {
        QuerySnapshotsTable.select {
            (QuerySnapshotsTable.queryId eq queryId) and
            QuerySnapshotsTable.validTo.isNull()
        }
            .map { it.toEntity() }
            .firstOrNull()
    }

    fun findCurrentByProposalId(proposalId: String): QuerySnapshotEntity? = transaction {
        QuerySnapshotsTable.select {
            (QuerySnapshotsTable.proposalId eq proposalId) and
            QuerySnapshotsTable.validTo.isNull()
        }
            .map { it.toEntity() }
            .firstOrNull()
    }

    // One proposal id is shared by several lifecycle rows, each "current" under its own queryId;
    // this returns the completed one, which carries the executionTimestamp.
    fun findCompletedByProposalId(proposalId: String): QuerySnapshotEntity? = transaction {
        QuerySnapshotsTable.select {
            (QuerySnapshotsTable.proposalId eq proposalId) and
            (QuerySnapshotsTable.status eq "completed") and
            QuerySnapshotsTable.validTo.isNull()
        }
            .orderBy(QuerySnapshotsTable.createdAt, SortOrder.DESC)
            .map { it.toEntity() }
            .firstOrNull()
    }

    // Reward groups are keyed by the FundedQuery's queryId ("FUNDED-..."), while the completed row
    // is keyed by the result id ("RESULT-..."). Looking groups up by the wrong one finds nothing.
    fun findFundedByProposalId(proposalId: String): QuerySnapshotEntity? = transaction {
        QuerySnapshotsTable.select {
            (QuerySnapshotsTable.proposalId eq proposalId) and
            (QuerySnapshotsTable.status eq "funded") and
            QuerySnapshotsTable.validTo.isNull()
        }
            .orderBy(QuerySnapshotsTable.createdAt, SortOrder.DESC)
            .map { it.toEntity() }
            .firstOrNull()
    }

    // Current versions only: replaying an offset writes a new version, so without the validTo
    // filter one query would be counted once per replay.
    fun findCompletedInTimeRange(
        fromTime: LocalDateTime,
        toTime: LocalDateTime
    ): List<QuerySnapshotEntity> = transaction {
        QuerySnapshotsTable.select {
            (QuerySnapshotsTable.status eq "completed") and
            QuerySnapshotsTable.validTo.isNull() and
            QuerySnapshotsTable.completionDate.isNotNull() and
            (QuerySnapshotsTable.completionDate greaterEq fromTime) and
            (QuerySnapshotsTable.completionDate lessEq toTime)
        }
            .orderBy(QuerySnapshotsTable.completionDate, SortOrder.ASC)
            .map { it.toEntity() }
    }

    fun findByConsumer(consumer: String, limit: Int = 100): List<QuerySnapshotEntity> = transaction {
        QuerySnapshotsTable.select {
            (QuerySnapshotsTable.consumer eq consumer) and
            QuerySnapshotsTable.validTo.isNull()
        }
            .orderBy(QuerySnapshotsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntity() }
    }

    private fun ResultRow.toEntity() = QuerySnapshotEntity(
        id = this[QuerySnapshotsTable.id].value,
        queryId = this[QuerySnapshotsTable.queryId],
        proposalId = this[QuerySnapshotsTable.proposalId],
        consumer = this[QuerySnapshotsTable.consumer],
        escrow = this[QuerySnapshotsTable.escrow],
        status = this[QuerySnapshotsTable.status],
        tokenCost = this[QuerySnapshotsTable.tokenCost],
        queryParamsJson = this[QuerySnapshotsTable.queryParamsJson],
        pricingMetadataJson = this[QuerySnapshotsTable.pricingMetadataJson],
        resultsJson = this[QuerySnapshotsTable.resultsJson],
        completionDate = this[QuerySnapshotsTable.completionDate],
        executionTimestamp = this[QuerySnapshotsTable.executionTimestamp],
        validFrom = this[QuerySnapshotsTable.validFrom],
        validTo = this[QuerySnapshotsTable.validTo],
        createdAt = this[QuerySnapshotsTable.createdAt]
    )
}
