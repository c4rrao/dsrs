package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.AuditLogsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class AuditLogEntity(
    val id: Long = 0,
    val requestType: String,
    val requestParams: String,
    val result: String,
    val matches: Boolean,
    val requesterParty: String? = null,
    val tokensCost: Long = 0,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

class AuditLogRepository {

    fun insert(log: AuditLogEntity): Long = transaction {
        AuditLogsTable.insertAndGetId {
            it[requestType] = log.requestType
            it[requestParams] = log.requestParams
            it[result] = log.result
            it[matches] = log.matches
            it[requesterParty] = log.requesterParty
            it[tokensCost] = log.tokensCost
            it[createdAt] = log.createdAt
        }.value
    }

    fun findByRequestType(requestType: String, limit: Int = 100): List<AuditLogEntity> = transaction {
        AuditLogsTable.select { AuditLogsTable.requestType eq requestType }
            .orderBy(AuditLogsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntity() }
    }

    fun findByRequester(requesterParty: String, limit: Int = 100): List<AuditLogEntity> = transaction {
        AuditLogsTable.select { AuditLogsTable.requesterParty eq requesterParty }
            .orderBy(AuditLogsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntity() }
    }

    fun findFailedVerifications(limit: Int = 100): List<AuditLogEntity> = transaction {
        AuditLogsTable.select { AuditLogsTable.matches eq false }
            .orderBy(AuditLogsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntity() }
    }

    fun getStatistics(): AuditStatistics = transaction {
        val total = AuditLogsTable.selectAll().count()
        val failed = AuditLogsTable.select { AuditLogsTable.matches eq false }.count()
        val countExpr = AuditLogsTable.id.count()
        val byType = AuditLogsTable
            .slice(AuditLogsTable.requestType, countExpr)
            .selectAll()
            .groupBy(AuditLogsTable.requestType)
            .associate { it[AuditLogsTable.requestType] to it[countExpr] }

        AuditStatistics(
            totalRequests = total,
            failedVerifications = failed,
            requestsByType = byType
        )
    }

    private fun ResultRow.toEntity() = AuditLogEntity(
        id = this[AuditLogsTable.id].value,
        requestType = this[AuditLogsTable.requestType],
        requestParams = this[AuditLogsTable.requestParams],
        result = this[AuditLogsTable.result],
        matches = this[AuditLogsTable.matches],
        requesterParty = this[AuditLogsTable.requesterParty],
        tokensCost = this[AuditLogsTable.tokensCost],
        createdAt = this[AuditLogsTable.createdAt]
    )
}

data class AuditStatistics(
    val totalRequests: Long,
    val failedVerifications: Long,
    val requestsByType: Map<String, Long>
)
