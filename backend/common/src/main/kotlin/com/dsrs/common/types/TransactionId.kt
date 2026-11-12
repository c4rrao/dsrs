package com.dsrs.common.types

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class TransactionId(val value: String)
