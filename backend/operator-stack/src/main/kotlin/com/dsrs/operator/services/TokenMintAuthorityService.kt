package com.dsrs.operator.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.token.mint.TokenMintAuthority
import com.dsrs.operator.config.OperatorConfig
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// Caches the id of the TokenMintAuthority the auditor creates once at bootstrap.
class TokenMintAuthorityService(
    private val config: OperatorConfig,
    private val ledgerClient: LedgerClient
) {
    @Volatile
    private var cachedAuthorityCid: TokenMintAuthority.ContractId? = null

    fun getAuthorityCid(): TokenMintAuthority.ContractId {
        cachedAuthorityCid?.let { return it }

        synchronized(this) {
            cachedAuthorityCid?.let { return it }

            logger.info { "Fetching TokenMintAuthority contract..." }

            val contracts = ledgerClient.queryContractsTyped(
                config.operatorParty,
                TokenMintAuthority.COMPANION
            )

            val contract = contracts.firstOrNull()
                ?: throw IllegalStateException(
                    "TokenMintAuthority contract not found - has the auditor bootstrapped the system?"
                )

            val cid = contract.id
            cachedAuthorityCid = cid

            logger.info { "Cached TokenMintAuthority contract ID: ${cid.contractId}" }
            return cid
        }
    }

    fun invalidateCache() {
        logger.info { "Invalidating TokenMintAuthority cache" }
        cachedAuthorityCid = null
    }
}
