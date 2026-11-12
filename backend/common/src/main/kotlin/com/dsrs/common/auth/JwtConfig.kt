package com.dsrs.common.auth

import java.time.Duration

// JWT_SECRET must differ per organization, so tokens from one are not valid at another.
data class JwtConfig(
    val secret: String = System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET environment variable must be set (min 32 characters)"),
    val issuer: String = "dsrs",
    val audience: String = "dsrs-users",
    val realm: String = "dsrs",
    val expirationTime: Duration = Duration.ofHours(24)
) {
    init {
        require(secret.length >= 32) {
            "JWT secret must be at least 32 characters for security"
        }
    }
}
