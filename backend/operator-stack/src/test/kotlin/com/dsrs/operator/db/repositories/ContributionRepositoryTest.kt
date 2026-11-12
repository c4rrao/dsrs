package com.dsrs.operator.db.repositories

import com.dsrs.operator.db.Contributions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContributionRepositoryTest {

    private lateinit var database: Database
    private lateinit var repository: ContributionRepository

    @BeforeEach
    fun setup() {
        database = Database.connect("jdbc:h2:mem:test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction(database) {
            SchemaUtils.create(Contributions)
        }
        repository = ContributionRepository()
    }

    @AfterEach
    fun cleanup() {
        TransactionManager.closeAndUnregister(database)
    }

    @Test
    fun `findByTransactionId returns existing contribution`() {
        val entity = createTestEntity(transactionId = "TX-001", contractId = "contract-001")
        repository.upsert(entity)

        val result = repository.findByTransactionId("TX-001")

        assertNotNull(result)
        assertEquals("TX-001", result.transactionId)
        assertEquals("contract-001", result.contractId)
        assertEquals("confirmed", result.status)
    }

    @Test
    fun `findByTransactionId returns null for non-existent transaction`() {
        val result = repository.findByTransactionId("NON-EXISTENT")

        assertNull(result)
    }

    @Test
    fun `upsert creates entry with pending status`() {
        val entity = createTestEntity(
            transactionId = "TX-PENDING",
            contractId = "pending-placeholder",
            status = "pending"
        )

        repository.upsert(entity)

        val result = repository.findByTransactionId("TX-PENDING")
        assertNotNull(result)
        assertEquals("pending", result.status)
        assertTrue(result.isPending)
    }

    @Test
    fun `confirmPending updates status to confirmed`() {
        val entity = createTestEntity(
            transactionId = "TX-TO-CONFIRM",
            contractId = "pending-placeholder",
            status = "pending"
        )
        repository.upsert(entity)

        repository.confirmPending("TX-TO-CONFIRM", "real-contract-123")

        val result = repository.findByTransactionId("TX-TO-CONFIRM")
        assertNotNull(result)
        assertEquals("confirmed", result.status)
        assertEquals("real-contract-123", result.contractId)
        assertTrue(result.isConfirmed)
    }

    @Test
    fun `queryContributions filters by city`() {
        repository.upsert(createTestEntity(transactionId = "TX-1", propertyCity = "Lisbon"))
        repository.upsert(createTestEntity(transactionId = "TX-2", propertyCity = "Porto"))
        repository.upsert(createTestEntity(transactionId = "TX-3", propertyCity = "Lisbon"))

        val results = repository.queryContributions(cities = listOf("Lisbon"))

        assertEquals(2, results.size)
        assertTrue(results.all { it.propertyCity == "Lisbon" })
    }

    @Test
    fun `queryContributions filters by price range`() {
        repository.upsert(createTestEntity(transactionId = "TX-1", salePrice = BigDecimal("250000")))
        repository.upsert(createTestEntity(transactionId = "TX-2", salePrice = BigDecimal("350000")))
        repository.upsert(createTestEntity(transactionId = "TX-3", salePrice = BigDecimal("450000")))
        repository.upsert(createTestEntity(transactionId = "TX-4", salePrice = BigDecimal("550000")))

        val results = repository.queryContributions(
            minPrice = BigDecimal("300000"),
            maxPrice = BigDecimal("500000")
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it.salePrice!! >= BigDecimal("300000") && it.salePrice!! <= BigDecimal("500000") })
    }

    @Test
    fun `queryContributions filters by date range`() {
        repository.upsert(createTestEntity(transactionId = "TX-1", saleDate = LocalDate.of(2023, 12, 15)))
        repository.upsert(createTestEntity(transactionId = "TX-2", saleDate = LocalDate.of(2024, 1, 15)))
        repository.upsert(createTestEntity(transactionId = "TX-3", saleDate = LocalDate.of(2024, 2, 15)))
        repository.upsert(createTestEntity(transactionId = "TX-4", saleDate = LocalDate.of(2024, 3, 15)))

        val results = repository.queryContributions(
            minDate = LocalDate.of(2024, 1, 1),
            maxDate = LocalDate.of(2024, 2, 28)
        )

        assertEquals(2, results.size)
        assertTrue(results.all {
            it.saleDate!! >= LocalDate.of(2024, 1, 1) && it.saleDate!! <= LocalDate.of(2024, 2, 28)
        })
    }

    @Test
    fun `queryContributions filters by trust score range`() {
        repository.upsert(createTestEntity(transactionId = "TX-1", totalTrustScore = BigDecimal("0.5")))
        repository.upsert(createTestEntity(transactionId = "TX-2", totalTrustScore = BigDecimal("1.0")))
        repository.upsert(createTestEntity(transactionId = "TX-3", totalTrustScore = BigDecimal("1.5")))
        repository.upsert(createTestEntity(transactionId = "TX-4", totalTrustScore = BigDecimal("2.0")))

        val results = repository.queryContributions(
            minTrustScore = BigDecimal("1.0"),
            maxTrustScore = BigDecimal("1.5")
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it.totalTrustScore >= BigDecimal("1.0") && it.totalTrustScore <= BigDecimal("1.5") })
    }

    @Test
    fun `queryContributions combines multiple filters correctly`() {
        repository.upsert(createTestEntity(
            transactionId = "TX-1",
            propertyCity = "Lisbon",
            salePrice = BigDecimal("350000"),
            propertyType = "Apartment",
            energyRating = "A"
        ))
        repository.upsert(createTestEntity(
            transactionId = "TX-2",
            propertyCity = "Lisbon",
            salePrice = BigDecimal("450000"),
            propertyType = "House",
            energyRating = "B"
        ))
        repository.upsert(createTestEntity(
            transactionId = "TX-3",
            propertyCity = "Porto",
            salePrice = BigDecimal("350000"),
            propertyType = "Apartment",
            energyRating = "A"
        ))

        val results = repository.queryContributions(
            cities = listOf("Lisbon"),
            propertyTypes = listOf("Apartment"),
            energyRatings = listOf("A"),
            minPrice = BigDecimal("300000"),
            maxPrice = BigDecimal("400000")
        )

        assertEquals(1, results.size)
        val result = results.first()
        assertEquals("Lisbon", result.propertyCity)
        assertEquals("Apartment", result.propertyType)
        assertEquals("A", result.energyRating)
        assertTrue(result.salePrice!! >= BigDecimal("300000") && result.salePrice!! <= BigDecimal("400000"))
    }

    @Test
    fun `queryContributions handles empty result set`() {
        repository.upsert(createTestEntity(transactionId = "TX-1", propertyCity = "Lisbon"))

        val results = repository.queryContributions(cities = listOf("Paris"))

        assertEquals(0, results.size)
    }

    @Test
    fun `queryContributions excludes pending contributions`() {
        repository.upsert(createTestEntity(
            transactionId = "TX-CONFIRMED",
            status = "confirmed",
            propertyCity = "Lisbon"
        ))
        repository.upsert(createTestEntity(
            transactionId = "TX-PENDING",
            status = "pending",
            propertyCity = "Lisbon"
        ))

        val results = repository.queryContributions(cities = listOf("Lisbon"))

        assertEquals(1, results.size)
        assertEquals("TX-CONFIRMED", results.first().transactionId)
        assertEquals("confirmed", results.first().status)
    }

    @Test
    fun `queryContributionsAtTime excludes uncorroborated contributions`() {
        val now = LocalDateTime.now()
        repository.upsert(createTestEntity(
            transactionId = "TX-SOLO",
            contributorCount = 1,
            createdAt = now.minusHours(1)
        ))
        repository.upsert(createTestEntity(
            transactionId = "TX-CORROBORATED",
            contributorCount = 2,
            createdAt = now.minusHours(1)
        ))

        val results = repository.queryContributionsAtTime(snapshotTime = now)

        assertEquals(1, results.size)
        assertEquals("TX-CORROBORATED", results.first().transactionId)
    }

    // a merge rewrites the row from the ledger, which makes the transaction sellable
    @Test
    fun `a contribution becomes queryable once a second party corroborates it`() {
        val now = LocalDateTime.now()
        repository.upsert(createTestEntity(
            transactionId = "TX-MERGE",
            contributorCount = 1,
            createdAt = now.minusHours(1)
        ))
        assertTrue(repository.queryContributionsAtTime(snapshotTime = now).isEmpty())

        repository.upsert(createTestEntity(
            transactionId = "TX-MERGE",
            contributorCount = 2,
            createdAt = now.minusHours(1)
        ))

        val results = repository.queryContributionsAtTime(snapshotTime = now)
        assertEquals(1, results.size)
        assertEquals(2, results.first().contributorCount)
    }

    private fun createTestEntity(
        transactionId: String,
        contractId: String = "contract-$transactionId",
        status: String = "confirmed",
        contributionId: String = "contribution-$transactionId",
        propertyCity: String? = "Lisbon",
        propertyCountry: String? = "Portugal",
        propertyPostalCode: String? = "1000-001",
        salePrice: BigDecimal? = BigDecimal("350000.00"),
        saleDate: LocalDate? = LocalDate.of(2024, 1, 15),
        propertyType: String? = "Apartment",
        squareMeters: BigDecimal? = BigDecimal("85.00"),
        numberOfRooms: Int? = 3,
        yearBuilt: Int? = 2010,
        energyRating: String? = "B",
        totalTrustScore: BigDecimal = BigDecimal("1.0"),
        tagsJson: String? = """["Central","Renovated"]""",
        contributionDataJson: String = """{"propertyAddress":{"city":"$propertyCity"},"salePrice":$salePrice}""",
        contributorsDataJson: String = """[{"party":"Alice::1220test","role":"Buyer"}]""",
        contributorCount: Int = 2,
        trustScoreJson: String = """{"totalScore":1.0}""",
        originalSubmissionDate: LocalDateTime = LocalDateTime.of(2024, 1, 15, 10, 0),
        disputeDatesJson: String = """[]""",
        createdAt: LocalDateTime = LocalDateTime.now(),
        updatedAt: LocalDateTime = LocalDateTime.now()
    ): ContributionEntity {
        return ContributionEntity(
            contractId = contractId,
            contributionId = contributionId,
            transactionId = transactionId,
            status = status,
            contributionDataJson = contributionDataJson,
            contributorsDataJson = contributorsDataJson,
            contributorCount = contributorCount,
            trustScoreJson = trustScoreJson,
            totalTrustScore = totalTrustScore,
            originalSubmissionDate = originalSubmissionDate,
            disputeDatesJson = disputeDatesJson,
            propertyCity = propertyCity,
            propertyCountry = propertyCountry,
            propertyPostalCode = propertyPostalCode,
            salePrice = salePrice,
            saleDate = saleDate,
            propertyType = propertyType,
            squareMeters = squareMeters,
            numberOfRooms = numberOfRooms,
            yearBuilt = yearBuilt,
            energyRating = energyRating,
            tagsJson = tagsJson,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
