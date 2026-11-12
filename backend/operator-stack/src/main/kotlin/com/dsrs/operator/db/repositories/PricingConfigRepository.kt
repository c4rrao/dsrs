package com.dsrs.operator.db.repositories

import com.dsrs.operator.db.PricingConfigs
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDateTime

data class PricingConfigEntity(
    val contractId: String,
    val version: Int,
    val basePrice: Long,
    val level1Multiplier: BigDecimal,
    val level2Multiplier: BigDecimal,
    val coverageWeight: BigDecimal,
    val coverageSaturation: BigDecimal,
    val narrownessWeight: BigDecimal,
    val trustWeight: BigDecimal,
    val createdAt: LocalDateTime
)

class PricingConfigRepository {

    // Idempotent, since a full resync on restart sees configs that are already stored.
    fun upsert(config: PricingConfigEntity) = transaction {
        val existing = PricingConfigs.select { PricingConfigs.contractId eq config.contractId }.singleOrNull()

        if (existing != null) {
            PricingConfigs.update({ PricingConfigs.contractId eq config.contractId }) {
                it[version] = config.version
                it[basePrice] = config.basePrice
                it[level1Multiplier] = config.level1Multiplier
                it[level2Multiplier] = config.level2Multiplier
                it[coverageWeight] = config.coverageWeight
                it[coverageSaturation] = config.coverageSaturation
                it[narrownessWeight] = config.narrownessWeight
                it[trustWeight] = config.trustWeight
                it[createdAt] = config.createdAt
            }
        } else {
            PricingConfigs.insert {
                it[contractId] = config.contractId
                it[version] = config.version
                it[basePrice] = config.basePrice
                it[level1Multiplier] = config.level1Multiplier
                it[level2Multiplier] = config.level2Multiplier
                it[coverageWeight] = config.coverageWeight
                it[coverageSaturation] = config.coverageSaturation
                it[narrownessWeight] = config.narrownessWeight
                it[trustWeight] = config.trustWeight
                it[createdAt] = config.createdAt
            }
        }
    }

    // The most recent config created at or before executionTime.
    fun findAtTime(executionTime: LocalDateTime): PricingConfigEntity? = transaction {
        PricingConfigs.select {
            PricingConfigs.createdAt lessEq executionTime
        }
        .orderBy(PricingConfigs.createdAt to SortOrder.DESC)
        .limit(1)
        .map { it.toEntity() }
        .firstOrNull()
    }

    fun findCurrent(): PricingConfigEntity? = transaction {
        PricingConfigs.selectAll()
            .orderBy(PricingConfigs.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it.toEntity() }
            .firstOrNull()
    }

    fun findByContractId(contractId: String): PricingConfigEntity? = transaction {
        PricingConfigs.select { PricingConfigs.contractId eq contractId }
            .map { it.toEntity() }
            .firstOrNull()
    }

    fun findAll(limit: Int = 100): List<PricingConfigEntity> = transaction {
        PricingConfigs.selectAll()
            .orderBy(PricingConfigs.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntity() }
    }

    private fun ResultRow.toEntity(): PricingConfigEntity {
        return PricingConfigEntity(
            contractId = this[PricingConfigs.contractId],
            version = this[PricingConfigs.version],
            basePrice = this[PricingConfigs.basePrice],
            level1Multiplier = this[PricingConfigs.level1Multiplier],
            level2Multiplier = this[PricingConfigs.level2Multiplier],
            coverageWeight = this[PricingConfigs.coverageWeight],
            coverageSaturation = this[PricingConfigs.coverageSaturation],
            narrownessWeight = this[PricingConfigs.narrownessWeight],
            trustWeight = this[PricingConfigs.trustWeight],
            createdAt = this[PricingConfigs.createdAt]
        )
    }
}
