package com.dsrs.auditor.streaming

import com.dsrs.auditor.db.repositories.*
import com.dsrs.auditor.services.AuditRequestHandler
import com.dsrs.daml.types.TokenType
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

// Contracts whose creation (or, for RewardGroup, archival) is the event a mint follows from.
// HoldingOffer and Holding are the mint's own output and would make every mint justify itself.
private val MINT_JUSTIFYING_TEMPLATES = setOf("QueryResult", "Contribution", "RewardGroup")

class HistorySynchronizer(
    private val streamClient: AuditorStreamClient,
    private val auditorParty: String,
    private val contractEventRepo: ContractEventRepository,
    private val contributionSnapshotRepo: ContributionSnapshotRepository,
    private val querySnapshotRepo: QuerySnapshotRepository,
    private val pricingConfigSnapshotRepo: PricingConfigSnapshotRepository,
    private val rewardSnapshotRepo: RewardSnapshotRepository,
    private val mintObservationRepo: MintObservationRepository,
    private val syncStateRepo: SyncStateRepository,
    private val auditRequestHandler: AuditRequestHandler?,
    private val json: Json
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    suspend fun fullResync(): String? = withContext(Dispatchers.IO) {
        logger.info { "Starting full resync..." }
        syncStateRepo.updateStatus("syncing")

        var lastOffset: String? = null
        var count = 0

        try {
            streamClient.getActiveContracts(auditorParty).forEach { (contract, offset) ->
                syncContract(contract, offset, LocalDateTime.now())
                lastOffset = offset
                count++

                if (count % 100 == 0) {
                    logger.info { "Synced $count contracts..." }
                }
            }

            syncStateRepo.updateOffset(lastOffset ?: "", count)
            syncStateRepo.updateStatus("completed")
            logger.info { "Full resync completed: $count contracts synced" }

            lastOffset
        } catch (e: Exception) {
            logger.error(e) { "Full resync failed" }
            syncStateRepo.updateStatus("error", e.message)
            throw e
        }
    }

    fun startStreaming(fromOffset: String?) {
        if (streamJob?.isActive == true) {
            logger.warn { "Stream already running" }
            return
        }

        streamJob = scope.launch {
            streamClient.streamUpdates(
                auditorParty = auditorParty,
                beginOffset = fromOffset,
                onTransaction = { offset, events -> runBlocking { handleTransaction(offset, events) } },
                onError = { error ->
                    logger.error(error) { "Stream error, will retry..." }
                    scope.launch {
                        delay(5000)
                        startStreaming(syncStateRepo.getState().lastOffset)
                    }
                },
                onComplete = {
                    logger.info { "Stream completed" }
                }
            )
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    private suspend fun handleTransaction(offset: String, events: List<LedgerEvent>) {
        val now = LocalDateTime.now()

        try {
            events.forEach { event -> processEvent(event, now) }
            correlateRewardDistributions(events)
            recordMints(offset, events, now)

            val createdCount = events.count { it is LedgerEvent.ContractCreated }
            syncStateRepo.updateOffset(offset, createdCount)
        } catch (e: Exception) {
            logger.error(e) { "Error handling transaction at offset $offset" }
        }
    }

    private fun processEvent(event: LedgerEvent, now: LocalDateTime) {
        when (event) {
            is LedgerEvent.ContractCreated -> {
                val payload = serializeContract(event.typedContract)
                contractEventRepo.insert(ContractEventEntity(
                    eventType = "created",
                    contractId = event.contractId,
                    templateName = event.templateName,
                    payload = payload,
                    offset = event.offset,
                    timestamp = now
                ))

                syncContract(
                    ActiveContractData(event.contractId, event.templateName, event.typedContract),
                    event.offset,
                    now
                )

                if (auditRequestHandler != null) {
                    when (event.typedContract) {
                        is TypedContract.AuditRequestContract -> {
                            auditRequestHandler.handleAuditRequestCreated(event.contractId, event.typedContract.data)
                        }
                        is TypedContract.FundedAuditRequestContract -> {
                            auditRequestHandler.handleFundedAuditRequestCreated(event.contractId, event.typedContract.data)
                        }
                        else -> { /* No audit handling needed */ }
                    }
                }
            }
            is LedgerEvent.ChoiceExercised -> {
                // the only place the trail records an action: who exercised what, and whether
                // directly or as a consequence of another choice
                contractEventRepo.insert(ContractEventEntity(
                    eventType = if (event.isRoot) "exercised" else "exercised-nested",
                    contractId = event.contractId,
                    templateName = event.templateName,
                    payload = json.encodeToString(mapOf(
                        "choice" to event.choice,
                        "actingParties" to event.actingParties.joinToString(","),
                        "isRoot" to event.isRoot.toString()
                    )),
                    offset = event.offset,
                    timestamp = now
                ))
            }
            is LedgerEvent.ContractArchived -> {
                contractEventRepo.insert(ContractEventEntity(
                    eventType = "archived",
                    contractId = event.contractId,
                    templateName = event.templateName,
                    payload = null,
                    offset = event.offset,
                    timestamp = now
                ))

                when (event.templateName) {
                    "Contribution" -> {
                        contributionSnapshotRepo.archiveContribution(event.contractId, now)
                        logger.info { "Archived contribution ${event.contractId}" }
                    }
                    "Dispute" -> {
                        // the resolved Contribution is created in the same transaction
                        logger.debug { "Dispute ${event.contractId} resolved" }
                    }
                }
            }
        }
    }

    /**
     * Records every mint and what caused it. ClaimMintEntitlement exercised as a root node was
     * submitted directly; as a child, the choice above it actually ran. Co-occurrence in a
     * transaction can be arranged, nesting cannot.
     *
     * Audit tokens are only earned inside workflows, so one minted from a root exercise has no
     * legitimate explanation. Reward tokens are always minted from the root against payments taken
     * off the platform, so they are not flagged. Nothing stops the operator, the sole signatory of
     * MintEntitlement, from creating an audit-token entitlement directly; this is what catches it.
     */
    private fun recordMints(offset: String, events: List<LedgerEvent>, now: LocalDateTime) {
        val entitlements = events.filterIsInstance<LedgerEvent.ContractCreated>()
            .mapNotNull { (it.typedContract as? TypedContract.MintEntitlementContract)?.data }
        if (entitlements.isEmpty()) return

        val claims = events.filterIsInstance<LedgerEvent.ChoiceExercised>()
            .filter { it.choice == "ClaimMintEntitlement" }

        // the parent of a nested claim names the event that caused the mint
        val causedBy = claims.firstOrNull { !it.isRoot }?.let { claim ->
            events.filterIsInstance<LedgerEvent.ChoiceExercised>()
                .firstOrNull { claim.eventId in it.childEventIds }
                ?.let { "${it.templateName}.${it.choice}" }
                // nested under something untracked, still not a root act
                ?: "nested"
        }

        entitlements.forEach { entitlement ->
            val tokenType = entitlement.tokenType.toString()
            val total = entitlement.recipients.sumOf { it._2.toLong() }

            mintObservationRepo.record(MintObservationEntity(
                transactionOffset = offset,
                tokenType = tokenType,
                totalAmount = total,
                recipientsJson = json.encodeToString(
                    entitlement.recipients.associate { it._1 to it._2.toLong() }
                ),
                accompaniedBy = causedBy,
                reason = entitlement.reason,
                sourceEventId = entitlement.sourceEventId,
                observedAt = now
            ))

            if (tokenType == AUDIT_TOKEN && causedBy == null) {
                logger.error {
                    "Audit tokens minted with no event behind them: $total to " +
                        "${entitlement.recipients.size} recipient(s), declared reason " +
                        "'${entitlement.reason}' / '${entitlement.sourceEventId}', claimed as a " +
                        "root exercise at offset $offset. Audit tokens are never sold, so there " +
                        "is no legitimate way for this to happen."
                }
            }
        }
    }

    // DistributeInGroup pays through Transfer, which creates HoldingOffers in the same transaction
    // as the archival. Those offers are what was actually paid, rather than the formula run again.
    private fun correlateRewardDistributions(events: List<LedgerEvent>) {
        val archivedGroups = events.filterIsInstance<LedgerEvent.ContractArchived>()
            .filter { it.templateName == "RewardGroup" }
        if (archivedGroups.isEmpty()) return

        val createdHoldingOffers = events.filterIsInstance<LedgerEvent.ContractCreated>()
            .mapNotNull { e -> (e.typedContract as? TypedContract.HoldingOfferContract)?.data }

        archivedGroups.forEach { archived ->
            val pending = rewardSnapshotRepo.findPendingByContractId(archived.contractId)
            if (pending == null) {
                logger.warn { "No pending reward snapshot found for archived RewardGroup ${archived.contractId}" }
                return@forEach
            }

            val contributorSet = pending.contributorParties.toSet()
            val recipients = createdHoldingOffers
                .filter { it.tokenType == TokenType.REWARDTOKEN && it.to in contributorSet }
                .map { RewardRecipient(party = it.to, amount = it.amount.toLong()) }

            rewardSnapshotRepo.recordDistribution(archived.contractId, recipients)
            logger.info { "Recorded reward distribution for group ${pending.distributionId}: ${recipients.size} recipients" }
        }
    }

    private fun syncContract(contract: ActiveContractData, offset: String, timestamp: LocalDateTime) {
        when (val typed = contract.typedContract) {
            is TypedContract.ContributionContract -> syncContribution(contract.contractId, typed.data, timestamp)
            is TypedContract.DisputeContract -> {
                val transactionId = typed.data.transactionId.unpack
                val contribution = contributionSnapshotRepo.findCurrentByTransactionId(transactionId)
                if (contribution != null) {
                    contributionSnapshotRepo.updateState(contribution.contractId, "in_dispute")
                    logger.info { "Marked contribution ${contribution.contractId} as in_dispute due to dispute ${contract.contractId}" }
                } else {
                    logger.warn { "Could not find contribution for transaction $transactionId to mark as in_dispute" }
                }
            }
            is TypedContract.PricingConfigContract -> {
                val config = typed.data
                pricingConfigSnapshotRepo.insert(PricingConfigSnapshot(
                    contractId = contract.contractId,
                    version = 1, // informational; lookups use the validFrom/validTo window
                    basePrice = config.basePrice,
                    level1Multiplier = config.level1Multiplier,
                    level2Multiplier = config.level2Multiplier,
                    coverageWeight = config.coverageWeight,
                    coverageSaturation = config.coverageSaturation,
                    narrownessWeight = config.narrownessWeight,
                    trustWeight = config.trustWeight,
                    validFrom = timestamp,
                    validTo = null,
                    createdAt = timestamp
                ))
                logger.info { "Stored pricing config ${contract.contractId} snapshot" }
            }
            is TypedContract.QueryRequestContract -> syncQueryRequest(contract.contractId, typed.data, timestamp)
            is TypedContract.QueryProposalContract -> syncQueryProposal(contract.contractId, typed.data, timestamp)
            is TypedContract.FundedQueryContract -> syncFundedQuery(contract.contractId, typed.data, timestamp)
            is TypedContract.QueryResultContract -> syncQueryResult(contract.contractId, typed.data, timestamp)
            is TypedContract.RewardGroupContract -> syncRewardGroupPending(contract.contractId, typed.data, timestamp)
            else -> logger.debug { "Contract ${contract.templateName} not tracked in snapshots" }
        }
    }

    private fun syncContribution(
        contractId: String,
        data: com.dsrs.daml.contribution.Contribution,
        timestamp: LocalDateTime
    ) {
        val contributorCount = data.contributorsData.size
        val existing = contributionSnapshotRepo.findCurrentByContractId(contractId)
        val version = (existing?.version ?: 0) + 1

        contributionSnapshotRepo.insert(ContributionSnapshotEntity(
            contractId = contractId,
            contributionId = data.contributionId,
            transactionId = data.transactionId.unpack,
            state = "active",
            contributorCount = contributorCount,
            isActive = contributorCount > 1,
            totalTrustScore = data.trustScore.total,
            contributorsDataJson = serializeContributorsData(data.contributorsData),
            contributionDataJson = serializeContributionData(data.contributionData),
            trustScoreJson = serializeTrustScore(data.trustScore),
            disputeDatesJson = json.encodeToString(data.disputeDates.map { it.toString() }),
            version = version,
            validFrom = timestamp
        ))
    }

    private fun syncQueryRequest(
        contractId: String,
        data: com.dsrs.daml.query.QueryRequest,
        timestamp: LocalDateTime
    ) {
        querySnapshotRepo.insert(QuerySnapshotEntity(
            queryId = data.requestId,
            consumer = data.consumer,
            status = "requested",
            queryParamsJson = serializeQueryParams(data.params),
            validFrom = timestamp
        ))
    }

    private fun syncQueryProposal(
        contractId: String,
        data: com.dsrs.daml.query.QueryProposal,
        timestamp: LocalDateTime
    ) {
        querySnapshotRepo.insert(QuerySnapshotEntity(
            queryId = data.proposalId,
            proposalId = data.proposalId,
            consumer = data.consumer,
            escrow = data.signerEscrow.orElse(null),
            status = "proposed",
            tokenCost = data.tokenCost,
            queryParamsJson = serializeQueryParams(data.params),
            pricingMetadataJson = serializePricingMetadata(data.pricingMetadata),
            validFrom = timestamp
        ))
    }

    private fun syncFundedQuery(
        contractId: String,
        data: com.dsrs.daml.query.FundedQuery,
        timestamp: LocalDateTime
    ) {
        querySnapshotRepo.insert(QuerySnapshotEntity(
            queryId = data.queryId,
            proposalId = data.proposalId,
            consumer = data.consumer,
            escrow = data.escrow,
            status = "funded",
            tokenCost = data.tokenCost,
            queryParamsJson = serializeQueryParams(data.params),
            validFrom = timestamp
        ))
    }

    private fun syncRewardGroupPending(
        contractId: String,
        data: com.dsrs.daml.reward.RewardGroup,
        timestamp: LocalDateTime
    ) {
        rewardSnapshotRepo.insertPending(
            distributionId = data.groupId,
            contractId = contractId,
            // Read off the group itself. Going through contributionCid fails once a dispute has
            // recreated the contribution under a new id.
            queryId = data.queryId,
            transactionId = data.transactionId.unpack,
            escrow = data.escrow,
            contributorParties = data.contributorsData.keys.toList(),
            distributionDate = instantToLocalDateTime(data.distributionDate)
        )
    }

    private fun syncQueryResult(
        contractId: String,
        data: com.dsrs.daml.query.QueryResult,
        timestamp: LocalDateTime
    ) {
        querySnapshotRepo.insert(QuerySnapshotEntity(
            queryId = data.resultId,
            proposalId = data.proposalId,
            consumer = data.consumer,
            status = "completed",
            tokenCost = data.tokenCost,
            queryParamsJson = serializeQueryParams(data.params),
            // the statistics as delivered, compared later against the auditor's recomputation
            resultsJson = data.results.joinToString(",", "[", "]") { it.jsonEncoder().intoString() },
            completionDate = instantToLocalDateTime(data.completionDate),
            // the pricing instant, not completionDate; the replay runs at this time
            executionTimestamp = instantToLocalDateTime(data.executionTimestamp),
            validFrom = timestamp
        ))
    }

    private fun serializeContract(typed: TypedContract): String {
        return when (typed) {
            is TypedContract.ContributionContract -> json.encodeToString(mapOf(
                "contributionId" to typed.data.contributionId,
                "transactionId" to typed.data.transactionId.unpack,
                "contributorCount" to typed.data.contributorsData.size.toString()
            ))
            is TypedContract.QueryProposalContract -> json.encodeToString(mapOf(
                "proposalId" to typed.data.proposalId,
                "consumer" to typed.data.consumer,
                "tokenCost" to typed.data.tokenCost.toString()
            ))
            is TypedContract.HoldingContract -> json.encodeToString(mapOf(
                "owner" to typed.data.owner,
                "tokenType" to typed.data.tokenType.name,
                "amount" to typed.data.amount.toString()
            ))
            else -> "{}"
        }
    }

    private fun serializeContributorsData(data: Map<String, com.dsrs.daml.contribution.ContributorData>): String {
        val map = data.mapValues { (_, v) ->
            mapOf(
                "contributor" to v.contributor,
                "roleInTransaction" to v.roleInTransaction.name,
                "disputeCoefficient" to v.disputeCoefficient.toPlainString(),
                "reputation" to v.reputation.toPlainString()
            )
        }
        return json.encodeToString(map)
    }

    // The auditor's own copy of every filterable field. A field left out here is a filter the
    // replay cannot reproduce.
    private fun serializeContributionData(data: com.dsrs.daml.types.ContributionData): String {
        val map = mutableMapOf<String, String?>(
            "salePrice" to data.salePrice.map { it.toPlainString() }.orElse(null),
            "saleDate" to data.saleDate.map { it.toString() }.orElse(null),
            "propertyType" to data.propertyType.orElse(null),
            "squareMeters" to data.squareMeters.map { it.toPlainString() }.orElse(null),
            "numberOfRooms" to data.numberOfRooms.map { it.toString() }.orElse(null),
            "yearBuilt" to data.yearBuilt.map { it.toString() }.orElse(null),
            "energyRating" to data.energyRating.map { it.name }.orElse(null),
            "tags" to data.tags.map { tags -> json.encodeToString(tags) }.orElse(null),
            "postalCode" to data.propertyAddress.flatMap { it.postalCode }.orElse(null),
            "street" to data.propertyAddress.flatMap { it.street }.orElse(null),
            "city" to data.propertyAddress.flatMap { it.city }.orElse(null),
            "country" to data.propertyAddress.flatMap { it.country }.orElse(null)
        )
        return json.encodeToString(map.filterValues { it != null }.mapValues { it.value!! })
    }

    private fun serializeTrustScore(ts: com.dsrs.daml.contribution.TrustScoreTree): String {
        return json.encodeToString(mapOf(
            "total" to ts.total.toPlainString(),
            "buyer" to ts.buyer.roleScore.toPlainString(),
            "seller" to ts.seller.roleScore.toPlainString(),
            "broker" to ts.broker.roleScore.toPlainString(),
            "notary" to ts.notary.roleScore.toPlainString(),
            "taxAuthority" to ts.taxAuthority.roleScore.toPlainString()
        ))
    }

    // The full filter, so the replay can count the matches itself instead of trusting
    // numTransactions. Ranges and lists are stored as JSON sub-documents.
    private fun serializeQueryParams(params: com.dsrs.daml.types.QueryParams): String {
        val map = mutableMapOf(
            "level" to params.level.toString()
        )

        params.trustScore.orElse(null)?.let {
            map["trustScore"] = json.encodeToString(decimalRangeToMap(it))
        }
        params.salePrice.orElse(null)?.let {
            map["salePrice"] = json.encodeToString(decimalRangeToMap(it))
        }
        params.squareMeters.orElse(null)?.let {
            map["squareMeters"] = json.encodeToString(decimalRangeToMap(it))
        }
        params.saleDate.orElse(null)?.let { range ->
            map["saleDate"] = json.encodeToString(rangeToMap(
                range.from.map { it.toString() }.orElse(null),
                range.to.map { it.toString() }.orElse(null)
            ))
        }
        params.yearBuilt.orElse(null)?.let { range ->
            map["yearBuilt"] = json.encodeToString(rangeToMap(
                range.from.map { it.toString() }.orElse(null),
                range.to.map { it.toString() }.orElse(null)
            ))
        }
        params.propertyAddress.orElse(null)?.let { addresses ->
            map["propertyAddress"] = json.encodeToString(addresses.map { address ->
                mapOf(
                    "postalCode" to address.postalCode.orElse(null),
                    "street" to address.street.orElse(null),
                    "city" to address.city.orElse(null),
                    "country" to address.country.orElse(null),
                    "additionalInfo" to address.additionalInfo.orElse(null)
                ).filterValues { it != null }.mapValues { it.value!! }
            })
        }
        params.propertyTypes.orElse(null)?.let { map["propertyTypes"] = json.encodeToString(it) }
        params.energyRating.orElse(null)?.let { ratings ->
            map["energyRating"] = json.encodeToString(ratings.map { it.name })
        }
        params.tags.orElse(null)?.let { map["tags"] = json.encodeToString(it) }
        params.numberOfRooms.orElse(null)?.let { rooms ->
            map["numberOfRooms"] = json.encodeToString(rooms.map { it.toString() })
        }

        return json.encodeToString(map)
    }

    private fun decimalRangeToMap(range: com.dsrs.daml.types.Range<java.math.BigDecimal>): Map<String, String> =
        rangeToMap(
            range.from.map { it.toPlainString() }.orElse(null),
            range.to.map { it.toPlainString() }.orElse(null)
        )

    private fun rangeToMap(from: String?, to: String?): Map<String, String> =
        mapOf("from" to from, "to" to to).filterValues { it != null }.mapValues { it.value!! }

    private fun serializePricingMetadata(metadata: com.dsrs.daml.types.PricingMetadata): String {
        // mirrors PricingMetadata field for field
        val map = mapOf(
            "numTransactions" to metadata.numTransactions.toString(),
            "corpusTransactions" to metadata.corpusTransactions.toString(),
            "addressSelectivity" to metadata.addressSelectivity.toPlainString(),
            "salePriceSelectivity" to metadata.salePriceSelectivity.toPlainString(),
            "saleDateSelectivity" to metadata.saleDateSelectivity.toPlainString(),
            "squareMetersSelectivity" to metadata.squareMetersSelectivity.toPlainString(),
            "yearBuiltSelectivity" to metadata.yearBuiltSelectivity.toPlainString(),
            "roomsSelectivity" to metadata.roomsSelectivity.toPlainString(),
            "propertyTypeSelectivity" to metadata.propertyTypeSelectivity.toPlainString(),
            "energyRatingSelectivity" to metadata.energyRatingSelectivity.toPlainString(),
            "tagsSelectivity" to metadata.tagsSelectivity.toPlainString(),
            "trustQuality" to metadata.trustQuality.toPlainString()
        )
        return json.encodeToString(map)
    }

    private fun instantToLocalDateTime(instant: Instant): LocalDateTime {
        // system zone, to stay comparable with the LocalDateTime.now() timestamps written elsewhere
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    }
}
