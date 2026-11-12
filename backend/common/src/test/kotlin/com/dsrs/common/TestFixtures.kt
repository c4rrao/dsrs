package com.dsrs.common

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

object TestFixtures {

    object Parties {
        const val ALICE = "Alice::1220test"
        const val BOB = "Bob::1220test"
        const val CHARLIE = "Charlie::1220test"
        const val DIANA = "Diana::1220test"
        const val OPERATOR = "Operator::1220test"
    }

    object Addresses {
        val lisbon = mapOf(
            "city" to "Lisbon",
            "country" to "Portugal",
            "postalCode" to "1000-001",
            "street" to "Rua Augusta, 123"
        )

        val porto = mapOf(
            "city" to "Porto",
            "country" to "Portugal",
            "postalCode" to "4000-001",
            "street" to "Rua Santa Catarina, 456"
        )

        val madrid = mapOf(
            "city" to "Madrid",
            "country" to "Spain",
            "postalCode" to "28001",
            "street" to "Calle Mayor, 789"
        )
    }

    object ContributionData {
        val lisbonApartment = mapOf(
            "propertyAddress" to Addresses.lisbon,
            "salePrice" to 350000.0,
            "saleDate" to "2024-01-15",
            "propertyType" to "Apartment",
            "squareMeters" to 85.0,
            "numberOfRooms" to 3,
            "yearBuilt" to 2010,
            "energyRating" to "B",
            "tags" to listOf("Central", "Renovated"),
            "additionalInfo" to "Recently renovated"
        )

        val lisbonApartmentHighPrice = lisbonApartment.toMutableMap().apply {
            put("salePrice", 450000.0)
        }

        val lisbonApartmentDifferentDate = lisbonApartment.toMutableMap().apply {
            put("saleDate", "2024-02-20")
        }

        val portoHouse = mapOf(
            "propertyAddress" to Addresses.porto,
            "salePrice" to 280000.0,
            "saleDate" to "2024-03-10",
            "propertyType" to "House",
            "squareMeters" to 120.0,
            "numberOfRooms" to 4,
            "yearBuilt" to 1995,
            "energyRating" to "C",
            "tags" to listOf("Garden", "Garage"),
            "additionalInfo" to "Corner house with garden"
        )

        val madridApartment = mapOf(
            "propertyAddress" to Addresses.madrid,
            "salePrice" to 420000.0,
            "saleDate" to "2024-04-05",
            "propertyType" to "Apartment",
            "squareMeters" to 95.0,
            "numberOfRooms" to 3,
            "yearBuilt" to 2015,
            "energyRating" to "A",
            "tags" to listOf("Modern", "City Center"),
            "additionalInfo" to "Luxury apartment"
        )
    }

    object Contributors {
        val alice = mapOf(
            "party" to Parties.ALICE,
            "role" to "Buyer",
            "submissionDate" to "2024-01-15T10:00:00Z",
            "reputationScore" to 1.0,
            "redundancyCoefficient" to 1.0,
            "disputeCoefficient" to 1.0
        )

        val bob = mapOf(
            "party" to Parties.BOB,
            "role" to "Seller",
            "submissionDate" to "2024-01-15T11:00:00Z",
            "reputationScore" to 1.0,
            "redundancyCoefficient" to 1.0,
            "disputeCoefficient" to 1.0
        )

        val charlie = mapOf(
            "party" to Parties.CHARLIE,
            "role" to "Broker",
            "submissionDate" to "2024-01-15T12:00:00Z",
            "reputationScore" to 1.5,
            "redundancyCoefficient" to 1.0,
            "disputeCoefficient" to 1.0
        )
    }

    object QueryParams {
        val lisbonApartments = mapOf(
            "level" to 1,
            "propertyAddress" to listOf(mapOf("city" to "Lisbon")),
            "salePrice" to mapOf("from" to 300000.0, "to" to 500000.0),
            "propertyTypes" to listOf("Apartment")
        )

        val allPortugal = mapOf(
            "level" to 2,
            "propertyAddress" to listOf(mapOf("country" to "Portugal")),
            "salePrice" to mapOf("from" to 200000.0, "to" to 600000.0)
        )

