package com.dsrs.operator.config

data class OperatorConfig(
    val operatorParty: String = System.getenv("OPERATOR_PARTY")
        ?: error("OPERATOR_PARTY environment variable must be set"),
    val adminApiKey: String = System.getenv("ADMIN_API_KEY")
        ?: error("ADMIN_API_KEY environment variable must be set"),

    val auditorParty: String = System.getenv("AUDITOR_PARTY")
        ?: error("AUDITOR_PARTY environment variable must be set"),

    val escrowParty: String = System.getenv("ESCROW_PARTY")
        ?: error("ESCROW_PARTY environment variable must be set"),

    val syncOnStartup: Boolean = System.getenv("SYNC_ON_STARTUP")?.toBoolean() ?: true,
    val autoProcessContributions: Boolean = System.getenv("AUTO_PROCESS_CONTRIBUTIONS")?.toBoolean() ?: true,
    val autoProcessQueries: Boolean = System.getenv("AUTO_PROCESS_QUERIES")?.toBoolean() ?: true,
    val autoProcessRewards: Boolean = System.getenv("AUTO_PROCESS_REWARDS")?.toBoolean() ?: true,

    // How long a priced but unfunded query may hold contribution processing still, which is also
    // the longest a contribution can wait. Too short and quotes expire before consumers fund them.
    val contributionSetLease: java.time.Duration =
        java.time.Duration.ofSeconds(
            System.getenv("CONTRIBUTION_SET_LEASE_SECONDS")?.toLongOrNull() ?: 300L
        ),

    // Backstop for a funded query whose distribution never completes. Normally that takes well
    // under a second.
    val contributionSetPinCeiling: java.time.Duration =
        java.time.Duration.ofSeconds(
            System.getenv("CONTRIBUTION_SET_PIN_CEILING_SECONDS")?.toLongOrNull() ?: 600L
        ),

    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
)
