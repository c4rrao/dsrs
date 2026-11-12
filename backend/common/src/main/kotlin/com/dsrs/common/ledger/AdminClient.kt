package com.dsrs.common.ledger

import com.daml.ledger.api.v1.admin.PartyManagementServiceGrpc
import com.daml.ledger.api.v1.admin.PartyManagementServiceOuterClass.AllocatePartyRequest
import com.daml.ledger.api.v1.admin.PartyManagementServiceOuterClass.ListKnownPartiesRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

// Party allocation through the Ledger API PartyManagementService (see AdminConfig).
class AdminClient(
    private val config: AdminConfig
) : AutoCloseable {

    // same inbound limit as LedgerClient
    private val maxInboundBytes = 32 * 1024 * 1024

    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(config.host, config.port)
        .apply { if (!config.useTls) usePlaintext() }
        .maxInboundMessageSize(maxInboundBytes)
        .build()

    private val partyManagementService: PartyManagementServiceGrpc.PartyManagementServiceBlockingStub =
        PartyManagementServiceGrpc.newBlockingStub(channel)

    fun allocateParty(partyIdHint: String, displayName: String): String {
        logger.info { "Allocating party with hint: $partyIdHint, displayName: $displayName" }

        val request = AllocatePartyRequest.newBuilder()
            .setPartyIdHint(partyIdHint)
            .setDisplayName(displayName)
            .build()

        val response = partyManagementService.allocateParty(request)
        val party = response.partyDetails.party

        logger.info { "Allocated party: $party" }
        return party
    }

    // Re-allocating an existing hint throws, so in that case the qualified id ("Alice::1220...")
    // is looked up instead. A bare hint is rejected by every submission.
    fun allocateOrFindParty(partyIdHint: String, displayName: String): String =
        try {
            allocateParty(partyIdHint, displayName)
        } catch (e: Exception) {
            val existing = findPartyByHint(partyIdHint)
            if (existing == null) throw e
            logger.info { "Party $partyIdHint already allocated as $existing" }
            existing
        }

    fun findPartyByHint(partyIdHint: String): String? =
        partyManagementService
            .listKnownParties(ListKnownPartiesRequest.getDefaultInstance())
            .partyDetailsList
            .map { it.party }
            .firstOrNull { it == partyIdHint || it.substringBefore("::") == partyIdHint }

    override fun close() {
        logger.info { "Closing admin client connection" }
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
