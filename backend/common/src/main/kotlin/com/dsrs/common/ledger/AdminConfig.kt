package com.dsrs.common.ledger

/**
 * PartyManagementService is part of the Ledger API and lives on the ledger port. Pointed at
 * Canton's admin API port instead, the stub connects fine and every call then fails with
 * `UNIMPLEMENTED: Method not found`.
 */
data class AdminConfig(
    val host: String = System.getenv("LEDGER_HOST")
        ?: error("LEDGER_HOST must be set"),
    val port: Int = System.getenv("LEDGER_PORT")?.toIntOrNull()
        ?: error("LEDGER_PORT must be set"),
    val useTls: Boolean = System.getenv("LEDGER_USE_TLS")?.toBooleanStrictOrNull()
        ?: false
)
