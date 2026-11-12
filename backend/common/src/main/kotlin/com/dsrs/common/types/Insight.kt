package com.dsrs.common.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InsightBase(
    val transactionCount: Int,
    val avgSalePrice: String? = null,
    val avgTrustScore: String? = null,
    val avgYearBuilt: String? = null,
    val avgSquareMeters: String? = null,
    val avgNumberOfRooms: String? = null,
    val medianEnergyRating: EnergyRating? = null
)

@Serializable
data class DetailedMetrics(
    val priceRange: DecimalRange? = null,
    val priceVariance: String? = null,
    val medianPrice: String? = null,
    val avgPricePerSqm: String? = null,
    val pricePerSqmRange: DecimalRange? = null,

    val yearBuiltRange: Range<Int>? = null,

    val sqmRange: DecimalRange? = null,
    val sqmVariance: String? = null,

    val roomsRange: Range<Int>? = null,

    val propertyTypeDistribution: List<Pair<String, Int>>? = null,

    val energyRatingDistribution: List<Pair<EnergyRating, Int>>? = null,

    val saleDateRange: DateRange? = null,

    val cityDistribution: List<Pair<String, Int>>? = null,

    val topTags: List<Pair<String, Int>>? = null,

    val avgContributorsPerTx: String? = null,
    val avgDisputesPerTx: String? = null,
    val disputeRate: String? = null
)

@Serializable
sealed class Insight {
    abstract val base: InsightBase

    @Serializable
    @SerialName("Level1")
    data class Level1(override val base: InsightBase) : Insight()

    @Serializable
    @SerialName("Level2")
    data class Level2(
        override val base: InsightBase,
        val details: DetailedMetrics
    ) : Insight()
}
