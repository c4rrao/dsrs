package com.dsrs.operator.streaming

import com.daml.ledger.api.v1.EventOuterClass
import com.daml.ledger.api.v1.TransactionServiceOuterClass.GetTransactionsResponse
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.Identifier
import com.dsrs.common.ledger.LedgerConfig
import com.dsrs.common.ledger.LedgerStreamClientBase
import com.dsrs.daml.contribution.Contribution
import com.dsrs.daml.contribution.ContributionRequest
import com.dsrs.daml.contribution.Dispute
import com.dsrs.daml.pricing.PricingConfig
import com.dsrs.daml.query.FundedQuery
import com.dsrs.daml.query.QueryProposal
import com.dsrs.daml.query.QueryRequest
import com.dsrs.daml.query.QueryResult
import com.dsrs.daml.reward.Reward
import com.dsrs.daml.reward.RewardGroup
import com.dsrs.daml.token.holding.Holding
import com.dsrs.daml.token.holding.HoldingOffer
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

sealed class TypedContract {
    data class ContributionContract(val data: Contribution) : TypedContract()
    data class ContributionRequestContract(val data: ContributionRequest) : TypedContract()
    data class DisputeContract(val data: Dispute) : TypedContract()
    data class QueryRequestContract(val data: QueryRequest) : TypedContract()
    data class QueryProposalContract(val data: QueryProposal) : TypedContract()
    data class FundedQueryContract(val data: FundedQuery) : TypedContract()
    data class QueryResultContract(val data: QueryResult) : TypedContract()
    data class RewardContract(val data: Reward) : TypedContract()
    data class RewardGroupContract(val data: RewardGroup) : TypedContract()
    data class HoldingContract(val data: Holding) : TypedContract()
    data class HoldingOfferContract(val data: HoldingOffer) : TypedContract()
    data class PricingConfigContract(val data: PricingConfig) : TypedContract()
}

sealed class LedgerEvent {
    abstract val offset: String

    data class ContractCreated(
        override val offset: String,
        val contractId: String,
        val templateName: String,
        val typedContract: TypedContract
    ) : LedgerEvent()

    data class ContractArchived(
        override val offset: String,
        val contractId: String,
        val templateName: String
    ) : LedgerEvent()
}

data class ActiveContractData(
    val contractId: String,
    val templateName: String,
    val typedContract: TypedContract
)

class LedgerStreamClient(
    config: LedgerConfig
) : LedgerStreamClientBase(config) {

    override fun getTemplateIds(): List<Identifier> = listOf(
        ContributionRequest.TEMPLATE_ID,
        Contribution.TEMPLATE_ID,
        Dispute.TEMPLATE_ID,
        QueryRequest.TEMPLATE_ID,
        QueryProposal.TEMPLATE_ID,
        FundedQuery.TEMPLATE_ID,
        QueryResult.TEMPLATE_ID,
        Reward.TEMPLATE_ID,
        RewardGroup.TEMPLATE_ID,
        Holding.TEMPLATE_ID,
        HoldingOffer.TEMPLATE_ID,
        PricingConfig.TEMPLATE_ID
    )

    fun getActiveContracts(operatorParty: String): Sequence<Pair<ActiveContractData, String>> = sequence {
        getActiveContractsRaw(operatorParty).forEach { (response, offset) ->
            response.activeContractsList.forEach { protoEvent ->
                val templateName = protoEvent.templateId.entityName

                val typedContract = decodeCreatedEvent(protoEvent)
                if (typedContract != null) {
                    val contract = ActiveContractData(
                        contractId = protoEvent.contractId,
                        templateName = templateName,
                        typedContract = typedContract
                    )
                    yield(contract to offset)
                }
            }
        }
    }

    fun streamUpdates(
        operatorParty: String,
        beginOffset: String?,
        onEvent: (LedgerEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: () -> Unit
    ) {
        streamTransactionsRaw(
            party = operatorParty,
            beginOffset = beginOffset,
            onResponse = { response -> processTransactionResponse(response, onEvent) },
            onError = onError,
            onComplete = onComplete
        )
    }

    private fun processTransactionResponse(response: GetTransactionsResponse, onEvent: (LedgerEvent) -> Unit) {
        response.transactionsList.forEach { transaction ->
            val offset = transaction.offset

            transaction.eventsList.forEach { event ->
                when {
                    event.hasCreated() -> {
                        val created = event.created
                        val templateName = created.templateId.entityName
                        val typedContract = decodeCreatedEvent(created)
                        if (typedContract != null) {
                            onEvent(LedgerEvent.ContractCreated(
                                offset = offset,
                                contractId = created.contractId,
                                templateName = templateName,
                                typedContract = typedContract
                            ))
                        }
                    }
                    event.hasArchived() -> {
                        val archived = event.archived
                        onEvent(LedgerEvent.ContractArchived(
                            offset = offset,
                            contractId = archived.contractId,
                            templateName = archived.templateId.entityName
                        ))
                    }
                }
            }
        }
    }

    private fun decodeCreatedEvent(protoEvent: EventOuterClass.CreatedEvent): TypedContract? {
        return try {
            val javaEvent = CreatedEvent.fromProto(protoEvent)
            when (protoEvent.templateId.entityName) {
                "Contribution" -> TypedContract.ContributionContract(
                    Contribution.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "ContributionRequest" -> TypedContract.ContributionRequestContract(
                    ContributionRequest.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "Dispute" -> TypedContract.DisputeContract(
                    Dispute.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "QueryRequest" -> TypedContract.QueryRequestContract(
                    QueryRequest.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "QueryProposal" -> TypedContract.QueryProposalContract(
                    QueryProposal.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "FundedQuery" -> TypedContract.FundedQueryContract(
                    FundedQuery.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "QueryResult" -> TypedContract.QueryResultContract(
                    QueryResult.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "Reward" -> TypedContract.RewardContract(
                    Reward.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "RewardGroup" -> TypedContract.RewardGroupContract(
                    RewardGroup.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "Holding" -> TypedContract.HoldingContract(
                    Holding.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "HoldingOffer" -> TypedContract.HoldingOfferContract(
                    HoldingOffer.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "PricingConfig" -> TypedContract.PricingConfigContract(
                    PricingConfig.COMPANION.fromCreatedEvent(javaEvent).data
                )
                else -> {
                    logger.debug { "Ignoring unknown contract type: ${protoEvent.templateId.entityName}" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to decode contract ${protoEvent.contractId} of type ${protoEvent.templateId.entityName}" }
            null
        }
    }
}
