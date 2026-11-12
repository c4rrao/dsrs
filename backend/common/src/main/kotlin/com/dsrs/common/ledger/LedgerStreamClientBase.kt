package com.dsrs.common.ledger

import com.daml.ledger.api.v1.ActiveContractsServiceGrpc
import com.daml.ledger.api.v1.ActiveContractsServiceOuterClass.GetActiveContractsRequest
import com.daml.ledger.api.v1.ActiveContractsServiceOuterClass.GetActiveContractsResponse
import com.daml.ledger.api.v1.LedgerIdentityServiceGrpc
import com.daml.ledger.api.v1.LedgerIdentityServiceOuterClass.GetLedgerIdentityRequest
import com.daml.ledger.api.v1.LedgerOffsetOuterClass.LedgerOffset
import com.daml.ledger.api.v1.TransactionFilterOuterClass.Filters
import com.daml.ledger.api.v1.TransactionFilterOuterClass.InclusiveFilters
import com.daml.ledger.api.v1.TransactionFilterOuterClass.TransactionFilter
import com.daml.ledger.api.v1.TransactionServiceGrpc
import com.daml.ledger.api.v1.TransactionServiceOuterClass.GetTransactionTreesResponse
import com.daml.ledger.api.v1.TransactionServiceOuterClass.GetTransactionsRequest
import com.daml.ledger.api.v1.TransactionServiceOuterClass.GetTransactionsResponse
import com.daml.ledger.api.v1.ValueOuterClass.Identifier
import com.daml.ledger.javaapi.data.Identifier as JavaIdentifier
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

