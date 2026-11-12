package com.dsrs.operator.models

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val error: String,
    val details: String? = null
)

@Serializable
data class ApiSuccess(
    val message: String,
    val data: Map<String, String>? = null
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
    val trustScore: RangeDto? = null,
    val propertyAddress: List<AddressDto>? = null,
    val salePrice: RangeDto? = null,
    val saleDate: DateRangeDto? = null,
    val propertyTypes: List<String>? = null,
    val squareMeters: RangeDto? = null,
    val numberOfRooms: List<Int>? = null,
    val yearBuilt: IntRangeDto? = null,
    val energyRating: List<String>? = null,
    val tags: List<String>? = null
)

@Serializable
data class RangeDto(
    val from: Double? = null,
    val to: Double? = null
)

@Serializable
data class DateRangeDto(
    val from: String? = null,
    val to: String? = null
)

@Serializable
data class IntRangeDto(
    val from: Int? = null,
    val to: Int? = null
)
