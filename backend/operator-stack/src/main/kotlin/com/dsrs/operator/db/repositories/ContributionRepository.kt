package com.dsrs.operator.db.repositories

import com.dsrs.operator.db.Contributions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ContributionEntity(
    val contractId: String,
    val contributionId: String,
    val transactionId: String,
    val state: String = "active",
    val status: String = "confirmed",
    val contributionDataJson: String,
    val contributorsDataJson: String,
    val contributorCount: Int,
    val trustScoreJson: String,
    val totalTrustScore: BigDecimal,
    val originalSubmissionDate: LocalDateTime,
    val disputeDatesJson: String,
    val propertyCity: String?,
    val propertyCountry: String?,
    val propertyPostalCode: String?,
    val salePrice: BigDecimal?,
    val saleDate: LocalDate?,
    val propertyType: String?,
    val squareMeters: BigDecimal?,
    val numberOfRooms: Int?,
    val yearBuilt: Int?,
    val energyRating: String?,
    val tagsJson: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val archivedAt: LocalDateTime? = null
) {
    val isPending: Boolean get() = status == "pending"
    val isConfirmed: Boolean get() = status == "confirmed"
    val isActive: Boolean get() = state == "active"
    val isInDispute: Boolean get() = state == "in_dispute"
    val isArchived: Boolean get() = state == "archived"
}

class ContributionRepository(private val json: Json = Json { ignoreUnknownKeys = true }) {

    fun upsert(entity: ContributionEntity) = transaction {
        // looked up by transactionId so a pending row can become confirmed
        val existing = Contributions.select { Contributions.transactionId eq entity.transactionId }.singleOrNull()

        if (existing != null) {
            Contributions.update({ Contributions.transactionId eq entity.transactionId }) {
                it[contractId] = entity.contractId
                it[contributionId] = entity.contributionId
                it[state] = entity.state
                it[status] = entity.status
                it[contributionData] = entity.contributionDataJson
                it[contributorsData] = entity.contributorsDataJson
                it[contributorCount] = entity.contributorCount
                it[trustScore] = entity.trustScoreJson
                it[totalTrustScore] = entity.totalTrustScore
                it[originalSubmissionDate] = entity.originalSubmissionDate
                it[disputeDates] = entity.disputeDatesJson
                it[propertyCity] = entity.propertyCity
                it[propertyCountry] = entity.propertyCountry
                it[propertyPostalCode] = entity.propertyPostalCode
                it[salePrice] = entity.salePrice
                it[saleDate] = entity.saleDate
                it[propertyType] = entity.propertyType
                it[squareMeters] = entity.squareMeters
                it[numberOfRooms] = entity.numberOfRooms
                it[yearBuilt] = entity.yearBuilt
                it[energyRating] = entity.energyRating
                it[tags] = entity.tagsJson
                it[archivedAt] = entity.archivedAt
                it[updatedAt] = LocalDateTime.now()
            }
        } else {
            Contributions.insert {
                it[contractId] = entity.contractId
                it[contributionId] = entity.contributionId
                it[transactionId] = entity.transactionId
                it[state] = entity.state
                it[status] = entity.status
                it[contributionData] = entity.contributionDataJson
                it[contributorsData] = entity.contributorsDataJson
                it[contributorCount] = entity.contributorCount
                it[trustScore] = entity.trustScoreJson
                it[totalTrustScore] = entity.totalTrustScore
                it[originalSubmissionDate] = entity.originalSubmissionDate
                it[disputeDates] = entity.disputeDatesJson
                it[propertyCity] = entity.propertyCity
                it[propertyCountry] = entity.propertyCountry
                it[propertyPostalCode] = entity.propertyPostalCode
                it[salePrice] = entity.salePrice
                it[saleDate] = entity.saleDate
                it[propertyType] = entity.propertyType
                it[squareMeters] = entity.squareMeters
                it[numberOfRooms] = entity.numberOfRooms
                it[yearBuilt] = entity.yearBuilt
                it[energyRating] = entity.energyRating
                it[tags] = entity.tagsJson
                it[archivedAt] = entity.archivedAt
                it[createdAt] = entity.createdAt
                it[updatedAt] = entity.updatedAt
            }
        }
    }

    fun confirmPending(transactionId: String, realContractId: String) = transaction {
        Contributions.update({ Contributions.transactionId eq transactionId }) {
            it[contractId] = realContractId
            it[status] = "confirmed"
            it[updatedAt] = LocalDateTime.now()
        }
    }

