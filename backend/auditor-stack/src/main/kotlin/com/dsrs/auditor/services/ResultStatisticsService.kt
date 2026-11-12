package com.dsrs.auditor.services

import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoder
import com.dsrs.auditor.db.repositories.ContributionSnapshotEntity
import com.dsrs.daml.da.types.Tuple2
import com.dsrs.daml.types.DetailedMetrics
import com.dsrs.daml.types.EnergyRating
import com.dsrs.daml.types.Insight
import com.dsrs.daml.types.InsightBase
import com.dsrs.daml.types.Range
import com.dsrs.daml.types.insight.Level1
import com.dsrs.daml.types.insight.Level2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Optional

data class StatisticDiscrepancy(
    val field: String,
    val delivered: String,
    val derived: String
)

/**
 * Recomputes the statistics a query delivered over the auditor's replay, since a correct price
 * says nothing about the numbers handed over. Re-implemented rather than shared with the operator,
 * but each statistic is defined exactly so an honest answer reproduces to the last digit:
 *
 *  - prices and areas at two decimals, as the operator's index stores them
 *  - means are the exact quotient rounded half up to four places; variance divides by n - 1
 *  - a median is the middle value or the mean of the two middle ones; the energy rating median
 *    is the value at n/2 of the sorted names
 *  - price per m² is rounded to two places per transaction before averaging
 *  - distributions are sorted by count, ties by value; top tags keeps the first ten
 */
class ResultStatisticsService(private val json: Json) {

    fun compute(matched: List<ContributionSnapshotEntity>, level: Int): Insight {
        val rows = matched.map { Row.of(it, json) }
        val base = base(rows)
        return if (level >= 2) Level2(base, details(rows)) else Level1(base)
    }

    // Decimals are compared by value, so 10 places on the ledger and 4 here are not a difference.
    fun compare(delivered: List<Insight>, derived: List<Insight>): List<StatisticDiscrepancy> {
        if (delivered.size != derived.size) {
            return listOf(StatisticDiscrepancy("insights", delivered.size.toString(), derived.size.toString()))
        }
        return delivered.zip(derived).flatMapIndexed { i, (d, e) ->
            val prefix = if (delivered.size > 1) "insight[$i]." else ""
            val a = flatten(d)
            val b = flatten(e)
            (a.keys + b.keys).sorted().mapNotNull { k ->
                val x = a[k] ?: "-"
                val y = b[k] ?: "-"
                if (x == y) null else StatisticDiscrepancy(prefix + k, x, y)
            }
        }
    }

    // CompleteAudit compares the two sides exactly, and codegen equality includes BigDecimal
    // scale, so both are put on the ledger's ten places.
    fun normalize(insight: Insight): Insight = when (insight) {
        is Level1 -> Level1(normalize(insight.base))
        is Level2 -> Level2(normalize(insight.base), normalize(insight.details))
        else -> insight
    }

    fun encode(insights: List<Insight>): String =
        insights.joinToString(",", "[", "]") { it.jsonEncoder().intoString() }

    // null for rows stored before the insights were kept, which cannot be checked
    fun decode(stored: String?): List<Insight>? {
        if (stored.isNullOrBlank()) return null
        val array = try {
            json.parseToJsonElement(stored) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            array.map { Insight.fromJson(it.toString()) }
        } catch (_: JsonLfDecoder.Error) {
            null
        }
    }

    private fun base(rows: List<Row>): InsightBase {
        val prices = rows.mapNotNull { it.salePrice }
        val years = rows.mapNotNull { it.yearBuilt }
        val sqms = rows.mapNotNull { it.squareMeters }
        val rooms = rows.mapNotNull { it.rooms }
        val ratings = rows.mapNotNull { it.energyRating }
        return InsightBase(
            rows.size.toLong(),
            opt(prices) { meanOf(it) },
            opt(rows.map { it.trust }) { meanOf(it) },
            opt(years) { meanOfInts(it) },
            opt(sqms) { meanOf(it) },
            opt(rooms) { meanOfInts(it) },
            Optional.ofNullable(ratings.sorted().getOrNull(ratings.size / 2)?.let(::rating))
        )
    }

