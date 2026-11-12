package com.dsrs.escrow

import com.dsrs.common.ktor.installCommonPlugins
import com.dsrs.common.ledger.LedgerClient
import com.dsrs.common.ledger.LedgerConfig
import com.dsrs.escrow.config.EscrowConfig
import com.dsrs.escrow.routes.healthRoutes
import com.dsrs.escrow.streaming.LedgerStreamClient
import com.dsrs.escrow.streaming.ProposalHandler
import com.dsrs.escrow.streaming.ProposalSynchronizer
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val config = EscrowConfig()
    logger.info { "Starting Escrow Stack on port ${config.port}" }
    logger.info { "Escrow party: ${config.escrowParty}" }
    logger.info { "Auto-accept proposals: ${config.autoAcceptProposals}" }

    embeddedServer(Netty, port = config.port, module = { module(config) })
        .start(wait = true)
}

fun Application.module(config: EscrowConfig = EscrowConfig()) {
    val ledgerConfig = LedgerConfig()

    val ledgerClient = LedgerClient(ledgerConfig)
    val streamClient = LedgerStreamClient(ledgerConfig)

    val proposalHandler = ProposalHandler(config, ledgerClient)
    val synchronizer = ProposalSynchronizer(config, streamClient, proposalHandler)

    installCommonPlugins(additionalCorsHeaders = listOf("X-Escrow-Key"))

    install(Authentication) {
        bearer("escrow-auth") {
            authenticate { tokenCredential ->
                if (tokenCredential.token == config.apiKey) {
                    UserIdPrincipal("escrow")
                } else {
                    null
                }
            }
        }
    }

    routing {
        healthRoutes(synchronizer)
    }

    if (config.syncOnStartup) {
        launch {
            try {
                logger.info { "Starting initial sync on startup..." }
                val lastOffset = runBlocking { synchronizer.initialSync() }
                logger.info { "Initial sync completed, starting streaming from offset: $lastOffset" }
                synchronizer.startStreaming(lastOffset)
            } catch (e: Exception) {
                logger.error(e) { "Startup sync failed" }
            }
        }
    } else {
        logger.info { "Sync on startup disabled, starting streaming immediately" }
        synchronizer.startStreaming(null)
    }

    environment.monitor.subscribe(ApplicationStopped) {
        logger.info { "Shutting down escrow stack..." }
        synchronizer.stopStreaming()
        streamClient.close()
        ledgerClient.close()
    }
}
