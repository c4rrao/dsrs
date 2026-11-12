package com.dsrs.auditor.services

import com.dsrs.auditor.db.repositories.ContributionSnapshotEntity
import com.dsrs.daml.types.InsightBase
import com.dsrs.daml.types.insight.Level1
import com.dsrs.daml.types.insight.Level2
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultStatisticsServiceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val service = ResultStatisticsService(json)

    private val rows = listOf(
        snapshot("TX-1", price = "200000", sqm = "80", year = 1990, rooms = 2, city = "Lisbon",
            type = "Apartment", rating = "B", tags = listOf("renovated"), trust = "3.0",
            contributors = 2, disputes = 0),
        snapshot("TX-2", price = "300000", sqm = "100", year = 2000, rooms = 3, city = "Lisbon",
            type = "Apartment", rating = "C", tags = listOf("renovated", "garden"), trust = "4.0",
            contributors = 2, disputes = 1),
        snapshot("TX-3", price = "250000", sqm = "90", year = 2001, rooms = 4, city = "Porto",
            type = "House", rating = "A", tags = listOf("garden"), trust = "5.0",
            contributors = 3, disputes = 0)
    )

    @Test
    fun `level one statistics are exact means rounded once`() {
        val insight = service.compute(rows, 1) as Level1
        val b = insight.base

        assertEquals(3L, b.transactionCount)
        assertEquals(0, BigDecimal("250000").compareTo(b.avgSalePrice.get()))
        assertEquals(0, BigDecimal("4").compareTo(b.avgTrustScore.get()))
        assertEquals(0, BigDecimal("1997").compareTo(b.avgYearBuilt.get()))
        assertEquals(0, BigDecimal("90").compareTo(b.avgSquareMeters.get()))
        assertEquals(0, BigDecimal("3").compareTo(b.avgNumberOfRooms.get()))
        assertEquals("B", b.medianEnergyRating.get().name)
    }

    @Test
    fun `a mean that does not terminate is rounded half up to four places`() {
        val two = rows.take(2) + snapshot("TX-4", price = "100000", sqm = "70", year = 1991, rooms = 2,
            city = "Faro", type = "Apartment", rating = "D", tags = emptyList(), trust = "2.0",
            contributors = 2, disputes = 0)
        val b = (service.compute(two, 1) as Level1).base
        assertEquals(BigDecimal("1993.6667"), b.avgYearBuilt.get())
    }

    @Test
    fun `level two distributions are ordered by count, ties by value`() {
        val d = (service.compute(rows, 2) as Level2).details

        val types = d.propertyTypeDistribution.get().map { it._1 to it._2 }
        assertEquals(listOf("Apartment" to 2L, "House" to 1L), types)

        val tags = d.topTags.get().map { it._1 to it._2 }
        assertEquals(listOf("garden" to 2L, "renovated" to 2L), tags)

        assertEquals(0, BigDecimal("250000").compareTo(d.medianPrice.get()))
        assertEquals(0, BigDecimal("2500000000").compareTo(d.priceVariance.get()))
        assertEquals(BigDecimal("2759.2600"), d.avgPricePerSqm.get())
        assertEquals(BigDecimal("0.3333"), d.disputeRate.get())
        assertEquals(BigDecimal("2.3333"), d.avgContributorsPerTx.get())
    }

    @Test
    fun `an honest answer agrees with the recomputation`() {
        val delivered = listOf(service.normalize(service.compute(rows, 2)))
        val derived = listOf(service.normalize(service.compute(rows.reversed(), 2)))

        assertTrue(service.compare(delivered, derived).isEmpty())
        assertEquals(delivered, derived)
    }

    @Test
    fun `a changed statistic is named, with both values`() {
        val honest = service.compute(rows, 1) as Level1
        val inflated = Level1(InsightBase(
            honest.base.transactionCount,
            Optional.of(BigDecimal("275000")),
            honest.base.avgTrustScore,
            honest.base.avgYearBuilt,
            honest.base.avgSquareMeters,
            honest.base.avgNumberOfRooms,
            honest.base.medianEnergyRating
        ))

        val found = service.compare(listOf(inflated), listOf(honest))

        assertEquals(1, found.size)
        assertEquals("avgSalePrice", found[0].field)
        assertEquals("275000", found[0].delivered)
        assertEquals("250000", found[0].derived)
    }

    @Test
    fun `a delivered answer over a different set is caught`() {
        val delivered = listOf(service.compute(rows.take(2), 1))
        val derived = listOf(service.compute(rows, 1))

        val fields = service.compare(delivered, derived).map { it.field }
        assertTrue("transactionCount" in fields)
    }

    @Test
    fun `decimals at the ledger's ten places compare equal to four`() {
        val four = service.compute(rows, 2)
        val ten = service.normalize(four)
        assertTrue(service.compare(listOf(four), listOf(ten)).isEmpty())
    }

    @Test
    fun `the stored answer survives a round trip through its JSON encoding`() {
        val insights = listOf(service.normalize(service.compute(rows, 2)))
        val decoded = service.decode(service.encode(insights))!!.map(service::normalize)
        assertEquals(insights, decoded)
    }

    @Test
    fun `a row that stored only the count cannot be checked`() {
        assertNull(service.decode("1"))
        assertNull(service.decode(null))
    }

    @Test
    fun `prices are read at two places, as the operator's index stores them`() {
        val odd = listOf(
            snapshot("TX-9", price = "100000.005", sqm = "50.004", year = 2000, rooms = 1, city = "Lisbon",
                type = "Apartment", rating = "A", tags = emptyList(), trust = "1.0", contributors = 2, disputes = 0)
        )
        val b = (service.compute(odd, 1) as Level1).base
        assertEquals(0, BigDecimal("100000.01").compareTo(b.avgSalePrice.get()))
        assertEquals(0, BigDecimal("50.00").compareTo(b.avgSquareMeters.get()))
    }

    private fun snapshot(
        tx: String,
        price: String,
        sqm: String,
        year: Int,
        rooms: Int,
        city: String,
        type: String,
        rating: String,
        tags: List<String>,
        trust: String,
        contributors: Int,
        disputes: Int
    ): ContributionSnapshotEntity {
        val data = mutableMapOf(
            "salePrice" to price,
            "saleDate" to LocalDate.of(2024, 3, 1).toString(),
            "propertyType" to type,
            "squareMeters" to sqm,
            "numberOfRooms" to rooms.toString(),
            "yearBuilt" to year.toString(),
            "energyRating" to rating,
            "city" to city
        )
        if (tags.isNotEmpty()) data["tags"] = json.encodeToString(tags)
        val contributorsJson = (1..contributors).joinToString(",", "{", "}") { "\"P$it\":{}" }
        val disputesJson = (1..disputes).joinToString(",", "[", "]") { "\"2024-0$it-01T00:00:00Z\"" }
        return ContributionSnapshotEntity(
            contractId = "cid-$tx",
            contributionId = "CONT-$tx",
            transactionId = tx,
            contributorCount = contributors,
            isActive = true,
            totalTrustScore = BigDecimal(trust),
            contributorsDataJson = contributorsJson,
            contributionDataJson = json.encodeToString(data.toMap()),
            trustScoreJson = "{}",
            disputeDatesJson = disputesJson,
            version = 1,
            validFrom = LocalDateTime.of(2024, 1, 1, 0, 0)
        )
    }
}
