package com.dsrs.auditor.config

data class AuditorConfig(
    val auditorParty: String = System.getenv("AUDITOR_PARTY")
        ?: error("AUDITOR_PARTY environment variable must be set"),
    val adminApiKey: String = System.getenv("ADMIN_API_KEY")
        ?: error("ADMIN_API_KEY environment variable must be set"),

    val operatorParty: String = System.getenv("OPERATOR_PARTY")
        ?: error("OPERATOR_PARTY environment variable must be set"),

    val syncOnStartup: Boolean = System.getenv("SYNC_ON_STARTUP")?.toBoolean() ?: true,

    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8103
)
