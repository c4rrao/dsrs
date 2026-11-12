package com.dsrs.common.models

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null
)

@Serializable
data class SuccessResponse(
    val message: String,
    val contractId: String? = null,
    val data: Map<String, String>? = null
)

@Serializable
data class RangeDto<T>(
    val from: T? = null,
    val to: T? = null
)

@Serializable
data class AddressDto(
    val postalCode: String? = null,
    val street: String? = null,
    val city: String? = null,
    val country: String? = null,
    val additionalInfo: String? = null
)

@Serializable
data class ContributionDataDto(
    val propertyAddress: AddressDto? = null,
    val salePrice: Double? = null,
    val saleDate: String? = null,
    val propertyType: String? = null,
    val squareMeters: Double? = null,
    val numberOfRooms: Int? = null,
    val yearBuilt: Int? = null,
    val energyRating: String? = null,
    val tags: List<String>? = null,
    val additionalInfo: String? = null
)

@Serializable
data class QueryParamsDto(
    val level: Int = 1,
    val trustScore: RangeDto<Double>? = null,
    val propertyAddress: List<AddressDto>? = null,
    val salePrice: RangeDto<Double>? = null,
    val saleDate: RangeDto<String>? = null,
    val propertyTypes: List<String>? = null,
    val squareMeters: RangeDto<Double>? = null,
    val numberOfRooms: List<Int>? = null,
    val yearBuilt: RangeDto<Int>? = null,
    val energyRating: List<String>? = null,
    val tags: List<String>? = null
)

@Serializable
data class InsightDto(
    val level: Int,
    val transactionCount: Int,
    val avgSalePrice: Double? = null,
    val avgTrustScore: Double? = null,
    val avgYearBuilt: Double? = null,
    val avgSquareMeters: Double? = null,
    val avgNumberOfRooms: Double? = null,
    val medianEnergyRating: String? = null,
    val details: DetailedMetricsDto? = null
)

@Serializable
data class DetailedMetricsDto(
    val priceRange: RangeDto<Double>? = null,
    val priceVariance: Double? = null,
    val medianPrice: Double? = null,
    val avgPricePerSqm: Double? = null,
    val pricePerSqmRange: RangeDto<Double>? = null,
    val yearBuiltRange: RangeDto<Int>? = null,
    val sqmRange: RangeDto<Double>? = null,
    val sqmVariance: Double? = null,
    val roomsRange: RangeDto<Int>? = null,
    val propertyTypeDistribution: List<DistributionEntryDto>? = null,
    val energyRatingDistribution: List<DistributionEntryDto>? = null,
    val cityDistribution: List<DistributionEntryDto>? = null,
    val saleDateRange: RangeDto<String>? = null,
    val topTags: List<DistributionEntryDto>? = null,
    val avgContributorsPerTx: Double? = null,
    val avgDisputesPerTx: Double? = null,
    val disputeRate: Double? = null
)

@Serializable
data class DistributionEntryDto(
    val key: String,
    val count: Int
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String = "1.0.0"
)

@Serializable
data class ReadyResponse(
    val ready: Boolean,
    val service: String,
    val details: Map<String, Boolean>? = null
)
