package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.MintObservationsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

const val AUDIT_TOKEN = "AUDITTOKEN"

data class MintObservationEntity(
    val id: Long = 0,
    val transactionOffset: String,
    val tokenType: String,
    val totalAmount: Long,
    val recipientsJson: String,
    val accompaniedBy: String? = null,
    val reason: String? = null,
    val sourceEventId: String? = null,
    val observedAt: LocalDateTime = LocalDateTime.now()
) {
    // Audit tokens are only earned inside workflows; a reward-token mint on its own is expected.
    val anomalous: Boolean get() = tokenType == AUDIT_TOKEN && accompaniedBy == null
}

class MintObservationRepository {

    // Idempotent on (offset, tokenType): offsets are re-read on every restart and resync.
    fun record(entity: MintObservationEntity): Unit = transaction {
        val existing = MintObservationsTable.select {
            (MintObservationsTable.transactionOffset eq entity.transactionOffset) and
                (MintObservationsTable.tokenType eq entity.tokenType)
        }.firstOrNull()
        if (existing != null) return@transaction

        MintObservationsTable.insert {
            it[transactionOffset] = entity.transactionOffset
            it[tokenType] = entity.tokenType
            it[totalAmount] = entity.totalAmount
            it[recipientsJson] = entity.recipientsJson
            it[accompaniedBy] = entity.accompaniedBy
            it[reason] = entity.reason
            it[sourceEventId] = entity.sourceEventId
            it[observedAt] = entity.observedAt
        }
    }

    fun findAll(limit: Int = 200): List<MintObservationEntity> = transaction {
        MintObservationsTable.selectAll()
            .orderBy(MintObservationsTable.observedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntity() }
    }

    fun findAnomalous(): List<MintObservationEntity> = transaction {
        MintObservationsTable.select {
            (MintObservationsTable.tokenType eq AUDIT_TOKEN) and
                MintObservationsTable.accompaniedBy.isNull()
        }
            .orderBy(MintObservationsTable.observedAt, SortOrder.DESC)
            .map { it.toEntity() }
    }

    // supply per token type, independent of anything the operator reports
    fun mintedByTokenType(): Map<String, Long> = transaction {
        val amount = MintObservationsTable.totalAmount.sum()
        MintObservationsTable
            .slice(MintObservationsTable.tokenType, amount)
            .selectAll()
            .groupBy(MintObservationsTable.tokenType)
            .associate { it[MintObservationsTable.tokenType] to (it[amount] ?: 0L) }
    }

    private fun ResultRow.toEntity() = MintObservationEntity(
        id = this[MintObservationsTable.id].value,
        transactionOffset = this[MintObservationsTable.transactionOffset],
        tokenType = this[MintObservationsTable.tokenType],
        totalAmount = this[MintObservationsTable.totalAmount],
        recipientsJson = this[MintObservationsTable.recipientsJson],
        accompaniedBy = this[MintObservationsTable.accompaniedBy],
        reason = this[MintObservationsTable.reason],
        sourceEventId = this[MintObservationsTable.sourceEventId],
        observedAt = this[MintObservationsTable.observedAt]
    )
}
