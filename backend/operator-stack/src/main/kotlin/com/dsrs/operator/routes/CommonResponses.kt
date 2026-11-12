package com.dsrs.operator.routes

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class SuccessResponse(
    val message: String,
    val contractId: String? = null
)
