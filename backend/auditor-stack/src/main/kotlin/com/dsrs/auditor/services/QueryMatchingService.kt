package com.dsrs.auditor.services

import com.dsrs.auditor.db.repositories.ContributionSnapshotEntity
import com.dsrs.common.types.PricingSelectivity
import com.dsrs.auditor.db.repositories.ContributionSnapshotRepository
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

// Same fields as PricingMetadata in Types.daml, so the two compare field by field.
data class PricingMetadataDto(
    val numTransactions: Long = 0,
    val corpusTransactions: Long = 0,
    val addressSelectivity: Double = 0.0,
    val salePriceSelectivity: Double = 0.0,
    val saleDateSelectivity: Double = 0.0,
    val squareMetersSelectivity: Double = 0.0,
    val yearBuiltSelectivity: Double = 0.0,
    val roomsSelectivity: Double = 0.0,
    val propertyTypeSelectivity: Double = 0.0,
    val energyRatingSelectivity: Double = 0.0,
    val tagsSelectivity: Double = 0.0,
    val trustQuality: Double = 0.0
)

data class MetadataDiscrepancy(
    val field: String,
    val claimed: String,
    val derived: String
)

data class QueryMatchResult(
    val snapshotTime: LocalDateTime,
    val matched: List<ContributionSnapshotEntity>,
    val derivedMetadata: PricingMetadataDto
) {
    val matchedTransactionIds: List<String> get() = matched.map { it.transactionId }
}

// The consumer's filter as recorded on the QueryRequest (HistorySynchronizer.serializeQueryParams).
data class AuditorQueryParams(
    val level: Int = 1,
    val trustScoreFrom: BigDecimal? = null,
    val trustScoreTo: BigDecimal? = null,
    val salePriceFrom: BigDecimal? = null,
    val salePriceTo: BigDecimal? = null,
    val saleDateFrom: LocalDate? = null,
    val saleDateTo: LocalDate? = null,
    val squareMetersFrom: BigDecimal? = null,
    val squareMetersTo: BigDecimal? = null,
    val yearBuiltFrom: Int? = null,
    val yearBuiltTo: Int? = null,
    val rooms: List<Int>? = null,
    val addresses: List<Map<String, String>>? = null,
    val propertyTypes: List<String>? = null,
    val energyRatings: List<String>? = null,
    val tags: List<String>? = null
)

/**
 * Re-executes a consumer's query over the auditor's own contribution history, so a pricing check
 * compares two independently derived sets of inputs instead of re-running the formula over the
 * operator's numbers. An inflated numTransactions priced correctly would otherwise pass.
 *
 * The filters re-implement the operator's InsightService instead of sharing code with it. They run
 * in memory: the auditor only matches on demand and does not need the operator's indexed schema.
 */
