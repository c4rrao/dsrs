package com.dsrs.common.ledger

import com.daml.ledger.api.v1.ActiveContractsServiceGrpc
import com.daml.ledger.api.v1.ActiveContractsServiceOuterClass.GetActiveContractsRequest
import com.daml.ledger.api.v1.ActiveContractsServiceOuterClass.GetActiveContractsResponse
import com.daml.ledger.api.v1.CommandServiceGrpc
import com.daml.ledger.api.v1.CommandServiceOuterClass.SubmitAndWaitForTransactionResponse
import com.daml.ledger.api.v1.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v1.CommandsOuterClass.Command
import com.daml.ledger.api.v1.CommandsOuterClass.Commands
import com.daml.ledger.api.v1.CommandsOuterClass.CreateCommand
import com.daml.ledger.api.v1.CommandsOuterClass.DisclosedContract
import com.daml.ledger.api.v1.CommandsOuterClass.ExerciseCommand
import com.daml.ledger.api.v1.EventQueryServiceGrpc
import com.daml.ledger.api.v1.EventQueryServiceOuterClass.GetEventsByContractIdRequest
import com.daml.ledger.api.v1.LedgerIdentityServiceGrpc
import com.daml.ledger.api.v1.LedgerIdentityServiceOuterClass.GetLedgerIdentityRequest
import com.daml.ledger.api.v1.LedgerOffsetOuterClass.LedgerOffset
import com.daml.ledger.api.v1.TransactionServiceGrpc
import com.daml.ledger.api.v1.TransactionServiceOuterClass.GetTransactionsRequest
import com.daml.ledger.api.v1.TransactionFilterOuterClass.Filters
import com.daml.ledger.api.v1.TransactionFilterOuterClass.InclusiveFilters
import com.daml.ledger.api.v1.TransactionFilterOuterClass.TransactionFilter
import com.daml.ledger.api.v1.ValueOuterClass.Identifier
import com.daml.ledger.api.v1.ValueOuterClass.Record
import com.daml.ledger.api.v1.ValueOuterClass.RecordField
import com.daml.ledger.api.v1.ValueOuterClass.Value
import com.daml.ledger.javaapi.data.codegen.ContractCompanion
import com.daml.ledger.javaapi.data.codegen.Update
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import mu.KotlinLogging
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

// For a RewardGroup, `stillActive` means unpaid: DistributeInGroup archives the group as it pays.
data class HistoricalContract<C>(val contract: C, val stillActive: Boolean)

