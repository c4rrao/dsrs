package com.dsrs.common.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

// Salted SHA-256. A real deployment would use bcrypt or argon2.
object PasswordHasher {

    private const val SALT_LENGTH = 16
    private val random = SecureRandom()

    // "salt:hash"
    fun hash(password: String): String {
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)
        val saltBase64 = Base64.getEncoder().encodeToString(salt)

        val hash = hashWithSalt(password, salt)
        return "$saltBase64:$hash"
    }

    fun verify(password: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2) return false

        val salt = Base64.getDecoder().decode(parts[0])
        val expectedHash = parts[1]
        val actualHash = hashWithSalt(password, salt)

        return actualHash == expectedHash
    }

    private fun hashWithSalt(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hash = digest.digest(password.toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }
}
