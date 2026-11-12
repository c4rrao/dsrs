package com.dsrs.auditor.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.token.mint.TokenMintAuthority
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class BootstrapService(
    private val ledgerClient: LedgerClient,
    private val auditorParty: String,
    private val operatorParty: String
) {
    fun ensureTokenMintAuthorityExists(): Boolean {
        logger.info { "Checking for existing TokenMintAuthority contract..." }

        val existing = ledgerClient.queryContractsTyped(
            auditorParty,
            TokenMintAuthority.COMPANION
        )

        if (existing.isNotEmpty()) {
            logger.info { "TokenMintAuthority already exists: ${existing.first().id.contractId}" }
            return false
        }

        logger.info { "Creating TokenMintAuthority contract..." }

        val tokenMintAuthority = TokenMintAuthority(operatorParty, auditorParty)
        val update = tokenMintAuthority.create()

        val result = ledgerClient.submitUpdate(auditorParty, update)
        val contractId = result.getCreatedContractIds().firstOrNull()

        logger.info { "Created TokenMintAuthority contract: $contractId" }
        return true
    }

    fun bootstrapAll() {
        logger.info { "Starting auditor bootstrap..." }

        try {
            ensureTokenMintAuthorityExists()
            logger.info { "Auditor bootstrap completed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Auditor bootstrap failed" }
            throw e
        }
    }
}
