package com.dsrs.common.types

import kotlinx.serialization.Serializable

// level 1: InsightBase only, level 2: with DetailedMetrics
@Serializable
data class QueryParams(
    val level: Int? = null,
    val trustScore: DecimalRange? = null,
    val propertyAddress: List<Address>? = null,
    val salePrice: DecimalRange? = null,
    val saleDate: DateRange? = null,
    val propertyTypes: List<String>? = null,
    val squareMeters: DecimalRange? = null,
    val numberOfRooms: List<Int>? = null,
    val yearBuilt: Range<Int>? = null,
    val energyRating: List<EnergyRating>? = null,
    val tags: List<String>? = null
)
