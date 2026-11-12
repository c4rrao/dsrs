package com.dsrs.common.types

import kotlinx.serialization.Serializable

@Serializable
data class ContributorData(
    val contributor: String,
    val roleInTransaction: Role,
    val disputeCoefficient: String,
    val reputation: String
)
