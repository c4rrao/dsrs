package com.dsrs.operator.db.repositories

import com.dsrs.operator.db.ProcessingLog
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class ProcessingLogEntry(
    val id: Long? = null,
    val eventType: String,
    val contractId: String,
    val action: String,
    val status: String,
    val details: String? = null,
    val errorMessage: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

class ProcessingLogRepository {

    fun log(entry: ProcessingLogEntry) = transaction {
        ProcessingLog.insert {
            it[eventType] = entry.eventType
            it[contractId] = entry.contractId
            it[action] = entry.action
            it[status] = entry.status
            it[details] = entry.details
            it[errorMessage] = entry.errorMessage
            it[createdAt] = entry.createdAt
        }
    }

    fun logSuccess(eventType: String, contractId: String, action: String, details: String? = null) {
        log(ProcessingLogEntry(
            eventType = eventType,
            contractId = contractId,
            action = action,
            status = "success",
            details = details
        ))
    }

    fun logError(eventType: String, contractId: String, action: String, errorMessage: String) {
        log(ProcessingLogEntry(
            eventType = eventType,
            contractId = contractId,
            action = action,
            status = "error",
            errorMessage = errorMessage
        ))
    }

    fun findRecent(limit: Int = 100): List<ProcessingLogEntry> = transaction {
        ProcessingLog.selectAll()
            .orderBy(ProcessingLog.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
    }

    fun findByContractId(contractId: String, limit: Int = 50): List<ProcessingLogEntry> = transaction {
        ProcessingLog.select { ProcessingLog.contractId eq contractId }
            .orderBy(ProcessingLog.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
    }

    fun findByEventType(eventType: String, limit: Int = 100): List<ProcessingLogEntry> = transaction {
        ProcessingLog.select { ProcessingLog.eventType eq eventType }
            .orderBy(ProcessingLog.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
    }

    fun countByStatus(status: String): Long = transaction {
        ProcessingLog.select { ProcessingLog.status eq status }.count()
    }

    private fun ResultRow.toEntry(): ProcessingLogEntry {
        return ProcessingLogEntry(
            id = this[ProcessingLog.id],
            eventType = this[ProcessingLog.eventType],
            contractId = this[ProcessingLog.contractId],
            action = this[ProcessingLog.action],
            status = this[ProcessingLog.status],
            details = this[ProcessingLog.details],
            errorMessage = this[ProcessingLog.errorMessage],
            createdAt = this[ProcessingLog.createdAt]
        )
    }
}