    private fun details(rows: List<Row>): DetailedMetrics {
        val prices = rows.mapNotNull { it.salePrice }
        val sqms = rows.mapNotNull { it.squareMeters }
        val years = rows.mapNotNull { it.yearBuilt }
        val rooms = rows.mapNotNull { it.rooms }
        val perSqm = rows.mapNotNull { r ->
            val p = r.salePrice
            val a = r.squareMeters
            if (p != null && a != null && a.signum() > 0) p.divide(a, 2, RoundingMode.HALF_UP) else null
        }
        val disputed = rows.count { it.disputes > 0 }

        return DetailedMetrics(
            range(prices),
            if (prices.size > 1) Optional.of(variance(prices)) else Optional.empty(),
            opt(prices) { median(it) },
            opt(perSqm) { meanOf(it) },
            range(perSqm),
            range(years.map { it.toLong() }),
            range(sqms),
            if (sqms.size > 1) Optional.of(variance(sqms)) else Optional.empty(),
            range(rooms.map { it.toLong() }),
            counts(rows.mapNotNull { it.propertyType }),
            counts(rows.mapNotNull { it.energyRating }).map { list ->
                list.mapNotNull { t -> rating(t._1)?.let { Tuple2(it, t._2) } }
            }.filter { it.isNotEmpty() },
            range(rows.mapNotNull { it.saleDate }),
            counts(rows.mapNotNull { it.city }),
            counts(rows.flatMap { it.tags }).map { it.take(10) },
            opt(rows.map { it.contributors }) { meanOfInts(it) },
            opt(rows.map { it.disputes }) { meanOfInts(it) },
            if (rows.isEmpty()) Optional.empty()
            else Optional.of(BigDecimal(disputed).divide(BigDecimal(rows.size), 4, RoundingMode.HALF_UP))
        )
    }

