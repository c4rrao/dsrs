package com.dsrs.common.types

import kotlinx.serialization.Serializable

@Serializable
data class Range<T>(
    val from: T? = null,
    val to: T? = null
)

typealias DecimalRange = Range<String>
typealias IntRange = Range<Int>
typealias DateRange = Range<String>
