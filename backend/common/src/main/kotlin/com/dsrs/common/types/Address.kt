package com.dsrs.common.types

import kotlinx.serialization.Serializable

@Serializable
data class Address(
    val postalCode: String? = null,
    val street: String? = null,
    val city: String? = null,
    val country: String? = null,
    val additionalInfo: String? = null
)
