package com.dsrs.common.types

import kotlinx.serialization.Serializable

@Serializable
enum class TokenType {
    RewardToken,
    AuditToken
}
