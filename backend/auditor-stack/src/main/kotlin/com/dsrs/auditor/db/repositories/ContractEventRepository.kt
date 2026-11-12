package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.ContractEventsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class ContractEventEntity(
    val id: Long = 0,
    val eventType: String,
    val contractId: String,
    val templateName: String,
    val payload: String?,
    val offset: String,
    val timestamp: LocalDateTime
)

class ContractEventRepository {

    fun insert(event: ContractEventEntity): Long = transaction {
        ContractEventsTable.insertAndGetId {
            it[eventType] = event.eventType
            it[contractId] = event.contractId
            it[templateName] = event.templateName
            it[payload] = event.payload
            it[offset] = event.offset
            it[timestamp] = event.timestamp
        }.value
    }

    fun findByContractId(contractId: String): List<ContractEventEntity> = transaction {
        ContractEventsTable.select { ContractEventsTable.contractId eq contractId }
            .orderBy(ContractEventsTable.timestamp, SortOrder.ASC)
            .map { it.toEntity() }
    }

    fun findByTemplateName(templateName: String, limit: Int = 100): List<ContractEventEntity> = transaction {
        ContractEventsTable.select { ContractEventsTable.templateName eq templateName }
            .orderBy(ContractEventsTable.timestamp, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntity() }
    }

    fun findByTimeRange(
        fromTime: LocalDateTime,
        toTime: LocalDateTime,
        templateName: String? = null
    ): List<ContractEventEntity> = transaction {
        val query = ContractEventsTable.select {
            (ContractEventsTable.timestamp greaterEq fromTime) and
            (ContractEventsTable.timestamp lessEq toTime)
        }

        if (templateName != null) {
            query.andWhere { ContractEventsTable.templateName eq templateName }
        }

        query.orderBy(ContractEventsTable.timestamp, SortOrder.ASC)
            .map { it.toEntity() }
    }

    fun getLatestOffset(): String? = transaction {
        ContractEventsTable.selectAll()
            .orderBy(ContractEventsTable.id, SortOrder.DESC)
            .limit(1)
            .map { it[ContractEventsTable.offset] }
            .firstOrNull()
    }

    private fun ResultRow.toEntity() = ContractEventEntity(
        id = this[ContractEventsTable.id].value,
        eventType = this[ContractEventsTable.eventType],
        contractId = this[ContractEventsTable.contractId],
        templateName = this[ContractEventsTable.templateName],
        payload = this[ContractEventsTable.payload],
        offset = this[ContractEventsTable.offset],
        timestamp = this[ContractEventsTable.timestamp]
    )
}
