package com.dsrs.operator.db.repositories

import com.dsrs.operator.db.SyncState
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class SyncStateEntity(
    val lastOffset: String?,
    val lastSyncTime: LocalDateTime?,
    val contractsSynced: Int,
    val syncStatus: String,
    val syncError: String?
)

class SyncStateRepository {

    fun getState(): SyncStateEntity = transaction {
        SyncState.selectAll().singleOrNull()?.toEntity()
            ?: SyncStateEntity(
                lastOffset = null,
                lastSyncTime = null,
                contractsSynced = 0,
                syncStatus = "idle",
                syncError = null
            )
    }

    fun initializeIfNeeded() = transaction {
        if (SyncState.selectAll().count() == 0L) {
            SyncState.insert {
                it[id] = 1
                it[lastOffset] = null
                it[lastSyncTime] = null
                it[contractsSynced] = 0
                it[syncStatus] = "idle"
                it[syncError] = null
            }
        }
    }

    fun updateOffset(offset: String, contractCount: Int) = transaction {
        SyncState.update({ SyncState.id eq 1 }) {
            it[lastOffset] = offset
            it[lastSyncTime] = LocalDateTime.now()
            it[contractsSynced] = contractCount
        }
    }

    fun updateStatus(status: String, error: String? = null) = transaction {
        SyncState.update({ SyncState.id eq 1 }) {
            it[syncStatus] = status
            it[syncError] = error
            if (status == "completed" || status == "error") {
                it[lastSyncTime] = LocalDateTime.now()
            }
        }
    }

    fun incrementContractsSynced() = transaction {
        SyncState.update({ SyncState.id eq 1 }) {
            with(SqlExpressionBuilder) {
                it[contractsSynced] = contractsSynced + 1
            }
        }
    }

    private fun ResultRow.toEntity(): SyncStateEntity {
        return SyncStateEntity(
            lastOffset = this[SyncState.lastOffset],
            lastSyncTime = this[SyncState.lastSyncTime],
            contractsSynced = this[SyncState.contractsSynced],
            syncStatus = this[SyncState.syncStatus],
            syncError = this[SyncState.syncError]
        )
    }
}
