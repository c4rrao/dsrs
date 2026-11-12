package com.dsrs.common.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import mu.KotlinLogging
import java.util.*
import javax.crypto.SecretKey

private val logger = KotlinLogging.logger {}

class JwtService(private val config: JwtConfig) {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(config.secret.toByteArray())

    fun generateToken(userId: String, party: String): String {
        val now = Date()
        val expiration = Date(now.time + config.expirationTime.toMillis())

        logger.debug { "Generating token for user: $userId, party: $party" }

        return Jwts.builder()
            .issuer(config.issuer)
            .subject(userId)
            .audience().add(config.audience).and()
            .claim("party", party)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(secretKey)
            .compact()
    }

    fun validateAndGetParty(token: String): String? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(config.issuer)
                .requireAudience(config.audience)
                .build()
                .parseSignedClaims(token)
                .payload

            claims.get("party", String::class.java)
        } catch (e: Exception) {
            logger.warn { "Token validation failed: ${e.message}" }
            null
        }
    }
}
