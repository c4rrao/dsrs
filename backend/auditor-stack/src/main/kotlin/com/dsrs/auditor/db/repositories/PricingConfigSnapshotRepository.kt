package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.PricingConfigSnapshotsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDateTime

data class PricingConfigSnapshot(
    val id: Long? = null,
    val contractId: String,
    val version: Int,
    val basePrice: Long,
    val level1Multiplier: BigDecimal,
    val level2Multiplier: BigDecimal,
    val coverageWeight: BigDecimal,
    val coverageSaturation: BigDecimal,
    val narrownessWeight: BigDecimal,
    val trustWeight: BigDecimal,
    val validFrom: LocalDateTime,
    val validTo: LocalDateTime?,
    val createdAt: LocalDateTime
)

class PricingConfigSnapshotRepository {

    fun insert(snapshot: PricingConfigSnapshot) = transaction {
        PricingConfigSnapshotsTable.insert {
            it[contractId] = snapshot.contractId
            it[version] = snapshot.version
            it[basePrice] = snapshot.basePrice
            it[level1Multiplier] = snapshot.level1Multiplier
            it[level2Multiplier] = snapshot.level2Multiplier
            it[coverageWeight] = snapshot.coverageWeight
            it[coverageSaturation] = snapshot.coverageSaturation
            it[narrownessWeight] = snapshot.narrownessWeight
            it[trustWeight] = snapshot.trustWeight
            it[validFrom] = snapshot.validFrom
            it[validTo] = snapshot.validTo
            it[createdAt] = snapshot.createdAt
        }
    }

    // the most recent config with validFrom <= executionTime
    fun findAtTime(executionTime: LocalDateTime): PricingConfigSnapshot? = transaction {
        PricingConfigSnapshotsTable.select {
            (PricingConfigSnapshotsTable.validFrom lessEq executionTime) and
            ((PricingConfigSnapshotsTable.validTo.isNull()) or
             (PricingConfigSnapshotsTable.validTo greater executionTime))
        }
        .orderBy(PricingConfigSnapshotsTable.validFrom to SortOrder.DESC)
        .limit(1)
        .map { it.toSnapshot() }
        .firstOrNull()
    }

    fun findCurrent(): PricingConfigSnapshot? = transaction {
        PricingConfigSnapshotsTable.select {
            PricingConfigSnapshotsTable.validTo.isNull()
        }
        .map { it.toSnapshot() }
        .firstOrNull()
    }

    fun findByContractId(contractId: String): PricingConfigSnapshot? = transaction {
        PricingConfigSnapshotsTable.select {
            (PricingConfigSnapshotsTable.contractId eq contractId) and
            PricingConfigSnapshotsTable.validTo.isNull()
        }
        .map { it.toSnapshot() }
        .firstOrNull()
    }

    fun findAll(limit: Int = 100): List<PricingConfigSnapshot> = transaction {
        PricingConfigSnapshotsTable.selectAll()
            .orderBy(PricingConfigSnapshotsTable.validFrom to SortOrder.DESC)
            .limit(limit)
            .map { it.toSnapshot() }
    }

    fun closeCurrentVersion(contractId: String, validTo: LocalDateTime) = transaction {
        PricingConfigSnapshotsTable.update({
            (PricingConfigSnapshotsTable.contractId eq contractId) and
            PricingConfigSnapshotsTable.validTo.isNull()
        }) {
            it[PricingConfigSnapshotsTable.validTo] = validTo
        }
    }

    private fun ResultRow.toSnapshot(): PricingConfigSnapshot {
        return PricingConfigSnapshot(
            id = this[PricingConfigSnapshotsTable.id].value,
            contractId = this[PricingConfigSnapshotsTable.contractId],
            version = this[PricingConfigSnapshotsTable.version],
            basePrice = this[PricingConfigSnapshotsTable.basePrice],
            level1Multiplier = this[PricingConfigSnapshotsTable.level1Multiplier],
            level2Multiplier = this[PricingConfigSnapshotsTable.level2Multiplier],
            coverageWeight = this[PricingConfigSnapshotsTable.coverageWeight],
            coverageSaturation = this[PricingConfigSnapshotsTable.coverageSaturation],
            narrownessWeight = this[PricingConfigSnapshotsTable.narrownessWeight],
            trustWeight = this[PricingConfigSnapshotsTable.trustWeight],
            validFrom = this[PricingConfigSnapshotsTable.validFrom],
            validTo = this[PricingConfigSnapshotsTable.validTo],
            createdAt = this[PricingConfigSnapshotsTable.createdAt]
        )
    }
}