    fun findByContractId(contractId: String): ContributionEntity? = transaction {
        Contributions.select { Contributions.contractId eq contractId }
            .singleOrNull()
            ?.toEntity()
    }

    fun findByTransactionId(transactionId: String): ContributionEntity? = transaction {
        Contributions.select { Contributions.transactionId eq transactionId }
            .singleOrNull()
            ?.toEntity()
    }

    fun findAll(limit: Int = 100, offset: Long = 0, includesPending: Boolean = false): List<ContributionEntity> = transaction {
        val query = if (includesPending) {
            Contributions.selectAll()
        } else {
            Contributions.select { Contributions.status eq "confirmed" }
        }
        query.orderBy(Contributions.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toEntity() }
    }

    fun delete(contractId: String) = transaction {
        Contributions.deleteWhere { Contributions.contractId eq contractId }
    }

    fun count(): Long = transaction {
        Contributions.selectAll().count()
    }

    /**
     * Only used by tests. Consumer queries go through queryContributionsAtTime, which applies
     * the corroboration filter; this one does not.
     */
    fun queryContributions(
        cities: List<String>? = null,
        countries: List<String>? = null,
        propertyTypes: List<String>? = null,
        energyRatings: List<String>? = null,
        tags: List<String>? = null,
        minPrice: BigDecimal? = null,
        maxPrice: BigDecimal? = null,
        minDate: LocalDate? = null,
        maxDate: LocalDate? = null,
        minTrustScore: BigDecimal? = null,
        maxTrustScore: BigDecimal? = null,
        minSquareMeters: BigDecimal? = null,
        maxSquareMeters: BigDecimal? = null,
        minYearBuilt: Int? = null,
        maxYearBuilt: Int? = null,
        minRooms: Int? = null,
        maxRooms: Int? = null,
        // No default limit: a truncated match set would be priced, while the auditor re-derives
        // the full one and reports a mismatch against an honest operator.
        limit: Int? = null
    ): List<ContributionEntity> = transaction {
        Contributions.select { Contributions.status eq "confirmed" }.apply {
            cities?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.propertyCity inList it }
            }
            countries?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.propertyCountry inList it }
            }
            propertyTypes?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.propertyType inList it }
            }
            energyRatings?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.energyRating inList it }
            }
            tags?.takeIf { it.isNotEmpty() }?.let { wanted ->
                andWhere { anyTagMatches(wanted) }
            }
            minPrice?.let { andWhere { Contributions.salePrice greaterEq it } }
            maxPrice?.let { andWhere { Contributions.salePrice lessEq it } }
            minDate?.let { andWhere { Contributions.saleDate greaterEq it } }
            maxDate?.let { andWhere { Contributions.saleDate lessEq it } }
            minTrustScore?.let { andWhere { Contributions.totalTrustScore greaterEq it } }
            maxTrustScore?.let { andWhere { Contributions.totalTrustScore lessEq it } }
            minSquareMeters?.let { andWhere { Contributions.squareMeters greaterEq it } }
            maxSquareMeters?.let { andWhere { Contributions.squareMeters lessEq it } }
            minYearBuilt?.let { andWhere { Contributions.yearBuilt greaterEq it } }
            maxYearBuilt?.let { andWhere { Contributions.yearBuilt lessEq it } }
            minRooms?.let { andWhere { Contributions.numberOfRooms greaterEq it } }
            maxRooms?.let { andWhere { Contributions.numberOfRooms lessEq it } }
        }.let { q -> limit?.let { q.limit(it) } ?: q }.map { it.toEntity() }
    }

    fun updateState(contractId: String, newState: String) = transaction {
        Contributions.update({ Contributions.contractId eq contractId }) {
            it[state] = newState
            it[updatedAt] = LocalDateTime.now()
        }
    }

    fun archiveContribution(contractId: String, archivedTime: LocalDateTime = LocalDateTime.now()) = transaction {
        Contributions.update({ Contributions.contractId eq contractId }) {
            it[state] = "archived"
            it[archivedAt] = archivedTime
            it[updatedAt] = LocalDateTime.now()
        }
    }

    /**
     * The corpus as it stood at snapshotTime, restricted to corroborated contributions. Both the
     * matched set (with the consumer's filter) and the corpus size used for coverage (without)
     * come from here, so an uncorroborated record is neither sold nor counted in n/N.
     */
    fun queryContributionsAtTime(
        snapshotTime: LocalDateTime,
        cities: List<String>? = null,
        countries: List<String>? = null,
        propertyTypes: List<String>? = null,
        energyRatings: List<String>? = null,
        tags: List<String>? = null,
        minPrice: BigDecimal? = null,
        maxPrice: BigDecimal? = null,
        minDate: LocalDate? = null,
        maxDate: LocalDate? = null,
        minTrustScore: BigDecimal? = null,
        maxTrustScore: BigDecimal? = null,
        minSquareMeters: BigDecimal? = null,
        maxSquareMeters: BigDecimal? = null,
        minYearBuilt: Int? = null,
        maxYearBuilt: Int? = null,
        minRooms: Int? = null,
        maxRooms: Int? = null,
        // No default limit: a truncated match set would be priced, while the auditor re-derives
        // the full one and reports a mismatch against an honest operator.
        limit: Int? = null
    ): List<ContributionEntity> = transaction {
        Contributions.select {
            (Contributions.state eq "active") and
            (Contributions.createdAt lessEq snapshotTime) and
            (Contributions.status eq "confirmed") and
            // Corroboration filter, in SQL so that `limit` counts sellable rows. The auditor
            // applies the same filter, or every pricing audit would report a mismatch.
            (Contributions.contributorCount greater 1)
        }.apply {
            cities?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.propertyCity inList it }
            }
            countries?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.propertyCountry inList it }
            }
            propertyTypes?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.propertyType inList it }
            }
            energyRatings?.takeIf { it.isNotEmpty() }?.let {
                andWhere { Contributions.energyRating inList it }
            }
            tags?.takeIf { it.isNotEmpty() }?.let { wanted ->
                andWhere { anyTagMatches(wanted) }
            }
            minPrice?.let { andWhere { Contributions.salePrice greaterEq it } }
            maxPrice?.let { andWhere { Contributions.salePrice lessEq it } }
            minDate?.let { andWhere { Contributions.saleDate greaterEq it } }
            maxDate?.let { andWhere { Contributions.saleDate lessEq it } }
            minTrustScore?.let { andWhere { Contributions.totalTrustScore greaterEq it } }
            maxTrustScore?.let { andWhere { Contributions.totalTrustScore lessEq it } }
            minSquareMeters?.let { andWhere { Contributions.squareMeters greaterEq it } }
            maxSquareMeters?.let { andWhere { Contributions.squareMeters lessEq it } }
            minYearBuilt?.let { andWhere { Contributions.yearBuilt greaterEq it } }
            maxYearBuilt?.let { andWhere { Contributions.yearBuilt lessEq it } }
            minRooms?.let { andWhere { Contributions.numberOfRooms greaterEq it } }
            maxRooms?.let { andWhere { Contributions.numberOfRooms lessEq it } }
        }.let { q -> limit?.let { q.limit(it) } ?: q }.map { it.toEntity() }
    }

    // Tags are stored as a JSON array, so matching the quoted token keeps "balcony" from
    // matching "balcony-view".
    private fun SqlExpressionBuilder.anyTagMatches(wanted: List<String>): Op<Boolean> =
        wanted.map { tag -> Contributions.tags like "%\"$tag\"%" }
            .reduce<Op<Boolean>, Op<Boolean>> { acc, op -> acc or op }

    private fun ResultRow.toEntity(): ContributionEntity {
        return ContributionEntity(
            contractId = this[Contributions.contractId],
            contributionId = this[Contributions.contributionId],
            transactionId = this[Contributions.transactionId],
            state = this[Contributions.state],
            status = this[Contributions.status],
            contributionDataJson = this[Contributions.contributionData],
            contributorsDataJson = this[Contributions.contributorsData],
            contributorCount = this[Contributions.contributorCount],
            trustScoreJson = this[Contributions.trustScore],
            totalTrustScore = this[Contributions.totalTrustScore],
            originalSubmissionDate = this[Contributions.originalSubmissionDate],
            disputeDatesJson = this[Contributions.disputeDates],
            propertyCity = this[Contributions.propertyCity],
            propertyCountry = this[Contributions.propertyCountry],
            propertyPostalCode = this[Contributions.propertyPostalCode],
            salePrice = this[Contributions.salePrice],
            saleDate = this[Contributions.saleDate],
            propertyType = this[Contributions.propertyType],
            squareMeters = this[Contributions.squareMeters],
            numberOfRooms = this[Contributions.numberOfRooms],
            yearBuilt = this[Contributions.yearBuilt],
            energyRating = this[Contributions.energyRating],
            tagsJson = this[Contributions.tags],
            createdAt = this[Contributions.createdAt],
            updatedAt = this[Contributions.updatedAt],
            archivedAt = this[Contributions.archivedAt]
        )
    }
}
