package com.dsrs.operator.services

import com.dsrs.operator.db.repositories.ContributionEntity
import com.dsrs.operator.db.repositories.ContributionRepository
import com.dsrs.daml.types.insight.Level1
import com.dsrs.daml.types.insight.Level2
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InsightServiceTest {

    private lateinit var contributionRepo: ContributionRepository
    private lateinit var service: InsightService

    @BeforeEach
    fun setup() {
        contributionRepo = mockk()
        service = InsightService(contributionRepo)
    }

    @Test
    fun `executeQuery returns matching contributions for city filter`() {
        val lisbonContributions = listOf(
            createTestContribution("TX-001", city = "Lisbon", price = 300000.0),
            createTestContribution("TX-002", city = "Lisbon", price = 350000.0)
        )

        every {
            contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),
                cities = listOf("Lisbon"),
                countries = any(),
                propertyTypes = any(),
                energyRatings = any(),
                tags = any(),
                minPrice = any(),
                maxPrice = any(),
                minDate = any(),
                maxDate = any(),
                minTrustScore = any(),
                maxTrustScore = any(),
                minSquareMeters = any(),
                maxSquareMeters = any(),
                minYearBuilt = any(),
                maxYearBuilt = any(),
                minRooms = any(),
                maxRooms = any(),
                limit = any()
            )
        } returns lisbonContributions

        val params = mapOf(
            "level" to 1,
            "propertyAddress" to listOf(mapOf("city" to "Lisbon"))
        )

        val result = service.executeQuery(params, level = 1)

        assertEquals(2, result.contributions.size)
        assertTrue(result.contributions.all { it.propertyCity == "Lisbon" })
        assertEquals(1, result.insights.size) // one aggregate insight per query
    }

    @Test
    fun `executeQuery returns matching contributions for price range`() {
        val priceRangeContributions = listOf(
            createTestContribution("TX-001", price = 320000.0),
            createTestContribution("TX-002", price = 380000.0)
        )

        every {
            contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),
                cities = null,
                countries = null,
                propertyTypes = null,
                energyRatings = null,
                tags = null,
                minPrice = BigDecimal("300000"),
                maxPrice = BigDecimal("400000"),
                minDate = null,
                maxDate = null,
                minTrustScore = null,
                maxTrustScore = null,
                minSquareMeters = null,
                maxSquareMeters = null,
                minYearBuilt = null,
                maxYearBuilt = null,
                minRooms = null,
                maxRooms = null,
                limit = any()
            )
        } returns priceRangeContributions

        val params = mapOf(
            "level" to 1,
            "salePrice" to mapOf("from" to 300000, "to" to 400000)
        )

        val result = service.executeQuery(params, level = 1)

        assertEquals(2, result.contributions.size)
        assertTrue(result.contributions.all {
            it.salePrice!! >= BigDecimal("300000") && it.salePrice!! <= BigDecimal("400000")
        })
    }

    @Test
    fun `executeQuery combines multiple filters correctly`() {
        val filteredContributions = listOf(
            createTestContribution(
                "TX-001",
                city = "Lisbon",
                price = 350000.0,
                propertyType = "Apartment",
                energyRating = "A"
            )
        )

        every {
            contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),
                cities = listOf("Lisbon"),
                countries = any(),
                propertyTypes = listOf("Apartment"),
                energyRatings = listOf("A"),
                tags = any(),
                minPrice = BigDecimal("300000"),
                maxPrice = BigDecimal("500000"),
                minDate = any(),
                maxDate = any(),
                minTrustScore = any(),
                maxTrustScore = any(),
                minSquareMeters = any(),
                maxSquareMeters = any(),
                minYearBuilt = any(),
                maxYearBuilt = any(),
                minRooms = any(),
                maxRooms = any(),
                limit = any()
            )
        } returns filteredContributions

        val params = mapOf(
            "level" to 1,
            "propertyAddress" to listOf(mapOf("city" to "Lisbon")),
            "salePrice" to mapOf("from" to 300000, "to" to 500000),
            "propertyTypes" to listOf("Apartment"),
            "energyRating" to listOf("A")
        )

        val result = service.executeQuery(params, level = 1)

        assertEquals(1, result.contributions.size)
        val contribution = result.contributions.first()
        assertEquals("Lisbon", contribution.propertyCity)
        assertEquals("Apartment", contribution.propertyType)
        assertEquals("A", contribution.energyRating)
    }

    @Test
    fun `computeInsight Level1 calculates averages correctly`() {
        val contributions = listOf(
            createTestContribution("TX-001", price = 300000.0, sqm = 80.0, rooms = 2, yearBuilt = 2010),
            createTestContribution("TX-002", price = 350000.0, sqm = 90.0, rooms = 3, yearBuilt = 2015),
            createTestContribution("TX-003", price = 400000.0, sqm = 100.0, rooms = 4, yearBuilt = 2020)
        )

        every { contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns contributions

        val params = mapOf("level" to 1)

        val result = service.executeQuery(params, level = 1)

        val insight = result.insights.first()
        assertTrue(insight is Level1)

        val base = (insight as Level1).base
        assertEquals(3L, base.transactionCount)

        assertTrue(base.avgSalePrice.isPresent)
        assertEquals(0, BigDecimal("350000.0000").compareTo(base.avgSalePrice.get()))

        assertTrue(base.avgSquareMeters.isPresent)
        assertEquals(0, BigDecimal("90.0000").compareTo(base.avgSquareMeters.get()))

        assertTrue(base.avgNumberOfRooms.isPresent)
        assertEquals(0, BigDecimal("3").compareTo(base.avgNumberOfRooms.get()))

        assertTrue(base.avgYearBuilt.isPresent)
        assertEquals(0, BigDecimal("2015").compareTo(base.avgYearBuilt.get()))
    }

    @Test
    fun `computeInsight Level1 calculates transaction count correctly`() {
        val contributions = listOf(
            createTestContribution("TX-001"),
            createTestContribution("TX-002"),
            createTestContribution("TX-003"),
            createTestContribution("TX-004"),
            createTestContribution("TX-005")
        )

        every { contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns contributions

        val params = mapOf("level" to 1)

        val result = service.executeQuery(params, level = 1)

        val insight = result.insights.first()
        assertTrue(insight is Level1)
        assertEquals(5L, (insight as Level1).base.transactionCount)
    }

    @Test
    fun `computeInsight Level2 includes detailed metrics`() {
        val contributions = listOf(
            createTestContribution("TX-001", price = 300000.0, sqm = 80.0, city = "Lisbon", propertyType = "Apartment"),
            createTestContribution("TX-002", price = 400000.0, sqm = 100.0, city = "Lisbon", propertyType = "House"),
            createTestContribution("TX-003", price = 350000.0, sqm = 90.0, city = "Porto", propertyType = "Apartment")
        )

        every { contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns contributions

        val params = mapOf("level" to 2)

        val result = service.executeQuery(params, level = 2)

        val insight = result.insights.first()
        assertTrue(insight is Level2)

        val level2Insight = insight as Level2
        assertEquals(3L, level2Insight.base.transactionCount)

        val details = level2Insight.details

        assertTrue(details.priceRange.isPresent)
        val priceRange = details.priceRange.get()
        assertTrue(priceRange.from.isPresent)
        assertTrue(priceRange.to.isPresent)
        assertEquals(0, BigDecimal("300000").compareTo(priceRange.from.get()))
        assertEquals(0, BigDecimal("400000").compareTo(priceRange.to.get()))

        assertTrue(details.propertyTypeDistribution.isPresent)
        val typeDistribution = details.propertyTypeDistribution.get()
        assertEquals(2, typeDistribution.size) // Apartment (2) and House (1)
    }

    @Test
    fun `computeInsight handles empty result set gracefully`() {
        every { contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val params = mapOf("level" to 1)

        val result = service.executeQuery(params, level = 1)

        assertEquals(0, result.contributions.size)
        assertEquals(1, result.insights.size)

        val insight = result.insights.first()
        assertTrue(insight is Level1)

        val base = (insight as Level1).base
        assertEquals(0L, base.transactionCount)
        assertTrue(!base.avgSalePrice.isPresent)
        assertTrue(!base.avgTrustScore.isPresent)
        assertTrue(!base.avgYearBuilt.isPresent)
    }

    @Test
    fun `range selectivity is the share of the corpus spread the filter excludes`() {
        val corpus = listOf(
            createTestContribution("TX-001", price = 100000.0),
            createTestContribution("TX-002", price = 300000.0),
            createTestContribution("TX-003", price = 500000.0)
        )
        val params = mapOf("salePrice" to mapOf("from" to 200000, "to" to 400000))

        val metadata = service.extractPricingMetadata(params, corpus.take(2), corpus)

        assertEquals(2L, metadata.numTransactions)
        assertEquals(3L, metadata.corpusTransactions)
        assertEquals(0.5, metadata.salePriceSelectivity.toDouble(), 1e-9)
    }

    // the matched set's extremes are defined by the filter, so it cannot be the denominator
    @Test
    fun `selectivity does not collapse when the filter narrows the matched set`() {
        val corpus = listOf(
            createTestContribution("TX-001", sqm = 50.0),
            createTestContribution("TX-002", sqm = 100.0),
            createTestContribution("TX-003", sqm = 150.0)
        )
        val params = mapOf("squareMeters" to mapOf("from" to 50, "to" to 100))
        val matched = corpus.take(2)

        val metadata = service.extractPricingMetadata(params, matched, corpus)

        assertEquals(0.5, metadata.squareMetersSelectivity.toDouble(), 1e-9)
    }

    @Test
    fun `an unfiltered query has zero selectivity everywhere`() {
        val corpus = listOf(
            createTestContribution("TX-001", price = 100000.0),
            createTestContribution("TX-002", price = 300000.0)
        )

        val metadata = service.extractPricingMetadata(emptyMap(), corpus, corpus)

        assertEquals(2L, metadata.numTransactions)
        assertEquals(2L, metadata.corpusTransactions)
        listOf(
            metadata.addressSelectivity, metadata.salePriceSelectivity, metadata.saleDateSelectivity,
            metadata.squareMetersSelectivity, metadata.yearBuiltSelectivity, metadata.roomsSelectivity,
            metadata.propertyTypeSelectivity, metadata.energyRatingSelectivity, metadata.tagsSelectivity
        ).forEach { assertEquals(0.0, it.toDouble(), 1e-9) }
        assertEquals(0.0, metadata.trustQuality.toDouble(), 1e-9)
    }

    @Test
    fun `trust quality rises when the matched set holds the better records`() {
        val corpus = listOf(
            createTestContribution("TX-001", trustScore = 1.0),
            createTestContribution("TX-002", trustScore = 5.0),
            createTestContribution("TX-003", trustScore = 10.0)
        )

        val best = service.extractPricingMetadata(emptyMap(), listOf(corpus[2]), corpus)
        val worst = service.extractPricingMetadata(emptyMap(), listOf(corpus[0]), corpus)
        val all = service.extractPricingMetadata(emptyMap(), corpus, corpus)

        assertTrue(best.trustQuality.toDouble() > 0.0, "the best record must price above average")
        assertTrue(worst.trustQuality.toDouble() < 0.0, "the worst record must price below average")
        assertEquals(0.0, all.trustQuality.toDouble(), 1e-9)
    }

    @Test
    fun `computeInsight Level2 calculates price variance correctly`() {
        val uniformContributions = listOf(
            createTestContribution("TX-001", price = 350000.0),
            createTestContribution("TX-002", price = 350000.0),
            createTestContribution("TX-003", price = 350000.0)
        )

        every { contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns uniformContributions

        val params = mapOf("level" to 2)

        val result = service.executeQuery(params, level = 2)

        val insight = result.insights.first() as Level2
        val details = insight.details

        assertTrue(details.priceVariance.isPresent)
        assertEquals(0, BigDecimal.ZERO.compareTo(details.priceVariance.get()))
    }

    @Test
    fun `computeInsight Level2 calculates average price per square meter`() {
        val contributions = listOf(
            createTestContribution("TX-001", price = 300000.0, sqm = 100.0), // 3000 per sqm
            createTestContribution("TX-002", price = 400000.0, sqm = 100.0)  // 4000 per sqm
        )

        every { contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns contributions

        val params = mapOf("level" to 2)

        val result = service.executeQuery(params, level = 2)

        val insight = result.insights.first() as Level2
        val details = insight.details

        assertTrue(details.avgPricePerSqm.isPresent)
        assertEquals(0, BigDecimal("3500").compareTo(details.avgPricePerSqm.get()))
    }

    // the auditor compares delivered statistics digit for digit, so row order must not matter
    @Test
    fun `distribution ties are broken by value, whatever order the rows arrive in`() {
        val rows = listOf(
            createTestContribution("TX-001", city = "Porto", propertyType = "House"),
            createTestContribution("TX-002", city = "Lisbon", propertyType = "Apartment"),
            createTestContribution("TX-003", city = "Faro", propertyType = "Villa")
        )
        fun citiesFor(order: List<ContributionEntity>): List<String> {
            every { contributionRepo.queryContributionsAtTime(
                snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns order
            val details = (service.executeQuery(mapOf("level" to 2), level = 2).insights.first() as Level2).details
            return details.cityDistribution.get().map { it._1 }
        }

        assertEquals(listOf("Faro", "Lisbon", "Porto"), citiesFor(rows))
        assertEquals(listOf("Faro", "Lisbon", "Porto"), citiesFor(rows.reversed()))
    }

    @Test
    fun `means of whole numbers are the exact quotient rounded once`() {
        val contributions = listOf(
            createTestContribution("TX-001", yearBuilt = 1990),
            createTestContribution("TX-002", yearBuilt = 2000),
            createTestContribution("TX-003", yearBuilt = 1991)
        )
        every { contributionRepo.queryContributionsAtTime(
            snapshotTime = any(),any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns contributions

        val base = (service.executeQuery(mapOf("level" to 1), level = 1).insights.first() as Level1).base

        assertEquals(BigDecimal("1993.6667"), base.avgYearBuilt.get())
    }

    private fun createTestContribution(
        transactionId: String,
        city: String = "Lisbon",
        price: Double = 350000.0,
        sqm: Double = 85.0,
        rooms: Int = 3,
        yearBuilt: Int = 2010,
        propertyType: String = "Apartment",
        energyRating: String = "B",
        trustScore: Double = 1.0
    ): ContributionEntity {
        return ContributionEntity(
            contractId = "contract-$transactionId",
            contributionId = "contribution-$transactionId",
            transactionId = transactionId,
            status = "confirmed",
            contributionDataJson = """{"city":"$city","price":$price}""",
            contributorsDataJson = """{"Alice::1220test":{"role":"Buyer"}}""",
            contributorCount = 2,
            trustScoreJson = """{"totalScore":$trustScore}""",
            totalTrustScore = BigDecimal(trustScore),
            originalSubmissionDate = LocalDateTime.now(),
            disputeDatesJson = """[]""",
            propertyCity = city,
            propertyCountry = "Portugal",
            propertyPostalCode = "1000-001",
            salePrice = BigDecimal(price),
            saleDate = LocalDate.of(2024, 1, 15),
            propertyType = propertyType,
            squareMeters = BigDecimal(sqm),
            numberOfRooms = rooms,
            yearBuilt = yearBuilt,
            energyRating = energyRating,
            tagsJson = """["Central"]""",
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
    }
}
