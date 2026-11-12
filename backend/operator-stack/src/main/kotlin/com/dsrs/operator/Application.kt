package com.dsrs.operator

import com.dsrs.common.db.DatabaseConfig
import com.dsrs.common.db.DatabaseManager
import com.dsrs.common.ktor.commonJson
import com.dsrs.common.ktor.installCommonPlugins
import com.dsrs.common.ledger.LedgerClient
import com.dsrs.common.ledger.LedgerConfig
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.*
import com.dsrs.operator.db.repositories.*
import com.dsrs.operator.routes.*
import com.dsrs.operator.services.*
import com.dsrs.operator.streaming.*
import com.dsrs.operator.streaming.handlers.*
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
    val config = OperatorConfig()
    logger.info { "Starting Operator Stack on port ${config.port}" }

    embeddedServer(Netty, port = config.port, module = { module(config) })
        .start(wait = true)
}

fun Application.module(config: OperatorConfig = OperatorConfig()) {
    val dbConfig = DatabaseConfig()
    val ledgerConfig = LedgerConfig()

    val dbManager = DatabaseManager(dbConfig)
    dbManager.connect()
    dbManager.createTables(*ALL_TABLES)

    val contributionRepo = ContributionRepository(commonJson)
    val pricingConfigRepo = PricingConfigRepository()
    val syncStateRepo = SyncStateRepository()
    val processingLogRepo = ProcessingLogRepository()
    val queryProposalRepo = QueryProposalRepository()

    syncStateRepo.initializeIfNeeded()

    val ledgerClient = LedgerClient(ledgerConfig)
    val streamClient = LedgerStreamClient(ledgerConfig)

    val tokenMintAuthorityService = TokenMintAuthorityService(config, ledgerClient)
    val insightService = InsightService(contributionRepo)
    val disputeService = DisputeService(config, ledgerClient)
    val contributionService = ContributionService(config, ledgerClient, contributionRepo, disputeService, tokenMintAuthorityService)
    // Serializes query pricing against contribution processing. Shared by everything on either side.
    val ledgerLock = LedgerLock(config.contributionSetLease, config.contributionSetPinCeiling)
    val queryService = QueryService(config, ledgerClient, insightService, tokenMintAuthorityService, queryProposalRepo, contributionRepo, ledgerLock)
    val rewardService = RewardService(config, ledgerClient, tokenMintAuthorityService, queryProposalRepo, ledgerLock)

    val syncHandler = SyncHandler(contributionRepo, syncStateRepo)
    val contributionHandler = ContributionHandler(config, contributionService, processingLogRepo, ledgerLock)
    val queryHandler = QueryHandler(config, queryService, processingLogRepo)
    val rewardHandler = RewardHandler(config, rewardService, processingLogRepo)
    val eventRouter = EventRouter(contributionHandler, queryHandler, rewardHandler, syncHandler)

    val synchronizer = ContractSynchronizer(
        streamClient, config.operatorParty,
        contributionRepo, pricingConfigRepo,
        syncStateRepo, processingLogRepo,
        eventRouter
    )

    installCommonPlugins(additionalCorsHeaders = listOf("X-Admin-Key"))

    install(Authentication) {
        bearer("admin-auth") {
            authenticate { tokenCredential ->
                if (tokenCredential.token == config.adminApiKey) {
                    UserIdPrincipal("admin")
                } else {
                    null
                }
            }
        }
    }

    routing {
        healthRoutes(syncStateRepo)
        contributionRoutes(contributionRepo, ledgerClient, config)
        pricingRoutes(ledgerClient, config)
        mintRoutes(ledgerClient, config.operatorParty, config.auditorParty, tokenMintAuthorityService)
        disputeRoutes(disputeService)
        queryRoutes(ledgerClient, config)
        systemRoutes(syncStateRepo, contributionRepo, processingLogRepo, synchronizer)
    }

    // Retries contribution requests the lock turned away and withdraws expired quotes. It runs
    // apart from the ledger stream, which is sequential and delivers the events that free the lock.
    launch {
        LedgerLockReconciler(
            config = config,
            ledgerClient = ledgerClient,
            contributionService = contributionService,
            processingLogRepo = processingLogRepo,
            ledgerLock = ledgerLock
        ).run()
    }

    if (config.syncOnStartup) {
        launch {
            try {
                logger.info { "Starting full resync on startup..." }
                val lastOffset = runBlocking { synchronizer.fullResync() }
                logger.info { "Full resync completed, starting streaming from offset: $lastOffset" }
                synchronizer.startStreaming(lastOffset)
            } catch (e: Exception) {
                logger.error(e) { "Startup resync failed, will retry when ready endpoint is called" }
            }
        }
    } else {
        logger.info { "Sync on startup disabled, starting streaming from last known offset" }
        val state = syncStateRepo.getState()
        synchronizer.startStreaming(state.lastOffset)
    }

    environment.monitor.subscribe(ApplicationStopped) {
        logger.info { "Shutting down operator stack..." }
        synchronizer.stopStreaming()
        streamClient.close()
        ledgerClient.close()
        dbManager.close()
    }
}
