package com.dsrs.participant.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

enum class UserStatus {
    PENDING,
    APPROVED
}

object Users : Table("users") {
    val id = uuid("id").autoGenerate()
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val party = varchar("party", 255)  // not unique: several users may act as one party
    val status = varchar("status", 20).default("PENDING")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val party: String,
    val status: UserStatus,
    val createdAt: Instant
)

class UserRepository {

    fun createUser(
        username: String,
        passwordHash: String,
        party: String,
        status: UserStatus = UserStatus.PENDING
    ): User? {
        return transaction {
            val exists = Users.select(Users.username eq username).count() > 0

            if (exists) {
                return@transaction null
            }

            val now = Instant.now()
            val insertStatement = Users.insert {
                it[Users.username] = username
                it[Users.passwordHash] = passwordHash
                it[Users.party] = party
                it[Users.status] = status.name
                it[Users.createdAt] = now.toEpochMilli()
            }

            User(
                id = insertStatement[Users.id].toString(),
                username = username,
                passwordHash = passwordHash,
                party = party,
                status = status,
                createdAt = now
            )
        }
    }

    fun findByUsername(username: String): User? {
        return transaction {
            Users.select(Users.username eq username)
                .singleOrNull()
                ?.toUser()
        }
    }

    fun findById(id: String): User? {
        return transaction {
            Users.select(Users.id eq UUID.fromString(id))
                .singleOrNull()
                ?.toUser()
        }
    }

    fun findByStatus(status: UserStatus): List<User> {
        return transaction {
            Users.select(Users.status eq status.name)
                .map { it.toUser() }
        }
    }

    fun findAll(): List<User> {
        return transaction {
            Users.selectAll()
                .map { it.toUser() }
        }
    }

    fun updateStatus(id: String, status: UserStatus): Boolean {
        return transaction {
            val updated = Users.update({ Users.id eq UUID.fromString(id) }) {
                it[Users.status] = status.name
            }
            updated > 0
        }
    }

    /**
     * Registration only stores a party hint ("Alice"); the qualified id Canton allocates is known
     * from approval on, and the ledger rejects anything else.
     */
    fun approveWithParty(id: String, party: String): Boolean {
        return transaction {
            val updated = Users.update({ Users.id eq UUID.fromString(id) }) {
                it[Users.status] = UserStatus.APPROVED.name
                it[Users.party] = party
            }
            updated > 0
        }
    }

    fun deleteUser(id: String): Boolean {
        return transaction {
            val deleted = Users.deleteWhere { Users.id eq UUID.fromString(id) }
            deleted > 0
        }
    }

    private fun ResultRow.toUser(): User = User(
        id = this[Users.id].toString(),
        username = this[Users.username],
        passwordHash = this[Users.passwordHash],
        party = this[Users.party],
        status = UserStatus.valueOf(this[Users.status]),
        createdAt = Instant.ofEpochMilli(this[Users.createdAt])
    )
}
