package com.dsrs.common.ledger

// USER_ID identifies this backend instance in the commands it submits.
data class LedgerConfig(
    val host: String = System.getenv("LEDGER_HOST")
        ?: error("LEDGER_HOST environment variable must be set"),
    val port: Int = System.getenv("LEDGER_PORT")?.toIntOrNull()
        ?: error("LEDGER_PORT environment variable must be set (integer)"),
    val useTls: Boolean = System.getenv("LEDGER_USE_TLS")?.toBooleanStrictOrNull()
        ?: error("LEDGER_USE_TLS environment variable must be set (true/false)"),
    val userId: String = System.getenv("USER_ID")
        ?: error("USER_ID environment variable must be set")
)
