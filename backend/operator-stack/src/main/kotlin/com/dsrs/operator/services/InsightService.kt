package com.dsrs.operator.services

import com.dsrs.daml.da.types.Tuple2
import com.dsrs.daml.types.DetailedMetrics
import com.dsrs.daml.types.EnergyRating
import com.dsrs.daml.types.Insight
import com.dsrs.daml.types.InsightBase
import com.dsrs.daml.types.PricingMetadata
import com.dsrs.daml.types.Range
import com.dsrs.daml.types.insight.Level1
import com.dsrs.daml.types.insight.Level2
import com.dsrs.common.types.PricingSelectivity
import com.dsrs.operator.db.repositories.ContributionEntity
import com.dsrs.operator.db.repositories.ContributionRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Optional

private val logger = KotlinLogging.logger {}

data class QueryExecutionResult(
    val contributions: List<ContributionEntity>,
    val insights: List<Insight>,
    val executionTimestamp: Instant
)

class InsightService(
    private val contributionRepo: ContributionRepository
) {
    @Suppress("UNCHECKED_CAST")
    fun executeQuery(
        params: Map<String, Any?>,
        level: Int = 1,
        executionTime: Instant = Instant.now()
    ): QueryExecutionResult {
        val snapshotTime = executionTime
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()

        logger.info { "Executing query at time $executionTime with snapshot $snapshotTime" }

        val addresses = params["propertyAddress"] as? List<Map<String, Any?>>
        val cities = addresses?.mapNotNull { it["city"] as? String }
        val countries = addresses?.mapNotNull { it["country"] as? String }

        val trustScoreRange = params["trustScore"] as? Map<String, Any?>
        val minTrustScore = (trustScoreRange?.get("from") as? Number)?.let { BigDecimal(it.toString()) }
        val maxTrustScore = (trustScoreRange?.get("to") as? Number)?.let { BigDecimal(it.toString()) }

        val salePriceRange = params["salePrice"] as? Map<String, Any?>
        val minPrice = (salePriceRange?.get("from") as? Number)?.let { BigDecimal(it.toString()) }
        val maxPrice = (salePriceRange?.get("to") as? Number)?.let { BigDecimal(it.toString()) }

        val saleDateRange = params["saleDate"] as? Map<String, Any?>
        val minDate = (saleDateRange?.get("from") as? String)?.let { LocalDate.parse(it) }
        val maxDate = (saleDateRange?.get("to") as? String)?.let { LocalDate.parse(it) }

        val propertyTypes = params["propertyTypes"] as? List<String>
        val energyRatings = params["energyRating"] as? List<String>
        val tags = params["tags"] as? List<String>

        val sqmRange = params["squareMeters"] as? Map<String, Any?>
        val minSqm = (sqmRange?.get("from") as? Number)?.let { BigDecimal(it.toString()) }
        val maxSqm = (sqmRange?.get("to") as? Number)?.let { BigDecimal(it.toString()) }

        val yearBuiltRange = params["yearBuilt"] as? Map<String, Any?>
        val minYear = (yearBuiltRange?.get("from") as? Number)?.toInt()
        val maxYear = (yearBuiltRange?.get("to") as? Number)?.toInt()

        val roomsRange = params["numberOfRooms"] as? List<Int>
        val minRooms = roomsRange?.minOrNull()
        val maxRooms = roomsRange?.maxOrNull()

        val contributions = contributionRepo.queryContributionsAtTime(
            snapshotTime = snapshotTime,
            cities = cities,
            countries = countries,
            propertyTypes = propertyTypes,
            energyRatings = energyRatings,
            tags = tags,
            minPrice = minPrice,
            maxPrice = maxPrice,
            minDate = minDate,
            maxDate = maxDate,
            minTrustScore = minTrustScore,
            maxTrustScore = maxTrustScore,
            minSquareMeters = minSqm,
            maxSquareMeters = maxSqm,
            minYearBuilt = minYear,
            maxYearBuilt = maxYear,
            minRooms = minRooms,
            maxRooms = maxRooms
        )

        logger.info { "Query returned ${contributions.size} contributions from snapshot" }

        val insights = listOf(computeInsight(contributions, level))

        return QueryExecutionResult(
            contributions = contributions,
            insights = insights,
            executionTimestamp = executionTime
        )
    }

    private fun computeInsight(contributions: List<ContributionEntity>, level: Int): Insight {
        val base = computeBaseInsight(contributions)

        return if (level >= 2) {
            val details = computeDetailedMetrics(contributions)
            Level2(base, details)
        } else {
            Level1(base)
        }
    }

    private fun computeBaseInsight(contributions: List<ContributionEntity>): InsightBase {
        if (contributions.isEmpty()) {
            return InsightBase(
                0L,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            )
        }

        val prices = contributions.mapNotNull { it.salePrice }
        val trustScores = contributions.map { it.totalTrustScore }
        val years = contributions.mapNotNull { it.yearBuilt }
        val sqms = contributions.mapNotNull { it.squareMeters }
        val rooms = contributions.mapNotNull { it.numberOfRooms }
        val ratings = contributions.mapNotNull { it.energyRating }

        return InsightBase(
            contributions.size.toLong(),
            Optional.ofNullable(if (prices.isNotEmpty()) average(prices) else null),
            Optional.ofNullable(if (trustScores.isNotEmpty()) average(trustScores) else null),
            Optional.ofNullable(if (years.isNotEmpty()) mean(years) else null),
            Optional.ofNullable(if (sqms.isNotEmpty()) average(sqms) else null),
            Optional.ofNullable(if (rooms.isNotEmpty()) mean(rooms) else null),
            Optional.ofNullable(median(ratings)?.let { parseEnergyRating(it) })
        )
    }

    private fun computeDetailedMetrics(contributions: List<ContributionEntity>): DetailedMetrics {
        val prices = contributions.mapNotNull { it.salePrice }
        val sqms = contributions.mapNotNull { it.squareMeters }
        val years = contributions.mapNotNull { it.yearBuilt }
        val rooms = contributions.mapNotNull { it.numberOfRooms }
        val dates = contributions.mapNotNull { it.saleDate }
        val types = contributions.mapNotNull { it.propertyType }
        val ratings = contributions.mapNotNull { it.energyRating }
        val cities = contributions.mapNotNull { it.propertyCity }
        val allTags = contributions.flatMap { c ->
            c.tagsJson?.let { try { Json.decodeFromString<List<String>>(it) } catch (e: Exception) { emptyList() } } ?: emptyList()
        }

        val pricesPerSqm = contributions.mapNotNull { c ->
            if (c.salePrice != null && c.squareMeters != null && c.squareMeters > BigDecimal.ZERO) {
                c.salePrice.divide(c.squareMeters, 2, RoundingMode.HALF_UP)
            } else null
        }

        val contributorCounts = contributions.map { c ->
            try { Json.decodeFromString<JsonObject>(c.contributorsDataJson).size } catch (e: Exception) { 1 }
        }

        // disputeDatesJson has one entry per dispute
        val disputeCounts = contributions.map { c ->
            try { Json.decodeFromString<JsonArray>(c.disputeDatesJson).size } catch (e: Exception) { 0 }
        }
        val disputedCount = disputeCounts.count { it > 0 }

        return DetailedMetrics(
            toDecimalRange(if (prices.isNotEmpty()) prices.minOrNull() to prices.maxOrNull() else null),
            Optional.ofNullable(if (prices.size > 1) variance(prices) else null),
            Optional.ofNullable(if (prices.isNotEmpty()) medianDecimal(prices) else null),
            Optional.ofNullable(if (pricesPerSqm.isNotEmpty()) average(pricesPerSqm) else null),
            toDecimalRange(if (pricesPerSqm.isNotEmpty()) pricesPerSqm.minOrNull() to pricesPerSqm.maxOrNull() else null),
            toLongRange(if (years.isNotEmpty()) years.minOrNull() to years.maxOrNull() else null),
            toDecimalRange(if (sqms.isNotEmpty()) sqms.minOrNull() to sqms.maxOrNull() else null),
            Optional.ofNullable(if (sqms.size > 1) variance(sqms) else null),
            toLongRange(if (rooms.isNotEmpty()) rooms.minOrNull() to rooms.maxOrNull() else null),
            toStringLongTuple2List(distribution(types)),
            toEnergyRatingTuple2List(distribution(ratings)),
            toDateRange(if (dates.isNotEmpty()) dates.minOrNull() to dates.maxOrNull() else null),
            toStringLongTuple2List(distribution(cities)),
            toStringLongTuple2List(distribution(allTags).take(10)),
            Optional.ofNullable(if (contributorCounts.isNotEmpty()) mean(contributorCounts) else null),
            Optional.ofNullable(if (disputeCounts.isNotEmpty()) mean(disputeCounts) else null),
            Optional.ofNullable(
                if (contributions.isNotEmpty())
                    BigDecimal(disputedCount).divide(BigDecimal(contributions.size), 4, RoundingMode.HALF_UP)
                else null
            )
        )
    }

    private fun toDecimalRange(pair: Pair<BigDecimal?, BigDecimal?>?): Optional<Range<BigDecimal>> =
        Optional.ofNullable(pair?.let {
            Range(Optional.ofNullable(it.first), Optional.ofNullable(it.second))
        })

    private fun toLongRange(pair: Pair<Int?, Int?>?): Optional<Range<Long>> =
        Optional.ofNullable(pair?.let {
            Range(Optional.ofNullable(it.first?.toLong()), Optional.ofNullable(it.second?.toLong()))
        })

    private fun toDateRange(pair: Pair<LocalDate?, LocalDate?>?): Optional<Range<LocalDate>> =
        Optional.ofNullable(pair?.let {
            Range(Optional.ofNullable(it.first), Optional.ofNullable(it.second))
        })

    private fun toStringLongTuple2List(pairs: List<Pair<String, Int>>?): Optional<List<Tuple2<String, Long>>> =
        Optional.ofNullable(pairs?.takeIf { it.isNotEmpty() }?.map { Tuple2(it.first, it.second.toLong()) })

    private fun toEnergyRatingTuple2List(pairs: List<Pair<String, Int>>?): Optional<List<Tuple2<EnergyRating, Long>>> =
        Optional.ofNullable(pairs?.takeIf { it.isNotEmpty() }?.mapNotNull { pair ->
            parseEnergyRating(pair.first)?.let { rating -> Tuple2(rating, pair.second.toLong()) }
        }?.takeIf { it.isNotEmpty() })

    private fun parseEnergyRating(rating: String): EnergyRating? =
        try { EnergyRating.valueOf(rating) } catch (_: Exception) { null }

    // Selectivities are measured against the whole corpus at the same instant, never against the
    // matched set, which the filter itself defines.
    @Suppress("UNCHECKED_CAST")
    fun extractPricingMetadata(
        params: Map<String, Any?>,
        contributions: List<ContributionEntity>,
        corpus: List<ContributionEntity>
    ): PricingMetadata {

        fun rangeParam(key: String): Pair<Double?, Double?>? {
            val m = params[key] as? Map<String, Any?> ?: return null
            return (m["from"] as? Number)?.toDouble() to (m["to"] as? Number)?.toDouble()
        }
        fun corpusRange(sel: (ContributionEntity) -> Double?): Pair<Double?, Double?> {
            val vs = corpus.mapNotNull(sel)
            return (vs.minOrNull() to vs.maxOrNull())
        }
        fun sel(key: String, pick: (ContributionEntity) -> Double?): java.math.BigDecimal {
            val req = rangeParam(key) ?: return java.math.BigDecimal.ZERO
            val (lo, hi) = corpusRange(pick)
            return dec(PricingSelectivity.range(req.first, req.second, lo, hi))
        }

        val salePriceSelectivity = sel("salePrice") { it.salePrice?.toDouble() }
        val squareMetersSelectivity = sel("squareMeters") { it.squareMeters?.toDouble() }
        val yearBuiltSelectivity = sel("yearBuilt") { it.yearBuilt?.toDouble() }

        // Sale dates are compared as epoch days so the same range helper applies.
        val saleDateSelectivity = run {
            val m = params["saleDate"] as? Map<String, Any?>
            if (m == null) java.math.BigDecimal.ZERO else {
                val toEpoch = { v: Any? -> (v as? String)?.let { LocalDate.parse(it).toEpochDay().toDouble() } }
                val (lo, hi) = corpusRange { it.saleDate?.toEpochDay()?.toDouble() }
                dec(PricingSelectivity.range(toEpoch(m["from"]), toEpoch(m["to"]), lo, hi))
            }
        }

        // Rooms arrive as a list of values but are matched as the band between its extremes.
        val roomsSelectivity = run {
            val rooms = params["numberOfRooms"] as? List<Int>
            if (rooms.isNullOrEmpty()) java.math.BigDecimal.ZERO else {
                val (lo, hi) = corpusRange { it.numberOfRooms?.toDouble() }
                dec(PricingSelectivity.range(rooms.minOrNull()?.toDouble(), rooms.maxOrNull()?.toDouble(), lo, hi))
            }
        }

        fun listSel(key: String, pick: (ContributionEntity) -> Collection<String>): java.math.BigDecimal {
            val selected = (params[key] as? List<String>)?.size ?: 0
            val distinct = corpus.flatMap(pick).toSet().size
            return dec(PricingSelectivity.list(selected, distinct))
        }
        val propertyTypeSelectivity = listSel("propertyTypes") { listOfNotNull(it.propertyType) }
        val energyRatingSelectivity = listSel("energyRating") { listOfNotNull(it.energyRating) }
        val tagsSelectivity = listSel("tags") { parseTags(it.tagsJson) }

        val addresses = params["propertyAddress"] as? List<Map<String, Any?>>
        val addressSelectivity = dec(
            PricingSelectivity.address(addresses?.maxOfOrNull { countAddressFields(it) } ?: 0)
        )

        // quality of what is delivered, not of what was asked for
        val trustQuality = dec(PricingSelectivity.trustQuality(
            contributions.map { it.totalTrustScore.toDouble() },
            corpus.map { it.totalTrustScore.toDouble() }
        ))

        return PricingMetadata(
            contributions.size.toLong(),
            maxOf(corpus.size, contributions.size).toLong(),
            addressSelectivity,
            salePriceSelectivity,
            saleDateSelectivity,
            squareMetersSelectivity,
            yearBuiltSelectivity,
            roomsSelectivity,
            propertyTypeSelectivity,
            energyRatingSelectivity,
            tagsSelectivity,
            trustQuality
        )
    }

    private fun dec(v: Double): java.math.BigDecimal =
        java.math.BigDecimal(v).setScale(10, RoundingMode.HALF_UP)

    private fun parseTags(tagsJson: String?): List<String> =
        tagsJson?.let { try { Json.decodeFromString<List<String>>(it) } catch (e: Exception) { emptyList() } }
            ?: emptyList()

    private fun countAddressFields(address: Map<String, Any?>): Int =
        listOf("postalCode", "street", "city", "country", "additionalInfo").count { address[it] != null }

    private fun average(values: List<BigDecimal>): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        return values.reduce { a, b -> a + b }.divide(BigDecimal(values.size), 4, RoundingMode.HALF_UP)
    }

    private fun variance(values: List<BigDecimal>): BigDecimal {
        if (values.size < 2) return BigDecimal.ZERO
        val mean = average(values)
        val sumSquares = values.map { (it - mean).pow(2) }.reduce { a, b -> a + b }
        return sumSquares.divide(BigDecimal(values.size - 1), 4, RoundingMode.HALF_UP)
    }

    private fun medianDecimal(values: List<BigDecimal>): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]).divide(BigDecimal(2), 4, RoundingMode.HALF_UP)
        } else {
            sorted[sorted.size / 2]
        }
    }

    private fun <T : Comparable<T>> median(values: List<T>): T? {
        if (values.isEmpty()) return null
        return values.sorted()[values.size / 2]
    }

    // Exact arithmetic rounded once: the auditor compares every delivered statistic digit for
    // digit, and rounding a Double's binary expansion can land on the other side of a half.
    private fun mean(values: List<Int>): BigDecimal =
        BigDecimal(values.sumOf { it.toLong() }).divide(BigDecimal(values.size), 4, RoundingMode.HALF_UP)

    // Ties are broken by value; the database row order is not stable, so counting alone would
    // make the result differ between runs.
    private fun <T> distribution(values: List<T>): List<Pair<String, Int>> =
        values.groupingBy { it.toString() }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
}