        val energyEfficientHomes = mapOf(
            "level" to 1,
            "energyRating" to listOf("A", "B"),
            "yearBuilt" to mapOf("from" to 2010, "to" to 2024)
        )
    }

    fun createContributionEntity(
        contractId: String = "contract-001",
        contributionId: String = "contribution-001",
        transactionId: String = "TX-001",
        status: String = "confirmed",
        contributionDataJson: String = """{"propertyAddress":{"city":"Lisbon"},"salePrice":350000.0}""",
        contributorsDataJson: String = """[{"party":"Alice::1220test","role":"Buyer"}]""",
        trustScoreJson: String = """{"totalScore":1.0}""",
        totalTrustScore: BigDecimal = BigDecimal("1.0"),
        originalSubmissionDate: LocalDateTime = LocalDateTime.of(2024, 1, 15, 10, 0),
        disputeDatesJson: String = """[]""",
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
        tagsJson: String? = """["Central","Renovated"]""",
        createdAt: LocalDateTime = LocalDateTime.now(),
        updatedAt: LocalDateTime = LocalDateTime.now()
    ): Map<String, Any?> {
        return mapOf(
            "contractId" to contractId,
            "contributionId" to contributionId,
            "transactionId" to transactionId,
            "status" to status,
            "contributionDataJson" to contributionDataJson,
            "contributorsDataJson" to contributorsDataJson,
            "trustScoreJson" to trustScoreJson,
            "totalTrustScore" to totalTrustScore,
            "originalSubmissionDate" to originalSubmissionDate,
            "disputeDatesJson" to disputeDatesJson,
            "propertyCity" to propertyCity,
            "propertyCountry" to propertyCountry,
            "propertyPostalCode" to propertyPostalCode,
            "salePrice" to salePrice,
            "saleDate" to saleDate,
            "propertyType" to propertyType,
            "squareMeters" to squareMeters,
            "numberOfRooms" to numberOfRooms,
            "yearBuilt" to yearBuilt,
            "energyRating" to energyRating,
            "tagsJson" to tagsJson,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt
        )
    }

    fun createPendingContributionEntity(
        transactionId: String = "TX-PENDING",
        contractId: String = "pending-placeholder",
        propertyCity: String? = "Lisbon",
        salePrice: BigDecimal? = BigDecimal("350000.00")
    ): Map<String, Any?> {
        return createContributionEntity(
            contractId = contractId,
            transactionId = transactionId,
            status = "pending",
            propertyCity = propertyCity,
            salePrice = salePrice
        )
    }

    fun createConfirmedContributionEntity(
        transactionId: String = "TX-CONFIRMED",
        salePrice: Double = 350000.0,
        propertyCity: String = "Lisbon"
    ): Map<String, Any?> {
        return createContributionEntity(
            transactionId = transactionId,
            status = "confirmed",
            salePrice = BigDecimal(salePrice),
            propertyCity = propertyCity
        )
    }

    object TestConfig {
        const val OPERATOR_PARTY = Parties.OPERATOR
        const val LEDGER_HOST = "localhost"
        const val LEDGER_PORT = 5011
        const val JWT_SECRET = "test-secret-key-for-testing-only"
        const val DB_URL = "jdbc:h2:mem:test"
        const val DB_DRIVER = "org.h2.Driver"
    }

    fun uniqueTransactionId(): String {
        return "TX-TEST-${System.currentTimeMillis()}"
    }

    fun uniqueContractId(): String {
        return "contract-${System.currentTimeMillis()}"
    }

    fun dateRange(fromYear: Int, fromMonth: Int, fromDay: Int, toYear: Int, toMonth: Int, toDay: Int): Pair<LocalDate, LocalDate> {
        return LocalDate.of(fromYear, fromMonth, fromDay) to LocalDate.of(toYear, toMonth, toDay)
    }

    fun priceRange(from: Double, to: Double): Pair<BigDecimal, BigDecimal> {
        return BigDecimal(from) to BigDecimal(to)
    }
}
