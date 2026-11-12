package com.dsrs.common.types

import kotlinx.serialization.Serializable

// Self-declared by the contributor and not verified by anyone.
@Serializable
enum class Role {
    Buyer,
    Seller,
    Broker,
    Notary,
    TaxAuthority
}
