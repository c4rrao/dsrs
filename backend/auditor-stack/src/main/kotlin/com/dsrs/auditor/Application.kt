package com.dsrs.auditor

import com.dsrs.auditor.config.AuditorConfig
import com.dsrs.auditor.db.*
import com.dsrs.auditor.db.repositories.*
import com.dsrs.auditor.routes.*
import com.dsrs.auditor.services.*
import com.dsrs.auditor.streaming.*
import com.dsrs.common.db.DatabaseConfig
import com.dsrs.common.db.DatabaseManager
import com.dsrs.common.ktor.commonJson
import com.dsrs.common.ktor.installCommonPlugins
import com.dsrs.common.ledger.LedgerClient
import com.dsrs.common.ledger.LedgerConfig
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
    val config = AuditorConfig()
    logger.info { "Starting Auditor Stack on port ${config.port}" }

    embeddedServer(Netty, port = config.port, module = { module(config) })
        .start(wait = true)
}

fun Application.module(config: AuditorConfig = AuditorConfig()) {
    val dbConfig = DatabaseConfig()
    val ledgerConfig = LedgerConfig()

    val dbManager = DatabaseManager(dbConfig)
    dbManager.connect()
    dbManager.createTables(*ALL_TABLES)

    val contractEventRepo = ContractEventRepository()
    val contributionSnapshotRepo = ContributionSnapshotRepository()
    val pricingConfigSnapshotRepo = PricingConfigSnapshotRepository()
    val querySnapshotRepo = QuerySnapshotRepository()
    val rewardSnapshotRepo = RewardSnapshotRepository(commonJson)
    val auditLogRepo = AuditLogRepository()
    val mintObservationRepo = MintObservationRepository()
    val syncStateRepo = SyncStateRepository()

    syncStateRepo.initializeIfNeeded()

    val ledgerClient = LedgerClient(ledgerConfig)
    val streamClient = AuditorStreamClient(ledgerConfig)

    val bootstrapService = BootstrapService(
        ledgerClient = ledgerClient,
        auditorParty = config.auditorParty,
        operatorParty = config.operatorParty
    )

    // replays a consumer's filter over the auditor's own history, so pricing inputs are re-derived
    val queryMatchingService = QueryMatchingService(contributionSnapshotRepo, commonJson)

    // recomputes the delivered statistics over the same replay
    val resultStatisticsService = ResultStatisticsService(commonJson)

    val verificationService = VerificationService(
        ledgerClient = ledgerClient,
        auditorParty = config.auditorParty,
        contributionSnapshotRepo = contributionSnapshotRepo,
        pricingConfigSnapshotRepo = pricingConfigSnapshotRepo,
        querySnapshotRepo = querySnapshotRepo,
        rewardSnapshotRepo = rewardSnapshotRepo,
        auditLogRepo = auditLogRepo,
        queryMatchingService = queryMatchingService,
        resultStatisticsService = resultStatisticsService,
        json = commonJson
    )

    val auditCostService = AuditCostService()

    val auditStandingService = AuditStandingService(
        querySnapshotRepo = querySnapshotRepo,
        rewardSnapshotRepo = rewardSnapshotRepo,
        contributionSnapshotRepo = contributionSnapshotRepo,
        json = commonJson
    )

    val auditRequestHandler = AuditRequestHandler(
        ledgerClient = ledgerClient,
        auditorParty = config.auditorParty,
        costService = auditCostService,
        verificationService = verificationService,
        standingService = auditStandingService
    )

    val historySynchronizer = HistorySynchronizer(
        streamClient = streamClient,
        auditorParty = config.auditorParty,
        contractEventRepo = contractEventRepo,
        contributionSnapshotRepo = contributionSnapshotRepo,
        querySnapshotRepo = querySnapshotRepo,
        pricingConfigSnapshotRepo = pricingConfigSnapshotRepo,
        rewardSnapshotRepo = rewardSnapshotRepo,
        mintObservationRepo = mintObservationRepo,
        syncStateRepo = syncStateRepo,
        auditRequestHandler = auditRequestHandler,
        json = commonJson
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
        healthRoutes(syncStateRepo, auditLogRepo)
        auditRoutes(verificationService, mintObservationRepo)
    }

    // makes sure the TokenMintAuthority exists
    launch {
        try {
            logger.info { "Running auditor bootstrap..." }
            bootstrapService.bootstrapAll()
        } catch (e: Exception) {
            logger.error(e) { "Bootstrap failed - TokenMintAuthority may not exist" }
        }
    }

    if (config.syncOnStartup) {
        launch {
            try {
                logger.info { "Starting full resync on startup..." }
                val lastOffset = runBlocking { historySynchronizer.fullResync() }
                logger.info { "Full resync completed, starting streaming from offset: $lastOffset" }
                syncStateRepo.updateStatus("streaming")
                historySynchronizer.startStreaming(lastOffset)
            } catch (e: Exception) {
                logger.error(e) { "Startup resync failed, will retry when ready endpoint is called" }
            }
        }
    } else {
        logger.info { "Sync on startup disabled, starting streaming from last known offset" }
        val state = syncStateRepo.getState()
        syncStateRepo.updateStatus("streaming")
        historySynchronizer.startStreaming(state.lastOffset)
    }

    environment.monitor.subscribe(ApplicationStopped) {
        logger.info { "Shutting down auditor stack..." }
        historySynchronizer.stopStreaming()
        streamClient.close()
        ledgerClient.close()
        dbManager.close()
    }
}