abstract class LedgerStreamClientBase(
    protected val config: LedgerConfig
) : AutoCloseable {

    // same inbound limit as LedgerClient
    protected val maxInboundBytes = 32 * 1024 * 1024

    protected val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(config.host, config.port)
        .apply { if (!config.useTls) usePlaintext() }
        .maxInboundMessageSize(maxInboundBytes)
        .build()

    protected val activeContractsService: ActiveContractsServiceGrpc.ActiveContractsServiceBlockingStub =
        ActiveContractsServiceGrpc.newBlockingStub(channel)

    protected val transactionService: TransactionServiceGrpc.TransactionServiceStub =
        TransactionServiceGrpc.newStub(channel)

    private val ledgerIdentityService: LedgerIdentityServiceGrpc.LedgerIdentityServiceBlockingStub =
        LedgerIdentityServiceGrpc.newBlockingStub(channel)

    protected val ledgerId: String by lazy {
        val response = ledgerIdentityService.getLedgerIdentity(
            GetLedgerIdentityRequest.getDefaultInstance()
        )
        response.ledgerId.also {
            logger.info { "Connected to ledger: $it" }
        }
    }

    abstract fun getTemplateIds(): List<JavaIdentifier>

    /** Returns the raw responses; the offset of the last one is where streaming should resume. */
    protected fun getActiveContractsRaw(
        party: String
    ): Sequence<Pair<GetActiveContractsResponse, String>> = sequence {
        logger.info { "Fetching active contracts for party: $party" }

        val filter = buildTransactionFilter(party, getTemplateIds())
        val request = GetActiveContractsRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setFilter(filter)
            .setVerbose(true)
            .build()

        var lastOffset = ""
        activeContractsService.getActiveContracts(request).forEach { response ->
            if (response.offset.isNotEmpty()) {
                lastOffset = response.offset
            }
            if (response.activeContractsCount > 0) {
                yield(response to lastOffset)
            }
        }
    }

    /**
     * Streams transaction trees rather than flat transactions. A flat stream omits contracts
     * created and archived within the same transaction, such as a MintEntitlement claimed inside
     * the choice that created it, and the auditor needs those.
     *
     * Canton rejects a tree subscription with a template filter (INVALID_ARGUMENT), so this
     * subscribes by party only and the caller drops what it does not track.
     */
    protected fun streamTransactionTreesRaw(
        party: String,
        beginOffset: String?,
        onResponse: (GetTransactionTreesResponse) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: () -> Unit
    ) {
        logger.info { "Starting transaction TREE stream from offset: ${beginOffset ?: "beginning"}" }

        val filter = buildWildcardFilter(party)

        val beginLedgerOffset = if (!beginOffset.isNullOrEmpty()) {
            LedgerOffset.newBuilder().setAbsolute(beginOffset).build()
        } else {
            LedgerOffset.newBuilder().setBoundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN).build()
        }

        val request = GetTransactionsRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setFilter(filter)
            .setBegin(beginLedgerOffset)
            .setVerbose(true)
            .build()

        transactionService.getTransactionTrees(request, object : StreamObserver<GetTransactionTreesResponse> {
            override fun onNext(response: GetTransactionTreesResponse) {
                try {
                    onResponse(response)
                } catch (t: Throwable) {
                    logStreamFailure("Error processing transaction tree", t)
                    onError(t)
                }
            }

            override fun onError(t: Throwable) {
                logStreamFailure("Tree stream error", t)
                onError(t)
            }

            override fun onCompleted() {
                logger.info("Tree stream completed")
                onComplete()
            }
        })
    }

    protected fun streamTransactionsRaw(
        party: String,
        beginOffset: String?,
        onResponse: (GetTransactionsResponse) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: () -> Unit
    ) {
        logger.info { "Starting transaction stream from offset: ${beginOffset ?: "beginning"}" }

        val filter = buildTransactionFilter(party, getTemplateIds())

        val beginLedgerOffset = if (!beginOffset.isNullOrEmpty()) {
            LedgerOffset.newBuilder()
                .setAbsolute(beginOffset)
                .build()
        } else {
            LedgerOffset.newBuilder()
                .setBoundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN)
                .build()
        }

        // no end offset, so the stream stays open
        val request = GetTransactionsRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setFilter(filter)
            .setBegin(beginLedgerOffset)
            .setVerbose(true)
            .build()

        transactionService.getTransactions(request, object : StreamObserver<GetTransactionsResponse> {
            override fun onNext(response: GetTransactionsResponse) {
                try {
                    onResponse(response)
                } catch (t: Throwable) {
                    // Throwable, not Exception: an Error (e.g. NoClassDefFoundError after a rebuild
                    // under a running JVM) would otherwise kill the stream thread silently while
                    // the process keeps answering /health. This hands it to the caller's retry.
                    logStreamFailure("Error processing transaction", t)
                    onError(t)
                }
            }

            override fun onError(t: Throwable) {
                logStreamFailure("Stream error", t)
                onError(t)
            }

            override fun onCompleted() {
                logger.info("Stream completed")
                onComplete()
            }
        })
    }

    // The plain slf4j overload on purpose: kotlin-logging's lambda form compiles to its own class,
    // and loading that class is exactly what can fail here after a rebuild.
    private fun logStreamFailure(message: String, t: Throwable) {
        try {
            logger.error(message, t)
        } catch (loggingFailure: Throwable) {
            System.err.println("$message: $t (logging failed: $loggingFailure)")
        }
    }

    // Tree subscriptions only accept a party-wide filter, see streamTransactionTreesRaw.
    protected fun buildWildcardFilter(party: String): TransactionFilter =
        TransactionFilter.newBuilder()
            .putFiltersByParty(party, Filters.getDefaultInstance())
            .build()

    protected fun buildTransactionFilter(party: String, templates: List<JavaIdentifier>): TransactionFilter {
        val inclusiveFilters = InclusiveFilters.newBuilder()
        templates.forEach { templateId ->
            inclusiveFilters.addTemplateIds(identifierToProto(templateId))
        }

        return TransactionFilter.newBuilder()
            .putFiltersByParty(
                party,
                Filters.newBuilder()
                    .setInclusive(inclusiveFilters)
                    .build()
            )
            .build()
    }

    protected fun identifierToProto(id: JavaIdentifier): Identifier =
        Identifier.newBuilder()
            .setPackageId(id.packageId)
            .setModuleName(id.moduleName)
            .setEntityName(id.entityName)
            .build()

    override fun close() {
        logger.info { "Closing ledger stream client" }
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
