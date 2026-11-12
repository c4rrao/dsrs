package com.dsrs.escrow.config

data class EscrowConfig(
    val escrowParty: String = System.getenv("ESCROW_PARTY")
        ?: error("ESCROW_PARTY environment variable must be set"),
    val apiKey: String = System.getenv("ESCROW_API_KEY")
        ?: error("ESCROW_API_KEY environment variable must be set"),
    val autoAcceptProposals: Boolean = System.getenv("AUTO_ACCEPT_PROPOSALS")?.toBoolean() ?: true,
    val syncOnStartup: Boolean = System.getenv("SYNC_ON_STARTUP")?.toBoolean() ?: true,
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8082
)
