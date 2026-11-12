package com.dsrs.auditor.streaming

import com.daml.ledger.api.v1.EventOuterClass
import com.daml.ledger.api.v1.TransactionServiceOuterClass.GetTransactionTreesResponse
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
import com.dsrs.daml.token.mint.MintEntitlement
import com.dsrs.daml.query.QueryRequest
import com.dsrs.daml.query.QueryResult
import com.dsrs.daml.reward.Reward
import com.dsrs.daml.reward.RewardGroup
import com.dsrs.daml.token.holding.Holding
import com.dsrs.daml.token.holding.HoldingOffer
import com.dsrs.daml.audit.AuditRequest
import com.dsrs.daml.audit.AuditProposal
import com.dsrs.daml.audit.FundedAuditRequest
import com.dsrs.daml.audit.AuditResult
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
    data class MintEntitlementContract(val data: MintEntitlement) : TypedContract()
    data class AuditRequestContract(val data: AuditRequest) : TypedContract()
    data class AuditProposalContract(val data: AuditProposal) : TypedContract()
    data class FundedAuditRequestContract(val data: FundedAuditRequest) : TypedContract()
    data class AuditResultContract(val data: AuditResult) : TypedContract()
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

    /**
     * Only produced from the tree stream. A root exercise was submitted directly; a non-root one
     * was caused by the choice above it, which is what tells a mint produced by a workflow from one
     * the operator simply asked for.
     */
    data class ChoiceExercised(
        override val offset: String,
        val contractId: String,
        val templateName: String,
        val choice: String,
        val actingParties: List<String>,
        val isRoot: Boolean,
        val childEventIds: List<String>,
        val eventId: String
    ) : LedgerEvent()
}

data class ActiveContractData(
    val contractId: String,
    val templateName: String,
    val typedContract: TypedContract
)

class AuditorStreamClient(
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
        PricingConfig.TEMPLATE_ID,
        // The entitlement is archived in the transaction that creates it, so it never appears in
        // the ACS: mints are only recorded while the auditor is streaming.
        MintEntitlement.TEMPLATE_ID,
        AuditRequest.TEMPLATE_ID,
        AuditProposal.TEMPLATE_ID,
        FundedAuditRequest.TEMPLATE_ID,
        AuditResult.TEMPLATE_ID
    )

    fun getActiveContracts(auditorParty: String): Sequence<Pair<ActiveContractData, String>> = sequence {
        getActiveContractsRaw(auditorParty).forEach { (response, offset) ->
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

    // Events are delivered per Daml transaction, so e.g. a RewardGroup archival can be correlated
    // with the transfers it produced.
    fun streamUpdates(
        auditorParty: String,
        beginOffset: String?,
        onTransaction: (offset: String, events: List<LedgerEvent>) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: () -> Unit
    ) {
        streamTransactionTreesRaw(
            party = auditorParty,
            beginOffset = beginOffset,
            onResponse = { response -> processTransactionTreeResponse(response, onTransaction) },
            onError = onError,
            onComplete = onComplete
        )
    }

    /**
     * Flattens a tree into the same LedgerEvent list, plus the exercise nodes. Children are walked
     * depth-first after their parent, so nesting is visible. Templates are filtered here because
     * tree streams cannot filter by template; exercises are kept regardless, since a choice on an
     * untracked contract can be the parent of one that matters.
     */
    private fun processTransactionTreeResponse(
        response: GetTransactionTreesResponse,
        onTransaction: (offset: String, events: List<LedgerEvent>) -> Unit
    ) {
        val tracked = getTemplateIds().map { it.entityName }.toSet()

        response.transactionsList.forEach { tx ->
            val offset = tx.offset
            val byId = tx.eventsByIdMap
            val rootIds = tx.rootEventIdsList.toSet()
            val events = mutableListOf<LedgerEvent>()

            fun walk(eventId: String) {
                val treeEvent = byId[eventId] ?: return
                when {
                    treeEvent.hasCreated() -> {
                        val created = treeEvent.created
                        val templateName = created.templateId.entityName
                        if (templateName in tracked) {
                            val typedContract = decodeCreatedEvent(created)
                            if (typedContract != null) {
                                events += LedgerEvent.ContractCreated(
                                    offset = offset,
                                    contractId = created.contractId,
                                    templateName = templateName,
                                    typedContract = typedContract
                                )
                            }
                        }
                    }
                    treeEvent.hasExercised() -> {
                        val exercised = treeEvent.exercised
                        val templateName = exercised.templateId.entityName

                        events += LedgerEvent.ChoiceExercised(
                            offset = offset,
                            contractId = exercised.contractId,
                            templateName = templateName,
                            choice = exercised.choice,
                            actingParties = exercised.actingPartiesList.toList(),
                            isRoot = eventId in rootIds,
                            childEventIds = exercised.childEventIdsList.toList(),
                            eventId = eventId
                        )

                        // trees report the consuming exercise, not an archived event, so synthesize it
                        if (exercised.consuming && templateName in tracked) {
                            events += LedgerEvent.ContractArchived(
                                offset = offset,
                                contractId = exercised.contractId,
                                templateName = templateName
                            )
                        }

                        exercised.childEventIdsList.forEach { walk(it) }
                    }
                }
            }

            tx.rootEventIdsList.forEach { walk(it) }

            if (events.isNotEmpty()) onTransaction(offset, events)
        }
    }

    private fun processTransactionResponse(
        response: GetTransactionsResponse,
        onTransaction: (offset: String, events: List<LedgerEvent>) -> Unit
    ) {
        response.transactionsList.forEach { tx ->
            val offset = tx.offset
            val events = mutableListOf<LedgerEvent>()

            tx.eventsList.forEach { event ->
                when {
                    event.hasCreated() -> {
                        val created = event.created
                        val templateName = created.templateId.entityName
                        val typedContract = decodeCreatedEvent(created)
                        if (typedContract != null) {
                            events += LedgerEvent.ContractCreated(
                                offset = offset,
                                contractId = created.contractId,
                                templateName = templateName,
                                typedContract = typedContract
                            )
                        }
                    }
                    event.hasArchived() -> {
                        val archived = event.archived
                        events += LedgerEvent.ContractArchived(
                            offset = offset,
                            contractId = archived.contractId,
                            templateName = archived.templateId.entityName
                        )
                    }
                }
            }

            if (events.isNotEmpty()) {
                onTransaction(offset, events)
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
                "MintEntitlement" -> TypedContract.MintEntitlementContract(
                    MintEntitlement.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "AuditRequest" -> TypedContract.AuditRequestContract(
                    AuditRequest.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "AuditProposal" -> TypedContract.AuditProposalContract(
                    AuditProposal.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "FundedAuditRequest" -> TypedContract.FundedAuditRequestContract(
                    FundedAuditRequest.COMPANION.fromCreatedEvent(javaEvent).data
                )
                "AuditResult" -> TypedContract.AuditResultContract(
                    AuditResult.COMPANION.fromCreatedEvent(javaEvent).data
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
