package com.dsrs.operator.streaming

import com.dsrs.daml.contribution.Contribution
import com.dsrs.daml.types.Address
import com.dsrs.daml.types.ContributionData
import com.dsrs.operator.db.repositories.ContributionEntity
import com.dsrs.operator.db.repositories.ContributionRepository
import com.dsrs.operator.db.repositories.ProcessingLogRepository
import com.dsrs.operator.db.repositories.SyncStateRepository
import com.dsrs.operator.streaming.handlers.EventRouter
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

// Mirrors Contribution and PricingConfig contracts into the database; everything else is read
// from the ledger.
class ContractSynchronizer(
    private val streamClient: LedgerStreamClient,
    private val operatorParty: String,
    private val contributionRepo: ContributionRepository,
    private val pricingConfigRepo: com.dsrs.operator.db.repositories.PricingConfigRepository,
    private val syncStateRepo: SyncStateRepository,
    private val processingLogRepo: ProcessingLogRepository,
    private val eventRouter: EventRouter? = null  // null during the initial resync
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    suspend fun fullResync(): String? = withContext(Dispatchers.IO) {
        logger.info { "Starting full resync..." }
        syncStateRepo.updateStatus("syncing")

        var lastOffset: String? = null
        var count = 0

        try {
            streamClient.getActiveContracts(operatorParty).forEach { (contract, offset) ->
                syncContract(contract)
                lastOffset = offset
                count++

                if (count % 100 == 0) {
                    logger.info { "Synced $count contracts..." }
                }
            }
            if (count % 100 != 0) {
                logger.info { "Synced $count contracts..." }
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
                operatorParty = operatorParty,
                beginOffset = fromOffset,
                onEvent = { event -> runBlocking { handleEvent(event) } },
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

    private suspend fun handleEvent(event: LedgerEvent) {
        try {
            if (eventRouter != null) {
                eventRouter.route(event)
            } else {
                when (event) {
                    is LedgerEvent.ContractCreated -> {
                        syncContract(ActiveContractData(
                            contractId = event.contractId,
                            templateName = event.templateName,
                            typedContract = event.typedContract
                        ))
                        syncStateRepo.updateOffset(event.offset, 0)
                    }
                    is LedgerEvent.ContractArchived -> {
                        deleteContract(event.contractId, event.templateName)
                        syncStateRepo.updateOffset(event.offset, 0)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling event: $event" }
            processingLogRepo.logError(
                eventType = "StreamEvent",
                contractId = when (event) {
                    is LedgerEvent.ContractCreated -> event.contractId
                    is LedgerEvent.ContractArchived -> event.contractId
                },
                action = "sync",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    private fun syncContract(contract: ActiveContractData) {
        when (val typed = contract.typedContract) {
            is TypedContract.ContributionContract -> syncContribution(contract.contractId, typed.data)
            is TypedContract.DisputeContract -> {
                val transactionId = typed.data.transactionId.unpack
                val contribution = contributionRepo.findByTransactionId(transactionId)
                if (contribution != null) {
                    contributionRepo.updateState(contribution.contractId, "in_dispute")
                    logger.info { "Marked contribution ${contribution.contractId} as in_dispute due to dispute ${contract.contractId}" }
                } else {
                    logger.warn { "Could not find contribution for transaction $transactionId to mark as in_dispute" }
                }
            }
            is TypedContract.PricingConfigContract -> {
                val config = typed.data
                pricingConfigRepo.upsert(com.dsrs.operator.db.repositories.PricingConfigEntity(
                    contractId = contract.contractId,
                    version = 1, // informational; findAtTime orders by created_at
                    basePrice = config.basePrice,
                    level1Multiplier = config.level1Multiplier,
                    level2Multiplier = config.level2Multiplier,
                    coverageWeight = config.coverageWeight,
                    coverageSaturation = config.coverageSaturation,
                    narrownessWeight = config.narrownessWeight,
                    trustWeight = config.trustWeight,
                    createdAt = LocalDateTime.now()
                ))
                logger.info { "Stored pricing config ${contract.contractId} in history" }
            }
            else -> logger.debug { "Contract ${contract.contractId} not synced (read from ledger)" }
        }
    }

    private fun syncContribution(contractId: String, data: Contribution) {
        val contributionData = data.contributionData
        val address = contributionData.propertyAddress

        val contributionDataJson = Json.encodeToString(contributionDataToJsonObject(contributionData))
        val contributorsDataJson = Json.encodeToString(contributorsDataToJsonObject(data.contributorsData))
        val trustScoreJson = Json.encodeToString(trustScoreToJsonObject(data.trustScore))
        val disputeDatesJson = Json.encodeToString(buildJsonArray { data.disputeDates.forEach { add(JsonPrimitive(it.toString())) } })
        val tagsJson = contributionData.tags.map { tags -> Json.encodeToString(buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } }) }.orElse(null)

        contributionRepo.upsert(ContributionEntity(
            contractId = contractId,
            contributionId = data.contributionId,
            transactionId = data.transactionId.unpack,
            state = "active",
            status = "confirmed",
            contributionDataJson = contributionDataJson,
            contributorsDataJson = contributorsDataJson,
            // the corroboration filter reads this, so a merge is what makes a record sellable
            contributorCount = data.contributorsData.size,
            trustScoreJson = trustScoreJson,
            totalTrustScore = data.trustScore.total,
            originalSubmissionDate = instantToLocalDateTime(data.originalSubmissionDate),
            disputeDatesJson = disputeDatesJson,
            propertyCity = address.flatMap { it.city }.orElse(null),
            propertyCountry = address.flatMap { it.country }.orElse(null),
            propertyPostalCode = address.flatMap { it.postalCode }.orElse(null),
            salePrice = contributionData.salePrice.orElse(null),
            saleDate = contributionData.saleDate.orElse(null),
            propertyType = contributionData.propertyType.orElse(null),
            squareMeters = contributionData.squareMeters.orElse(null),
            numberOfRooms = contributionData.numberOfRooms.map { it.toInt() }.orElse(null),
            yearBuilt = contributionData.yearBuilt.map { it.toInt() }.orElse(null),
            energyRating = contributionData.energyRating.map { it.name }.orElse(null),
            tagsJson = tagsJson,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
            archivedAt = null
        ))
    }

    private fun contributionDataToJsonObject(data: ContributionData): JsonObject {
        return buildJsonObject {
            put("propertyAddress", data.propertyAddress.map { addressToJsonObject(it) as JsonElement }.orElse(JsonNull))
            put("salePrice", data.salePrice.map { JsonPrimitive(it.toPlainString()) as JsonElement }.orElse(JsonNull))
            put("saleDate", data.saleDate.map { JsonPrimitive(it.toString()) as JsonElement }.orElse(JsonNull))
            put("propertyType", data.propertyType.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
            put("squareMeters", data.squareMeters.map { JsonPrimitive(it.toPlainString()) as JsonElement }.orElse(JsonNull))
            put("numberOfRooms", data.numberOfRooms.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
            put("yearBuilt", data.yearBuilt.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
            put("energyRating", data.energyRating.map { JsonPrimitive(it.name) as JsonElement }.orElse(JsonNull))
            put("tags", data.tags.map { tags -> buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } } as JsonElement }.orElse(JsonNull))
            put("additionalInfo", data.additionalInfo.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
        }
    }

    private fun addressToJsonObject(address: Address): JsonObject {
        return buildJsonObject {
            put("postalCode", address.postalCode.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
            put("street", address.street.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
            put("city", address.city.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
            put("country", address.country.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
            put("additionalInfo", address.additionalInfo.map { JsonPrimitive(it) as JsonElement }.orElse(JsonNull))
        }
    }

    private fun contributorsDataToJsonObject(data: Map<String, com.dsrs.daml.contribution.ContributorData>): JsonObject {
        return buildJsonObject {
            data.forEach { (key, v) ->
                put(key, buildJsonObject {
                    put("contributor", JsonPrimitive(v.contributor))
                    put("roleInTransaction", JsonPrimitive(v.roleInTransaction.name))
                    put("disputeCoefficient", JsonPrimitive(v.disputeCoefficient.toPlainString()))
                    put("reputation", JsonPrimitive(v.reputation.toPlainString()))
                })
            }
        }
    }

    private fun trustScoreToJsonObject(trustScore: com.dsrs.daml.contribution.TrustScoreTree): JsonObject {
        return buildJsonObject {
            put("total", JsonPrimitive(trustScore.total.toPlainString()))
            put("buyer", JsonPrimitive(trustScore.buyer.roleScore.toPlainString()))
            put("seller", JsonPrimitive(trustScore.seller.roleScore.toPlainString()))
            put("broker", JsonPrimitive(trustScore.broker.roleScore.toPlainString()))
            put("notary", JsonPrimitive(trustScore.notary.roleScore.toPlainString()))
            put("taxAuthority", JsonPrimitive(trustScore.taxAuthority.roleScore.toPlainString()))
        }
    }

    private fun instantToLocalDateTime(instant: Instant): LocalDateTime {
        // system zone, to stay comparable with the LocalDateTime.now() timestamps written elsewhere
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    }

    private fun deleteContract(contractId: String, entityName: String) {
        when (entityName) {
            "Contribution" -> {
                contributionRepo.archiveContribution(contractId, LocalDateTime.now())
                logger.info { "Archived contribution $contractId" }
            }
            "Dispute" -> {
                // the resolved Contribution is created in the same transaction and synced on its own
                logger.debug { "Dispute $contractId resolved" }
            }
            else -> logger.debug { "Contract $contractId ($entityName) deletion not synced (read from ledger)" }
        }
    }
}
