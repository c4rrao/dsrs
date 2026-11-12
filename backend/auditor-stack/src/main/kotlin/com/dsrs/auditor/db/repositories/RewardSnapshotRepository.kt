package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.RewardSnapshotsTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

@Serializable
data class RewardRecipient(val party: String, val amount: Long)

data class RewardSnapshotEntity(
    val id: Long = 0,
    val distributionId: String,
    val contractId: String,
    val queryId: String? = null,
    val transactionId: String? = null,
    val escrow: String,
    val contributorParties: List<String>,
    val totalAmount: Long,
    val recipients: List<RewardRecipient>,
    val distributed: Boolean,
    val distributionDate: LocalDateTime,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

class RewardSnapshotRepository(private val json: Json) {

    /**
     * Idempotent on contractId, since offsets are replayed on restart and duplicates would inflate
     * the reward-group count a pricing check compares. A replayed create refreshes only the
     * linkage fields and never resets a distribution already recorded from the archive.
     */
    fun insertPending(
        distributionId: String,
        contractId: String,
        queryId: String?,
        transactionId: String?,
        escrow: String,
        contributorParties: List<String>,
        distributionDate: LocalDateTime
    ): Long = transaction {
        val existing = RewardSnapshotsTable
            .select { RewardSnapshotsTable.contractId eq contractId }
            .map { it[RewardSnapshotsTable.id].value }
            .firstOrNull()

        if (existing != null) {
            RewardSnapshotsTable.update({ RewardSnapshotsTable.contractId eq contractId }) {
                it[RewardSnapshotsTable.distributionId] = distributionId
                it[RewardSnapshotsTable.queryId] = queryId
                it[RewardSnapshotsTable.transactionId] = transactionId
                it[RewardSnapshotsTable.escrow] = escrow
                it[RewardSnapshotsTable.contributorPartiesJson] = json.encodeToString(contributorParties)
                it[RewardSnapshotsTable.distributionDate] = distributionDate
            }
            return@transaction existing
        }

        RewardSnapshotsTable.insertAndGetId {
            it[RewardSnapshotsTable.distributionId] = distributionId
            it[RewardSnapshotsTable.contractId] = contractId
            it[RewardSnapshotsTable.queryId] = queryId
            it[RewardSnapshotsTable.transactionId] = transactionId
            it[RewardSnapshotsTable.escrow] = escrow
            it[RewardSnapshotsTable.contributorPartiesJson] = json.encodeToString(contributorParties)
            it[RewardSnapshotsTable.totalAmount] = 0
            it[RewardSnapshotsTable.recipientsJson] = "[]"
            it[RewardSnapshotsTable.distributed] = false
            it[RewardSnapshotsTable.distributionDate] = distributionDate
            it[RewardSnapshotsTable.createdAt] = LocalDateTime.now()
        }.value
    }

    fun recordDistribution(contractId: String, recipients: List<RewardRecipient>) = transaction {
        RewardSnapshotsTable.update({ RewardSnapshotsTable.contractId eq contractId }) {
            it[totalAmount] = recipients.sumOf { r -> r.amount }
            it[recipientsJson] = json.encodeToString(recipients)
            it[distributed] = true
        }
    }

    fun findPendingByContractId(contractId: String): RewardSnapshotEntity? = transaction {
        RewardSnapshotsTable.select { RewardSnapshotsTable.contractId eq contractId }
            .map { it.toEntity() }
            .firstOrNull()
    }

    // One RewardGroup per query that matched the transaction, so prefer findByTransactionAndQuery.
    fun findByTransactionId(transactionId: String): RewardSnapshotEntity? = transaction {
        RewardSnapshotsTable.select {
            (RewardSnapshotsTable.transactionId eq transactionId) and (RewardSnapshotsTable.distributed eq true)
        }
            .orderBy(RewardSnapshotsTable.distributionDate, SortOrder.DESC)
            .map { it.toEntity() }
            .firstOrNull()
    }

    fun findByTransactionAndQuery(transactionId: String, queryId: String): RewardSnapshotEntity? = transaction {
        RewardSnapshotsTable.select {
            (RewardSnapshotsTable.transactionId eq transactionId) and
            (RewardSnapshotsTable.queryId eq queryId) and
            (RewardSnapshotsTable.distributed eq true)
        }
            .orderBy(RewardSnapshotsTable.distributionDate, SortOrder.DESC)
            .map { it.toEntity() }
            .firstOrNull()
    }

    // a group's share only makes sense against all the other groups of the same query
    fun findByQueryId(queryId: String): List<RewardSnapshotEntity> = transaction {
        RewardSnapshotsTable.select { RewardSnapshotsTable.queryId eq queryId }
            .orderBy(RewardSnapshotsTable.distributionId, SortOrder.ASC)
            .map { it.toEntity() }
    }

    fun findByGroupId(groupId: String): RewardSnapshotEntity? = transaction {
        RewardSnapshotsTable.select { RewardSnapshotsTable.distributionId eq groupId }
            .map { it.toEntity() }
            .firstOrNull()
    }

    private fun ResultRow.toEntity() = RewardSnapshotEntity(
        id = this[RewardSnapshotsTable.id].value,
        distributionId = this[RewardSnapshotsTable.distributionId],
        contractId = this[RewardSnapshotsTable.contractId],
        queryId = this[RewardSnapshotsTable.queryId],
        transactionId = this[RewardSnapshotsTable.transactionId],
        escrow = this[RewardSnapshotsTable.escrow],
        contributorParties = json.decodeFromString(this[RewardSnapshotsTable.contributorPartiesJson]),
        totalAmount = this[RewardSnapshotsTable.totalAmount],
        recipients = json.decodeFromString(this[RewardSnapshotsTable.recipientsJson]),
        distributed = this[RewardSnapshotsTable.distributed],
        distributionDate = this[RewardSnapshotsTable.distributionDate],
        createdAt = this[RewardSnapshotsTable.createdAt]
    )
}
