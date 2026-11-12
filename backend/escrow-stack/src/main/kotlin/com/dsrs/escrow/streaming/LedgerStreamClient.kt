package com.dsrs.escrow.streaming

import com.daml.ledger.api.v1.TransactionServiceOuterClass.GetTransactionsResponse
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.Identifier
import com.dsrs.common.ledger.LedgerConfig
import com.dsrs.common.ledger.LedgerStreamClientBase
import com.dsrs.daml.query.QueryProposal
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

sealed class LedgerEvent {
    abstract val offset: String

    data class QueryProposalCreated(
        override val offset: String,
        val contractId: String,
        val proposal: QueryProposal
    ) : LedgerEvent()

    data class QueryProposalArchived(
        override val offset: String,
        val contractId: String
    ) : LedgerEvent()
}

data class ActiveQueryProposal(
    val contractId: String,
    val proposal: QueryProposal
)

class LedgerStreamClient(
    config: LedgerConfig
) : LedgerStreamClientBase(config) {

    override fun getTemplateIds(): List<Identifier> = listOf(QueryProposal.TEMPLATE_ID)

    fun getActiveContracts(escrowParty: String): Sequence<Pair<ActiveQueryProposal, String>> = sequence {
        getActiveContractsRaw(escrowParty).forEach { (response, offset) ->
            response.activeContractsList.forEach { protoEvent ->
                try {
                    val javaEvent = CreatedEvent.fromProto(protoEvent)
                    val contract = QueryProposal.COMPANION.fromCreatedEvent(javaEvent)
                    val activeContract = ActiveQueryProposal(
                        contractId = protoEvent.contractId,
                        proposal = contract.data
                    )
                    yield(activeContract to offset)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to decode QueryProposal contract ${protoEvent.contractId}" }
                }
            }
        }
    }

    fun streamUpdates(
        escrowParty: String,
        beginOffset: String?,
        onEvent: (LedgerEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: () -> Unit
    ) {
        streamTransactionsRaw(
            party = escrowParty,
            beginOffset = beginOffset,
            onResponse = { response -> processTransactionResponse(response, onEvent) },
            onError = onError,
            onComplete = onComplete
        )
    }

    private fun processTransactionResponse(
        response: GetTransactionsResponse,
        onEvent: (LedgerEvent) -> Unit
    ) {
        response.transactionsList.forEach { transaction ->
            val offset = transaction.offset

            transaction.eventsList.forEach { event ->
                when {
                    event.hasCreated() -> {
                        val created = event.created
                        if (created.templateId.entityName == "QueryProposal") {
                            try {
                                val javaEvent = CreatedEvent.fromProto(created)
                                val contract = QueryProposal.COMPANION.fromCreatedEvent(javaEvent)
                                onEvent(LedgerEvent.QueryProposalCreated(
                                    offset = offset,
                                    contractId = created.contractId,
                                    proposal = contract.data
                                ))
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to decode QueryProposal ${created.contractId}" }
                            }
                        }
                    }
                    event.hasArchived() -> {
                        val archived = event.archived
                        if (archived.templateId.entityName == "QueryProposal") {
                            onEvent(LedgerEvent.QueryProposalArchived(
                                offset = offset,
                                contractId = archived.contractId
                            ))
                        }
                    }
                }
            }
        }
    }
}
