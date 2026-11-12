package com.dsrs.common.types

import kotlinx.serialization.Serializable

// Decimals are carried as strings so they keep the precision of Daml's fixed-point Decimal.
@Serializable
data class ContributionData(
    val propertyAddress: Address? = null,
    val salePrice: String? = null,
    val saleDate: String? = null,
    val propertyType: String? = null,
    val squareMeters: String? = null,
    val numberOfRooms: Int? = null,
    val yearBuilt: Int? = null,
    val energyRating: EnergyRating? = null,
    val tags: List<String>? = null,
    val additionalInfo: String? = null
)
