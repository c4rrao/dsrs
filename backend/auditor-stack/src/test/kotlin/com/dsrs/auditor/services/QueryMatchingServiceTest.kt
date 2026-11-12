package com.dsrs.auditor.services

import com.dsrs.auditor.db.repositories.ContributionSnapshotEntity
import com.dsrs.auditor.db.repositories.ContributionSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryMatchingServiceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val snapshotTime: LocalDateTime = LocalDateTime.of(2026, 8, 14, 12, 0)

    private fun serviceWith(vararg contributions: ContributionSnapshotEntity): QueryMatchingService {
        val repo = mockk<ContributionSnapshotRepository>()
        every { repo.findActiveAtTime(any()) } returns contributions.toList()
        return QueryMatchingService(repo, json)
    }

    @Test
    fun `parses the full filter, not just the level`() {
        val service = serviceWith()
        val params = service.parseParams(
            """{"level":"2","salePrice":"{\"from\":\"100000\",\"to\":\"500000\"}",""" +
                """"propertyTypes":"[\"Apartment\"]","tags":"[\"renovated\"]",""" +
                """"numberOfRooms":"[\"2\",\"4\"]"}"""
        )

        assertEquals(2, params.level)
        assertEquals(BigDecimal("100000"), params.salePriceFrom)
        assertEquals(BigDecimal("500000"), params.salePriceTo)
        assertEquals(listOf("Apartment"), params.propertyTypes)
        assertEquals(listOf("renovated"), params.tags)
        assertEquals(listOf(2, 4), params.rooms)
    }

    @Test
    fun `unparseable params degrade to an unfiltered query rather than throwing`() {
        val params = serviceWith().parseParams("not json at all")
        assertEquals(1, params.level)
        assertTrue(params.propertyTypes == null)
    }

    @Test
    fun `an unfiltered query matches every active contribution`() {
        val service = serviceWith(
            contribution("TX-1", city = "Lisbon"),
            contribution("TX-2", city = "Porto")
        )

        val result = service.replay(AuditorQueryParams(), snapshotTime)

        assertEquals(2, result.matched.size)
        assertEquals(2, result.derivedMetadata.numTransactions)
    }

    @Test
    fun `city filter excludes contributions from other cities`() {
        val service = serviceWith(
            contribution("TX-1", city = "Lisbon"),
            contribution("TX-2", city = "Porto"),
            contribution("TX-3", city = "Lisbon")
        )

        val result = service.replay(
            AuditorQueryParams(addresses = listOf(mapOf("city" to "Lisbon"))),
            snapshotTime
        )

        assertEquals(listOf("TX-1", "TX-3"), result.matchedTransactionIds)
        assertEquals(2, result.derivedMetadata.numTransactions)
    }

    @Test
    fun `price band excludes contributions outside it, and those with no price at all`() {
        val service = serviceWith(
            contribution("TX-CHEAP", salePrice = "100000"),
            contribution("TX-MID", salePrice = "300000"),
            contribution("TX-RICH", salePrice = "900000"),
            contribution("TX-UNKNOWN", salePrice = null)
        )

        val result = service.replay(
            AuditorQueryParams(
                salePriceFrom = BigDecimal("200000"),
                salePriceTo = BigDecimal("500000")
            ),
            snapshotTime
        )

        assertEquals(listOf("TX-MID"), result.matchedTransactionIds)
    }

    @Test
    fun `tag filter matches a contribution carrying any of the requested tags`() {
        val service = serviceWith(
            contribution("TX-1", tags = listOf("renovated", "balcony")),
            contribution("TX-2", tags = listOf("garage")),
            contribution("TX-3", tags = emptyList())
        )

        val result = service.replay(AuditorQueryParams(tags = listOf("balcony", "garage")), snapshotTime)

        assertEquals(listOf("TX-1", "TX-2"), result.matchedTransactionIds)
    }

    @Test
    fun `trust score band filters on the snapshot's own score`() {
        val service = serviceWith(
            contribution("TX-LOW", trustScore = "1.0"),
            contribution("TX-HIGH", trustScore = "50.0")
        )

        val result = service.replay(AuditorQueryParams(trustScoreFrom = BigDecimal("10.0")), snapshotTime)

        assertEquals(listOf("TX-HIGH"), result.matchedTransactionIds)
    }

    @Test
    fun `rooms list is applied as a band between its extremes, like the operator does`() {
        val service = serviceWith(
            contribution("TX-1", rooms = 1),
            contribution("TX-3", rooms = 3),
            contribution("TX-9", rooms = 9)
        )

        val result = service.replay(AuditorQueryParams(rooms = listOf(2, 5)), snapshotTime)

        assertEquals(listOf("TX-3"), result.matchedTransactionIds)
    }

    @Test
    fun `an inflated numTransactions is reported as a metadata discrepancy`() {
        val service = serviceWith(
            contribution("TX-1"),
            contribution("TX-2")
        )

        val derived = service.replay(AuditorQueryParams(), snapshotTime).derivedMetadata
        // only numTransactions differs: the operator claims 500 where the auditor's history has 2
        val operatorClaim = PricingMetadataDto(numTransactions = 500, corpusTransactions = 2)

        val discrepancies = service.compareMetadata(operatorClaim, derived)

        assertEquals(1, discrepancies.size)
        assertEquals("numTransactions", discrepancies.single().field)
        assertEquals("500", discrepancies.single().claimed)
        assertEquals("2", discrepancies.single().derived)
    }

    @Test
    fun `a contribution archived after the query still counts when replaying at the query's own time`() {
        // state is updated in place when a contribution is later archived, so membership at a
        // point in time has to come from the validity window, not from state
        val liveThenArchivedLater = contribution("TX-ARCHIVED-LATER").copy(
            state = "archived",
            validTo = snapshotTime.plusHours(1),
            archivedAt = snapshotTime.plusHours(1)
        )
        val repo = mockk<ContributionSnapshotRepository>()
        every { repo.findActiveAtTime(any()) } returns listOf(contribution("TX-STILL-LIVE"), liveThenArchivedLater)
        val service = QueryMatchingService(repo, json)

        val result = service.replay(AuditorQueryParams(), snapshotTime)

        assertEquals(2, result.derivedMetadata.numTransactions)
        assertTrue("TX-ARCHIVED-LATER" in result.matchedTransactionIds)
    }

    @Test
    fun `honest metadata produces no discrepancies`() {
        val service = serviceWith(contribution("TX-1"), contribution("TX-2"))
        val derived = service.replay(AuditorQueryParams(), snapshotTime).derivedMetadata

        assertTrue(service.compareMetadata(derived, derived).isEmpty())
    }

    private fun contribution(
        transactionId: String,
        city: String? = "Lisbon",
        salePrice: String? = "250000",
        trustScore: String = "10.0",
        rooms: Int? = 3,
        tags: List<String> = emptyList()
    ): ContributionSnapshotEntity {
        // the shape HistorySynchronizer.serializeContributionData writes, tags as an embedded JSON string
        val fields = buildList {
            city?.let { add(""""city":"$it"""") }
            salePrice?.let { add(""""salePrice":"$it"""") }
            rooms?.let { add(""""numberOfRooms":"$it"""") }
            if (tags.isNotEmpty()) {
                val encoded = tags.joinToString(",") { "\\\"$it\\\"" }
                add(""""tags":"[$encoded]"""")
            }
        }

        return ContributionSnapshotEntity(
            contractId = "cid-$transactionId",
            contributionId = "CONTRIB-$transactionId",
            transactionId = transactionId,
            contributorCount = 1,
            isActive = true,
            totalTrustScore = BigDecimal(trustScore),
            contributorsDataJson = "{}",
            contributionDataJson = fields.joinToString(",", prefix = "{", postfix = "}"),
            trustScoreJson = "{}",
            disputeDatesJson = "[]",
            version = 1,
            validFrom = snapshotTime.minusDays(1)
        )
    }
}
