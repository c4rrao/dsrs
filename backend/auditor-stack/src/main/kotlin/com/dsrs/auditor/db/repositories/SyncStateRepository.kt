package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.SyncStateTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class SyncState(
    val lastOffset: String?,
    val status: String,
    val lastError: String?,
    val contractCount: Long,
    val lastSyncTime: LocalDateTime?
)

class SyncStateRepository {

    fun initializeIfNeeded() = transaction {
        val existing = SyncStateTable.select { SyncStateTable.key eq "last_offset" }.count()

        if (existing == 0L) {
            SyncStateTable.insert {
                it[key] = "last_offset"
                it[value] = ""
                it[updatedAt] = LocalDateTime.now()
            }
            SyncStateTable.insert {
                it[key] = "status"
                it[value] = "initialized"
                it[updatedAt] = LocalDateTime.now()
            }
            SyncStateTable.insert {
                it[key] = "contract_count"
                it[value] = "0"
                it[updatedAt] = LocalDateTime.now()
            }
        }
    }

    fun getState(): SyncState = transaction {
        val values = SyncStateTable.selectAll()
            .associate { it[SyncStateTable.key] to it[SyncStateTable.value] }

        SyncState(
            lastOffset = values["last_offset"]?.takeIf { it.isNotEmpty() },
            status = values["status"] ?: "unknown",
            lastError = values["last_error"],
            contractCount = values["contract_count"]?.toLongOrNull() ?: 0,
            lastSyncTime = values["last_sync_time"]?.let { LocalDateTime.parse(it) }
        )
    }

    fun updateOffset(offset: String, incrementCount: Int = 1) = transaction {
        val now = LocalDateTime.now()

        SyncStateTable.update({ SyncStateTable.key eq "last_offset" }) {
            it[value] = offset
            it[updatedAt] = now
        }

        val currentCount = SyncStateTable.select { SyncStateTable.key eq "contract_count" }
            .map { it[SyncStateTable.value].toLongOrNull() ?: 0 }
            .firstOrNull() ?: 0

        SyncStateTable.update({ SyncStateTable.key eq "contract_count" }) {
            it[value] = (currentCount + incrementCount).toString()
            it[updatedAt] = now
        }

        SyncStateTable.upsert(SyncStateTable.key) {
            it[key] = "last_sync_time"
            it[value] = now.toString()
            it[updatedAt] = now
        }
    }

    fun updateStatus(status: String, error: String? = null) = transaction {
        val now = LocalDateTime.now()

        SyncStateTable.update({ SyncStateTable.key eq "status" }) {
            it[value] = status
            it[updatedAt] = now
        }

        if (error != null) {
            SyncStateTable.upsert(SyncStateTable.key) {
                it[key] = "last_error"
                it[value] = error
                it[updatedAt] = now
            }
        }
    }
}
