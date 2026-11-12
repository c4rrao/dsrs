package com.dsrs.operator.streaming.handlers

import com.dsrs.daml.contribution.Contribution
import com.dsrs.daml.types.Address
import com.dsrs.daml.types.ContributionData
import com.dsrs.operator.db.repositories.ContributionEntity
import com.dsrs.operator.db.repositories.ContributionRepository
import com.dsrs.operator.db.repositories.SyncStateRepository
import com.dsrs.operator.streaming.LedgerEvent
import com.dsrs.operator.streaming.TypedContract
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

class SyncHandler(
    private val contributionRepo: ContributionRepository,
    private val syncStateRepo: SyncStateRepository
) : EventHandler {

    override suspend fun handleCreated(event: LedgerEvent.ContractCreated) {
        val templateName = event.templateName
        logger.debug { "Syncing created contract: $templateName (${event.contractId})" }

        syncContract(event.contractId, event.typedContract)
        syncStateRepo.updateOffset(event.offset, 0)
        syncStateRepo.incrementContractsSynced()
    }

    override suspend fun handleArchived(event: LedgerEvent.ContractArchived) {
        val templateName = event.templateName
        logger.debug { "Syncing archived contract: $templateName (${event.contractId})" }

        deleteContract(event.contractId, templateName)
        syncStateRepo.updateOffset(event.offset, 0)
    }

    private fun syncContract(contractId: String, typed: TypedContract) {
        when (typed) {
            is TypedContract.ContributionContract -> syncContribution(contractId, typed.data)
            else -> logger.debug { "Contract $contractId not synced (read from ledger)" }
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
            updatedAt = LocalDateTime.now()
        ))
    }

    private fun deleteContract(contractId: String, entityName: String) {
        when (entityName) {
            "Contribution" -> contributionRepo.delete(contractId)
            else -> logger.debug { "Contract $contractId ($entityName) deletion not synced (read from ledger)" }
        }
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
}