    private fun meanOf(values: List<BigDecimal>): BigDecimal =
        values.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(values.size), 4, RoundingMode.HALF_UP)

    private fun meanOfInts(values: List<Int>): BigDecimal =
        BigDecimal(values.sumOf { it.toLong() }).divide(BigDecimal(values.size), 4, RoundingMode.HALF_UP)

    private fun variance(values: List<BigDecimal>): BigDecimal {
        val m = meanOf(values)
        val squares = values.fold(BigDecimal.ZERO) { acc, v -> acc + (v - m).pow(2) }
        return squares.divide(BigDecimal(values.size - 1), 4, RoundingMode.HALF_UP)
    }

    private fun median(values: List<BigDecimal>): BigDecimal {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]).divide(BigDecimal(2), 4, RoundingMode.HALF_UP)
    }

    private fun counts(values: List<String>): Optional<List<Tuple2<String, Long>>> {
        if (values.isEmpty()) return Optional.empty()
        val list = values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { Tuple2(it.key, it.value.toLong()) }
        return Optional.of(list)
    }

    private fun <T : Comparable<T>> range(values: List<T>): Optional<Range<T>> =
        if (values.isEmpty()) Optional.empty()
        else Optional.of(Range(Optional.of(values.min()), Optional.of(values.max())))

    private fun <T, R : Any> opt(values: List<T>, f: (List<T>) -> R): Optional<R> =
        if (values.isEmpty()) Optional.empty() else Optional.of(f(values))

    private fun rating(name: String): EnergyRating? =
        try { EnergyRating.valueOf(name) } catch (_: Exception) { null }

    private fun flatten(insight: Insight): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val base = when (insight) {
            is Level1 -> insight.base
            is Level2 -> insight.base
            else -> return mapOf("level" to insight.javaClass.simpleName)
        }
        out["level"] = if (insight is Level2) "2" else "1"
        out["transactionCount"] = base.transactionCount.toString()
        put(out, "avgSalePrice", base.avgSalePrice)
        put(out, "avgTrustScore", base.avgTrustScore)
        put(out, "avgYearBuilt", base.avgYearBuilt)
        put(out, "avgSquareMeters", base.avgSquareMeters)
        put(out, "avgNumberOfRooms", base.avgNumberOfRooms)
        put(out, "medianEnergyRating", base.medianEnergyRating)
        if (insight is Level2) {
            val d = insight.details
            put(out, "priceRange", d.priceRange)
            put(out, "priceVariance", d.priceVariance)
            put(out, "medianPrice", d.medianPrice)
            put(out, "avgPricePerSqm", d.avgPricePerSqm)
            put(out, "pricePerSqmRange", d.pricePerSqmRange)
            put(out, "yearBuiltRange", d.yearBuiltRange)
            put(out, "sqmRange", d.sqmRange)
            put(out, "sqmVariance", d.sqmVariance)
            put(out, "roomsRange", d.roomsRange)
            put(out, "propertyTypeDistribution", d.propertyTypeDistribution)
            put(out, "energyRatingDistribution", d.energyRatingDistribution)
            put(out, "saleDateRange", d.saleDateRange)
            put(out, "cityDistribution", d.cityDistribution)
            put(out, "topTags", d.topTags)
            put(out, "avgContributorsPerTx", d.avgContributorsPerTx)
            put(out, "avgDisputesPerTx", d.avgDisputesPerTx)
            put(out, "disputeRate", d.disputeRate)
        }
        return out
    }

    private fun put(out: MutableMap<String, String>, key: String, value: Optional<*>) {
        if (value.isPresent) out[key] = show(value.get())
    }

    private fun show(v: Any?): String = when (v) {
        null -> "-"
        is BigDecimal -> v.stripTrailingZeros().toPlainString()
        is Optional<*> -> if (v.isPresent) show(v.get()) else "-"
        is Range<*> -> "${show(v.from)}..${show(v.to)}"
        is Tuple2<*, *> -> "${show(v._1)}=${show(v._2)}"
        is List<*> -> v.joinToString(", ", "[", "]") { show(it) }
        is EnergyRating -> v.name
        else -> v.toString()
    }

    private fun normalize(b: InsightBase): InsightBase = InsightBase(
        b.transactionCount,
        b.avgSalePrice.map(::ten),
        b.avgTrustScore.map(::ten),
        b.avgYearBuilt.map(::ten),
        b.avgSquareMeters.map(::ten),
        b.avgNumberOfRooms.map(::ten),
        b.medianEnergyRating
    )

    private fun normalize(d: DetailedMetrics): DetailedMetrics = DetailedMetrics(
        d.priceRange.map(::tenRange),
        d.priceVariance.map(::ten),
        d.medianPrice.map(::ten),
        d.avgPricePerSqm.map(::ten),
        d.pricePerSqmRange.map(::tenRange),
        d.yearBuiltRange,
        d.sqmRange.map(::tenRange),
        d.sqmVariance.map(::ten),
        d.roomsRange,
        d.propertyTypeDistribution,
        d.energyRatingDistribution,
        d.saleDateRange,
        d.cityDistribution,
        d.topTags,
        d.avgContributorsPerTx.map(::ten),
        d.avgDisputesPerTx.map(::ten),
        d.disputeRate.map(::ten)
    )

    private fun ten(v: BigDecimal): BigDecimal = v.setScale(10, RoundingMode.HALF_UP)

    private fun tenRange(r: Range<BigDecimal>): Range<BigDecimal> = Range(r.from.map(::ten), r.to.map(::ten))

    private data class Row(
        val salePrice: BigDecimal?,
        val squareMeters: BigDecimal?,
        val yearBuilt: Int?,
        val rooms: Int?,
        val saleDate: LocalDate?,
        val propertyType: String?,
        val energyRating: String?,
        val city: String?,
        val tags: List<String>,
        val trust: BigDecimal,
        val contributors: Int,
        val disputes: Int
    ) {
        companion object {
            fun of(s: ContributionSnapshotEntity, json: Json): Row {
                val m = try {
                    json.decodeFromString<Map<String, String>>(s.contributionDataJson)
                } catch (_: Exception) {
                    emptyMap()
                }
                val tags = m["tags"]?.let {
                    try { json.decodeFromString<List<String>>(it) } catch (_: Exception) { emptyList() }
                } ?: emptyList()
                val disputes = try {
                    (json.parseToJsonElement(s.disputeDatesJson) as? JsonArray)?.size ?: 0
                } catch (_: Exception) {
                    0
                }
                val contributors = try {
                    (json.parseToJsonElement(s.contributorsDataJson) as? JsonObject)?.size ?: s.contributorCount
                } catch (_: Exception) {
                    s.contributorCount
                }
                return Row(
                    salePrice = m["salePrice"]?.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP),
                    squareMeters = m["squareMeters"]?.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP),
                    yearBuilt = m["yearBuilt"]?.toIntOrNull(),
                    rooms = m["numberOfRooms"]?.toIntOrNull(),
                    saleDate = m["saleDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    propertyType = m["propertyType"],
                    energyRating = m["energyRating"],
                    city = m["city"],
                    tags = tags,
                    trust = s.totalTrustScore,
                    contributors = contributors,
                    disputes = disputes
                )
            }
        }
    }
}