class LedgerClient(
    private val config: LedgerConfig
) : AutoCloseable {

    // gRPC's default 4 MiB inbound limit is reached by the tree of a distribution over roughly
    // 1,000 matched transactions. Raised to the domain's max request size (bootstrap.canton).
    private val maxInboundBytes = 32 * 1024 * 1024

    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(config.host, config.port)
        .apply { if (!config.useTls) usePlaintext() }
        .maxInboundMessageSize(maxInboundBytes)
        .build()

    private val commandService: CommandServiceGrpc.CommandServiceBlockingStub =
        CommandServiceGrpc.newBlockingStub(channel)

    private val activeContractsService: ActiveContractsServiceGrpc.ActiveContractsServiceBlockingStub =
        ActiveContractsServiceGrpc.newBlockingStub(channel)

    private val eventQueryService: EventQueryServiceGrpc.EventQueryServiceBlockingStub =
        EventQueryServiceGrpc.newBlockingStub(channel)

    private val transactionService: TransactionServiceGrpc.TransactionServiceBlockingStub =
        TransactionServiceGrpc.newBlockingStub(channel)

    private val ledgerIdentityService: LedgerIdentityServiceGrpc.LedgerIdentityServiceBlockingStub =
        LedgerIdentityServiceGrpc.newBlockingStub(channel)

    private val ledgerId: String by lazy {
        val response = ledgerIdentityService.getLedgerIdentity(
            GetLedgerIdentityRequest.getDefaultInstance()
        )
        response.ledgerId.also {
            logger.info { "Connected to ledger: $it" }
        }
    }

    /**
     * @param disclosedContracts contracts the submitter is not a stakeholder of but the command
     *   has to fetch, e.g. the mint authority in a vote. See [getDisclosedContract].
     */
    fun <T> submitUpdate(
        party: String,
        update: Update<T>,
        disclosedContracts: List<DisclosedContract> = emptyList()
    ): ExerciseResult {
        logger.debug { "Submitting update with ${update.commands().size} commands" }

        val commands = update.commands().map { cmd ->
            when (cmd) {
                is com.daml.ledger.javaapi.data.CreateCommand -> {
                    Command.newBuilder()
                        .setCreate(
                            CreateCommand.newBuilder()
                                .setTemplateId(identifierToProto(cmd.templateId))
                                .setCreateArguments(damlRecordToProto(cmd.createArguments))
                        )
                        .build()
                }
                is com.daml.ledger.javaapi.data.ExerciseCommand -> {
                    Command.newBuilder()
                        .setExercise(
                            ExerciseCommand.newBuilder()
                                .setTemplateId(identifierToProto(cmd.templateId))
                                .setContractId(cmd.contractId)
                                .setChoice(cmd.choice)
                                .setChoiceArgument(damlValueToProto(cmd.choiceArgument))
                        )
                        .build()
                }
                else -> throw IllegalArgumentException("Unsupported command type: ${cmd.javaClass}")
            }
        }

        val response = submitCommands(party, commands, disclosedContracts)
        logger.info { "Submitted update successfully" }
        return ExerciseResult(response)
    }

    /**
     * The explicit-disclosure blob for a contract, produced by a stakeholder [party] so that a
     * non-stakeholder can attach it to its own submission.
     *
     * Read through the active contracts service: on Canton 2.8.12 GetEventsByContractId returns
     * an empty createdEventBlob.
     */
    fun getDisclosedContract(party: String, contractId: String, templateId: Identifier): DisclosedContract? {
        // template ids alone do not fill createdEventBlob; it needs a TemplateFilter with
        // includeCreatedEventBlob set
        val filter = TransactionFilter.newBuilder()
            .putFiltersByParty(
                party,
                Filters.newBuilder()
                    .setInclusive(
                        InclusiveFilters.newBuilder()
                            .addTemplateFilters(
                                com.daml.ledger.api.v1.TransactionFilterOuterClass.TemplateFilter.newBuilder()
                                    .setTemplateId(templateId)
                                    .setIncludeCreatedEventBlob(true)
                            )
                    )
                    .build()
            )
            .build()

        val request = GetActiveContractsRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setFilter(filter)
            .setVerbose(true)
            .build()

        activeContractsService.getActiveContracts(request).forEach { response ->
            response.activeContractsList.forEach { createdEvent ->
                if (createdEvent.contractId == contractId) {
                    return DisclosedContract.newBuilder()
                        .setTemplateId(templateId)
                        .setContractId(contractId)
                        .setCreatedEventBlob(createdEvent.createdEventBlob)
                        .build()
                }
            }
        }

        logger.debug { "No active contract $contractId visible to $party for disclosure" }
        return null
    }

    fun <C : com.daml.ledger.javaapi.data.codegen.Contract<Cid, T>, Cid, T> queryContractsTyped(
        party: String,
        companion: ContractCompanion.WithoutKey<C, Cid, T>
    ): List<C> {
        val templateId = companion.TEMPLATE_ID
        logger.debug { "Querying typed contracts: ${templateId.moduleName}.${templateId.entityName}" }

        val filter = TransactionFilter.newBuilder()
            .putFiltersByParty(
                party,
                Filters.newBuilder()
                    .setInclusive(
                        InclusiveFilters.newBuilder()
                            .addTemplateIds(identifierToProto(templateId))
                    )
                    .build()
            )
            .build()

        val request = GetActiveContractsRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setFilter(filter)
            .setVerbose(true)
            .build()

        val contracts = mutableListOf<C>()
        activeContractsService.getActiveContracts(request).forEach { response ->
            response.activeContractsList.forEach { createdEvent ->
                try {
                    val javaEvent = com.daml.ledger.javaapi.data.CreatedEvent.fromProto(createdEvent)
                    val contract = companion.fromCreatedEvent(javaEvent)
                    contracts.add(contract)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to decode contract ${createdEvent.contractId}" }
                }
            }
        }

        logger.debug { "Found ${contracts.size} typed contracts of type ${templateId.entityName}" }
        return contracts
    }

    /**
     * Every contract of the template the party has been a stakeholder of, active or archived.
     *
     * A contributor needs the groupId of a RewardGroup that DistributeInGroup archived seconds
     * after it was created, so it is not in the ACS any more but is in their own history.
     * Not cached on purpose: the participant stack keeps no ledger state.
     */
    fun <C : com.daml.ledger.javaapi.data.codegen.Contract<Cid, T>, Cid, T> queryContractHistoryTyped(
        party: String,
        companion: ContractCompanion.WithoutKey<C, Cid, T>
    ): List<HistoricalContract<C>> {
        val templateId = companion.TEMPLATE_ID
        logger.debug { "Reading history of ${templateId.moduleName}.${templateId.entityName} for $party" }

        val filter = TransactionFilter.newBuilder()
            .putFiltersByParty(
                party,
                Filters.newBuilder()
                    .setInclusive(
                        InclusiveFilters.newBuilder()
                            .addTemplateIds(identifierToProto(templateId))
                    )
                    .build()
            )
            .build()

        // bounded at LEDGER_END, otherwise the stream stays open and the call never returns
        val request = GetTransactionsRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setFilter(filter)
            .setBegin(LedgerOffset.newBuilder().setBoundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN))
            .setEnd(LedgerOffset.newBuilder().setBoundary(LedgerOffset.LedgerBoundary.LEDGER_END))
            .setVerbose(true)
            .build()

        val created = LinkedHashMap<String, C>()
        val archived = mutableSetOf<String>()

        transactionService.getTransactions(request).forEach { response ->
            response.transactionsList.forEach { transaction ->
                transaction.eventsList.forEach { event ->
                    when {
                        event.hasCreated() -> try {
                            val javaEvent = com.daml.ledger.javaapi.data.CreatedEvent.fromProto(event.created)
                            created[event.created.contractId] = companion.fromCreatedEvent(javaEvent)
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to decode contract ${event.created.contractId}" }
                        }
                        event.hasArchived() -> archived.add(event.archived.contractId)
                    }
                }
            }
        }

        return created.map { (contractId, contract) ->
            HistoricalContract(contract, stillActive = contractId !in archived)
        }
    }

    fun <C : com.daml.ledger.javaapi.data.codegen.Contract<Cid, T>, Cid, T> getContractTyped(
        party: String,
        contractId: String,
        companion: ContractCompanion.WithoutKey<C, Cid, T>
    ): C? {
        logger.debug { "Looking up typed contract by ID: $contractId" }

        val request = GetEventsByContractIdRequest.newBuilder()
            .setContractId(contractId)
            .addRequestingParties(party)
            .build()

        val response = eventQueryService.getEventsByContractId(request)

        if (response.hasArchiveEvent()) {
            logger.debug { "Contract $contractId has been archived" }
            return null
        }

        if (response.hasCreateEvent()) {
            val createEvent = response.createEvent
            return try {
                val javaEvent = com.daml.ledger.javaapi.data.CreatedEvent.fromProto(createEvent)
                companion.fromCreatedEvent(javaEvent)
            } catch (e: Exception) {
                logger.error(e) { "Failed to decode contract $contractId" }
                null
            }
        }

        logger.debug { "Contract $contractId not found" }
        return null
    }

    fun <C : com.daml.ledger.javaapi.data.codegen.Contract<Cid, T>, Cid, T> getContractDataTyped(
        party: String,
        contractId: String,
        companion: ContractCompanion.WithoutKey<C, Cid, T>
    ): T? {
        return getContractTyped(party, contractId, companion)?.data
    }

    private fun submitCommands(
        party: String,
        commandList: List<Command>,
        disclosedContracts: List<DisclosedContract> = emptyList()
    ): SubmitAndWaitForTransactionResponse {
        val commandId = UUID.randomUUID().toString()

        val commandsBuilder = Commands.newBuilder()
            .setLedgerId(ledgerId)
            .setApplicationId(config.userId)
            .setCommandId(commandId)
            .setParty(party)
            .setWorkflowId("dsrs-workflow-${Instant.now().epochSecond}")

        commandList.forEach { commandsBuilder.addCommands(it) }
        commandsBuilder.addAllDisclosedContracts(disclosedContracts)

        val request = SubmitAndWaitRequest.newBuilder()
            .setCommands(commandsBuilder.build())
            .build()

        return commandService.submitAndWaitForTransaction(request)
    }

    override fun close() {
        logger.info { "Closing ledger client connection" }
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun identifierToProto(id: com.daml.ledger.javaapi.data.Identifier): Identifier =
        Identifier.newBuilder()
            .setPackageId(id.packageId)
            .setModuleName(id.moduleName)
            .setEntityName(id.entityName)
            .build()

    private fun damlRecordToProto(damlRecord: com.daml.ledger.javaapi.data.DamlRecord): Record {
        val builder = Record.newBuilder()
        damlRecord.fields.forEach { field ->
            builder.addFields(
                RecordField.newBuilder()
                    .setLabel(field.label.orElse(""))
                    .setValue(damlValueToProto(field.value))
            )
        }
        return builder.build()
    }

    private fun damlValueToProto(value: com.daml.ledger.javaapi.data.Value): Value {
        val builder = Value.newBuilder()
        when (value) {
            is com.daml.ledger.javaapi.data.Text -> builder.setText(value.value)
            is com.daml.ledger.javaapi.data.Int64 -> builder.setInt64(value.value)
            is com.daml.ledger.javaapi.data.Bool -> builder.setBool(value.value)
            is com.daml.ledger.javaapi.data.Party -> builder.setParty(value.value)
            is com.daml.ledger.javaapi.data.ContractId -> builder.setContractId(value.value)
            is com.daml.ledger.javaapi.data.Numeric -> builder.setNumeric(value.value.toPlainString())
            is com.daml.ledger.javaapi.data.DamlRecord -> builder.setRecord(damlRecordToProto(value))
            is com.daml.ledger.javaapi.data.DamlList -> {
                val listBuilder = com.daml.ledger.api.v1.ValueOuterClass.List.newBuilder()
                value.stream().forEach { elem -> listBuilder.addElements(damlValueToProto(elem)) }
                builder.setList(listBuilder.build())
            }
            is com.daml.ledger.javaapi.data.DamlOptional -> {
                val optBuilder = com.daml.ledger.api.v1.ValueOuterClass.Optional.newBuilder()
                value.value.ifPresent { v -> optBuilder.setValue(damlValueToProto(v)) }
                builder.setOptional(optBuilder.build())
            }
            is com.daml.ledger.javaapi.data.DamlEnum -> {
                builder.setEnum(
                    com.daml.ledger.api.v1.ValueOuterClass.Enum.newBuilder()
                        .setConstructor(value.constructor)
                        .build()
                )
            }
            is com.daml.ledger.javaapi.data.Variant -> {
                builder.setVariant(
                    com.daml.ledger.api.v1.ValueOuterClass.Variant.newBuilder()
                        .setConstructor(value.constructor)
                        .setValue(damlValueToProto(value.value))
                        .build()
                )
            }
            is com.daml.ledger.javaapi.data.Timestamp -> builder.setTimestamp(value.getMicroseconds())
            is com.daml.ledger.javaapi.data.Date -> builder.setDate(value.getValue().toEpochDay().toInt())
            is com.daml.ledger.javaapi.data.Unit -> builder.setUnit(com.google.protobuf.Empty.getDefaultInstance())
            else -> builder.setText(value.toString())
        }
        return builder.build()
    }

}

data class ExerciseResult(
    private val response: SubmitAndWaitForTransactionResponse
) {
    val transactionId: String = response.transaction.transactionId

    fun getCreatedContractIds(): List<String> {
        return response.transaction.eventsList
            .filter { it.hasCreated() }
            .map { it.created.contractId }
    }

    fun getCreatedContracts(): List<Pair<String, com.daml.ledger.javaapi.data.Identifier>> {
        return response.transaction.eventsList
            .filter { it.hasCreated() }
            .map { event ->
                val created = event.created
                val templateId = com.daml.ledger.javaapi.data.Identifier(
                    created.templateId.packageId,
                    created.templateId.moduleName,
                    created.templateId.entityName
                )
                created.contractId to templateId
            }
    }

    fun getArchivedContractIds(): List<String> {
        return response.transaction.eventsList
            .filter { it.hasArchived() }
            .map { it.archived.contractId }
    }
}
