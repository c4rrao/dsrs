package com.dsrs.escrow.streaming

import com.dsrs.escrow.config.EscrowConfig
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

data class SyncStatus(
    val status: String,  // "idle", "syncing", "streaming", "error"
    val lastSyncTime: LocalDateTime?,
    val proposalsProcessed: Int,
    val proposalsAccepted: Int,
    val errorMessage: String? = null
)

class ProposalSynchronizer(
    private val config: EscrowConfig,
    private val streamClient: LedgerStreamClient,
    private val proposalHandler: ProposalHandler
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    private val _status = AtomicReference("idle")
    private val _lastSyncTime = AtomicReference<LocalDateTime?>(null)
    private val _proposalsProcessed = AtomicInteger(0)
    private val _proposalsAccepted = AtomicInteger(0)
    private val _errorMessage = AtomicReference<String?>(null)
    private val _isReady = AtomicBoolean(false)

    val isReady: Boolean get() = _isReady.get()

    fun getStatus(): SyncStatus = SyncStatus(
        status = _status.get(),
        lastSyncTime = _lastSyncTime.get(),
        proposalsProcessed = _proposalsProcessed.get(),
        proposalsAccepted = _proposalsAccepted.get(),
        errorMessage = _errorMessage.get()
    )

    suspend fun initialSync(): String? = withContext(Dispatchers.IO) {
        logger.info { "Starting initial sync for escrow: ${config.escrowParty}" }
        _status.set("syncing")
        _errorMessage.set(null)

        var lastOffset: String? = null
        var processed = 0
        var accepted = 0

        try {
            streamClient.getActiveContracts(config.escrowParty).forEach { (contract, offset) ->
                processed++
                if (proposalHandler.processProposal(contract.contractId, contract.proposal)) {
                    accepted++
                }
                lastOffset = offset
            }

            _proposalsProcessed.set(processed)
            _proposalsAccepted.set(accepted)
            _lastSyncTime.set(LocalDateTime.now())
            _status.set("streaming")
            _isReady.set(true)

            logger.info { "Initial sync completed: $processed proposals found, $accepted accepted" }
            lastOffset
        } catch (e: Exception) {
            logger.error(e) { "Initial sync failed" }
            _status.set("error")
            _errorMessage.set(e.message)
            throw e
        }
    }

    fun startStreaming(fromOffset: String?) {
        if (streamJob?.isActive == true) {
            logger.warn { "Stream already running" }
            return
        }

        _status.set("streaming")
        streamJob = scope.launch {
            streamClient.streamUpdates(
                escrowParty = config.escrowParty,
                beginOffset = fromOffset,
                onEvent = { event -> handleEvent(event) },
                onError = { error ->
                    logger.error(error) { "Stream error, will retry..." }
                    _errorMessage.set(error.message)
                    scope.launch {
                        delay(5000)
                        startStreaming(fromOffset)
                    }
                },
                onComplete = {
                    logger.info { "Stream completed" }
                }
            )
        }

        logger.info { "Streaming started from offset: ${fromOffset ?: "beginning"}" }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        _status.set("idle")
        logger.info { "Streaming stopped" }
    }

    private fun handleEvent(event: LedgerEvent) {
        try {
            when (event) {
                is LedgerEvent.QueryProposalCreated -> {
                    logger.info { "New QueryProposal detected: ${event.contractId}" }
                    _proposalsProcessed.incrementAndGet()
                    if (proposalHandler.processProposal(event.contractId, event.proposal)) {
                        _proposalsAccepted.incrementAndGet()
                    }
                }
                is LedgerEvent.QueryProposalArchived -> {
                    logger.debug { "QueryProposal archived: ${event.contractId}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling event: $event" }
        }
    }
}