class QueryMatchingService(
    private val contributionSnapshotRepo: ContributionSnapshotRepository,
    private val json: Json
) {

    // snapshotTime is the query's pinned executionTimestamp, the only instant its result is
    // reproducible at
    fun replay(params: AuditorQueryParams, snapshotTime: LocalDateTime): QueryMatchResult {
        val candidates = contributionSnapshotRepo.findActiveAtTime(snapshotTime)
        val matched = candidates.filter { matches(it, params) }

        logger.info {
            "Replayed query at $snapshotTime: ${matched.size} of ${candidates.size} " +
                "contributions active at that time matched the consumer's filter"
        }

        return QueryMatchResult(
            snapshotTime = snapshotTime,
            matched = matched,
            derivedMetadata = derivePricingMetadata(params, matched, candidates)
        )
    }

    // only differing fields are returned
    fun compareMetadata(claimed: PricingMetadataDto, derived: PricingMetadataDto): List<MetadataDiscrepancy> {
        val checks = listOf<Triple<String, Any?, Any?>>(
            Triple("numTransactions", claimed.numTransactions, derived.numTransactions),
            Triple("corpusTransactions", claimed.corpusTransactions, derived.corpusTransactions),
            Triple("addressSelectivity", claimed.addressSelectivity, derived.addressSelectivity),
            Triple("salePriceSelectivity", claimed.salePriceSelectivity, derived.salePriceSelectivity),
            Triple("saleDateSelectivity", claimed.saleDateSelectivity, derived.saleDateSelectivity),
            Triple("squareMetersSelectivity", claimed.squareMetersSelectivity, derived.squareMetersSelectivity),
            Triple("yearBuiltSelectivity", claimed.yearBuiltSelectivity, derived.yearBuiltSelectivity),
            Triple("roomsSelectivity", claimed.roomsSelectivity, derived.roomsSelectivity),
            Triple("propertyTypeSelectivity", claimed.propertyTypeSelectivity, derived.propertyTypeSelectivity),
            Triple("energyRatingSelectivity", claimed.energyRatingSelectivity, derived.energyRatingSelectivity),
            Triple("tagsSelectivity", claimed.tagsSelectivity, derived.tagsSelectivity),
            Triple("trustQuality", claimed.trustQuality, derived.trustQuality)
        )
        return checks.mapNotNull { (field, a, b) ->
            val same = if (a is Double && b is Double) kotlin.math.abs(a - b) < 1e-6 else a == b
            if (same) null else MetadataDiscrepancy(field, a?.toString() ?: "-", b?.toString() ?: "-")
        }
    }

    fun parseParams(paramsJson: String): AuditorQueryParams {
        val map = try {
            json.decodeFromString<Map<String, String>>(paramsJson)
        } catch (e: Exception) {
            logger.warn { "Failed to parse query params, treating as unfiltered: $paramsJson" }
            return AuditorQueryParams()
        }

        fun range(key: String): Map<String, String> =
            map[key]?.let {
                try { json.decodeFromString<Map<String, String>>(it) } catch (_: Exception) { emptyMap() }
            } ?: emptyMap()

        fun list(key: String): List<String>? =
            map[key]?.let {
                try { json.decodeFromString<List<String>>(it) } catch (_: Exception) { null }
            }

        val trustScore = range("trustScore")
        val salePrice = range("salePrice")
        val saleDate = range("saleDate")
        val squareMeters = range("squareMeters")
        val yearBuilt = range("yearBuilt")

        val addresses = map["propertyAddress"]?.let {
            try { json.decodeFromString<List<Map<String, String>>>(it) } catch (_: Exception) { null }
        }

        return AuditorQueryParams(
            level = map["level"]?.toIntOrNull() ?: 1,
            trustScoreFrom = trustScore["from"]?.toBigDecimalOrNull(),
            trustScoreTo = trustScore["to"]?.toBigDecimalOrNull(),
            salePriceFrom = salePrice["from"]?.toBigDecimalOrNull(),
            salePriceTo = salePrice["to"]?.toBigDecimalOrNull(),
            saleDateFrom = saleDate["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            saleDateTo = saleDate["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            squareMetersFrom = squareMeters["from"]?.toBigDecimalOrNull(),
            squareMetersTo = squareMeters["to"]?.toBigDecimalOrNull(),
            yearBuiltFrom = yearBuilt["from"]?.toIntOrNull(),
            yearBuiltTo = yearBuilt["to"]?.toIntOrNull(),
            rooms = list("numberOfRooms")?.mapNotNull { it.toIntOrNull() },
            addresses = addresses,
            propertyTypes = list("propertyTypes"),
            energyRatings = list("energyRating"),
            tags = list("tags")
        )
    }

    private fun matches(snapshot: ContributionSnapshotEntity, params: AuditorQueryParams): Boolean {
        val data = parseContributionData(snapshot.contributionDataJson)

        // the trust score is on the snapshot row, not in the data blob
        params.trustScoreFrom?.let { if (snapshot.totalTrustScore < it) return false }
        params.trustScoreTo?.let { if (snapshot.totalTrustScore > it) return false }

        // like the operator: an OR over every city (and country) named, not whole addresses
        params.addresses?.takeIf { it.isNotEmpty() }?.let { addresses ->
            val cities = addresses.mapNotNull { it["city"] }
            val countries = addresses.mapNotNull { it["country"] }
            if (cities.isNotEmpty() && data.city !in cities) return false
            if (countries.isNotEmpty() && data.country !in countries) return false
        }

        params.propertyTypes?.takeIf { it.isNotEmpty() }?.let {
            if (data.propertyType !in it) return false
        }
        params.energyRatings?.takeIf { it.isNotEmpty() }?.let {
            if (data.energyRating !in it) return false
        }
        params.tags?.takeIf { it.isNotEmpty() }?.let { wanted ->
            if (data.tags.none { it in wanted }) return false
        }

        params.salePriceFrom?.let { if (data.salePrice == null || data.salePrice < it) return false }
        params.salePriceTo?.let { if (data.salePrice == null || data.salePrice > it) return false }

        params.saleDateFrom?.let { if (data.saleDate == null || data.saleDate < it) return false }
        params.saleDateTo?.let { if (data.saleDate == null || data.saleDate > it) return false }

        params.squareMetersFrom?.let { if (data.squareMeters == null || data.squareMeters < it) return false }
        params.squareMetersTo?.let { if (data.squareMeters == null || data.squareMeters > it) return false }

        params.yearBuiltFrom?.let { if (data.yearBuilt == null || data.yearBuilt < it) return false }
        params.yearBuiltTo?.let { if (data.yearBuilt == null || data.yearBuilt > it) return false }

        // a list applied as a min/max band, as the operator does
        params.rooms?.takeIf { it.isNotEmpty() }?.let { rooms ->
            val min = rooms.min()
            val max = rooms.max()
            if (data.numberOfRooms == null || data.numberOfRooms < min || data.numberOfRooms > max) return false
        }

        return true
    }

    // The corpus, never the matched set, is the denominator of every selectivity.
    private fun derivePricingMetadata(
        params: AuditorQueryParams,
        matched: List<ContributionSnapshotEntity>,
        corpus: List<ContributionSnapshotEntity>
    ): PricingMetadataDto {
        val corpusData = corpus.map { parseContributionData(it.contributionDataJson) }

        fun corpusRange(pick: (ContributionDataDto) -> Double?): Pair<Double?, Double?> {
            val vs = corpusData.mapNotNull(pick)
            return vs.minOrNull() to vs.maxOrNull()
        }
        fun rangeSel(lo: Double?, hi: Double?, pick: (ContributionDataDto) -> Double?): Double {
            if (lo == null && hi == null) return 0.0
            val (cl, ch) = corpusRange(pick)
            return PricingSelectivity.range(lo, hi, cl, ch)
        }

        val salePriceSelectivity = rangeSel(
            params.salePriceFrom?.toDouble(), params.salePriceTo?.toDouble()) { it.salePrice?.toDouble() }
        val squareMetersSelectivity = rangeSel(
            params.squareMetersFrom?.toDouble(), params.squareMetersTo?.toDouble()) { it.squareMeters?.toDouble() }
        val yearBuiltSelectivity = rangeSel(
            params.yearBuiltFrom?.toDouble(), params.yearBuiltTo?.toDouble()) { it.yearBuilt?.toDouble() }
        val saleDateSelectivity = rangeSel(
            params.saleDateFrom?.toEpochDay()?.toDouble(), params.saleDateTo?.toEpochDay()?.toDouble()
        ) { it.saleDate?.toEpochDay()?.toDouble() }
        val roomsSelectivity = params.rooms?.takeIf { it.isNotEmpty() }?.let { rooms ->
            rangeSel(rooms.min().toDouble(), rooms.max().toDouble()) { it.numberOfRooms?.toDouble() }
        } ?: 0.0

        val propertyTypeSelectivity = PricingSelectivity.list(
            params.propertyTypes?.size ?: 0, corpusData.mapNotNull { it.propertyType }.toSet().size)
        val energyRatingSelectivity = PricingSelectivity.list(
            params.energyRatings?.size ?: 0, corpusData.mapNotNull { it.energyRating }.toSet().size)
        val tagsSelectivity = PricingSelectivity.list(
            params.tags?.size ?: 0, corpusData.flatMap { it.tags }.toSet().size)

        val addressSelectivity = PricingSelectivity.address(
            params.addresses?.maxOfOrNull { a -> ADDRESS_FIELDS.count { a[it] != null } } ?: 0)

        val trustQuality = PricingSelectivity.trustQuality(
            matched.map { it.totalTrustScore.toDouble() },
            corpus.map { it.totalTrustScore.toDouble() }
        )

        return PricingMetadataDto(
            numTransactions = matched.size.toLong(),
            corpusTransactions = maxOf(corpus.size, matched.size).toLong(),
            addressSelectivity = addressSelectivity,
            salePriceSelectivity = salePriceSelectivity,
            saleDateSelectivity = saleDateSelectivity,
            squareMetersSelectivity = squareMetersSelectivity,
            yearBuiltSelectivity = yearBuiltSelectivity,
            roomsSelectivity = roomsSelectivity,
            propertyTypeSelectivity = propertyTypeSelectivity,
            energyRatingSelectivity = energyRatingSelectivity,
            tagsSelectivity = tagsSelectivity,
            trustQuality = trustQuality
        )
    }

    private fun parseContributionData(dataJson: String): ContributionDataDto {
        val map = try {
            json.decodeFromString<Map<String, String>>(dataJson)
        } catch (e: Exception) {
            logger.warn { "Failed to parse contribution data: $dataJson" }
            return ContributionDataDto()
        }

        return ContributionDataDto(
            salePrice = map["salePrice"]?.toBigDecimalOrNull(),
            saleDate = map["saleDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            propertyType = map["propertyType"],
            squareMeters = map["squareMeters"]?.toBigDecimalOrNull(),
            numberOfRooms = map["numberOfRooms"]?.toIntOrNull(),
            yearBuilt = map["yearBuilt"]?.toIntOrNull(),
            energyRating = map["energyRating"],
            tags = map["tags"]?.let {
                try { json.decodeFromString<List<String>>(it) } catch (_: Exception) { emptyList() }
            } ?: emptyList(),
            city = map["city"],
            country = map["country"]
        )
    }

    // the operator rounds to 10 decimals before the value reaches the ledger
    private fun scaled(value: Double): Double =
        BigDecimal(value).setScale(10, RoundingMode.HALF_UP).toDouble()

    private companion object {
        val ADDRESS_FIELDS = listOf("postalCode", "street", "city", "country", "additionalInfo")

        // the ledger holds these at 10 decimals, here they are Doubles
        const val RATIO_TOLERANCE = 1e-9
    }
}

data class ContributionDataDto(
    val salePrice: BigDecimal? = null,
    val saleDate: LocalDate? = null,
    val propertyType: String? = null,
    val squareMeters: BigDecimal? = null,
    val numberOfRooms: Int? = null,
    val yearBuilt: Int? = null,
    val energyRating: String? = null,
    val tags: List<String> = emptyList(),
    val city: String? = null,
    val country: String? = null
)
