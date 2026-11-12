package com.dsrs.common.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

data class DatabaseConfig(
    val host: String = System.getenv("DB_HOST")
        ?: error("DB_HOST environment variable must be set"),
    val port: Int = System.getenv("DB_PORT")?.toIntOrNull()
        ?: error("DB_PORT environment variable must be set (integer)"),
    val database: String = System.getenv("DB_NAME")
        ?: error("DB_NAME environment variable must be set"),
    val user: String = System.getenv("DB_USER")
        ?: error("DB_USER environment variable must be set"),
    val password: String = System.getenv("DB_PASSWORD")
        ?: error("DB_PASSWORD environment variable must be set"),
    val maxPoolSize: Int = System.getenv("DB_POOL_SIZE")?.toIntOrNull() ?: 10
) {
    val jdbcUrl: String
        get() = "jdbc:postgresql://$host:$port/$database"
}

class DatabaseManager(private val config: DatabaseConfig) {

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database

    fun connect(): Database {
        logger.info { "Connecting to database: ${config.jdbcUrl}" }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        dataSource = HikariDataSource(hikariConfig)
        database = Database.connect(dataSource)

        logger.info { "Database connection established" }
        return database
    }

    fun createTables(vararg tables: Table) {
        transaction(database) {
            // `create` only adds whole missing tables, so a column added to an existing table would
            // never appear. This one is additive and never drops anything.
            SchemaUtils.createMissingTablesAndColumns(*tables)
        }
        logger.info { "Ensured schema for ${tables.size} tables" }
    }

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
            logger.info { "Database connection closed" }
        }
    }
}
