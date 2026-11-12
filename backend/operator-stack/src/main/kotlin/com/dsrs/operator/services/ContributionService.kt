package com.dsrs.operator.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.contribution.Contribution
import com.dsrs.daml.contribution.ContributionRequest
import com.dsrs.daml.contribution.ContributorData
import com.dsrs.daml.contribution.Dispute
import com.dsrs.daml.types.Address
import com.dsrs.daml.types.Role
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ContributionEntity
import com.dsrs.operator.db.repositories.ContributionRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

private val logger = KotlinLogging.logger {}

data class ContributionProcessResult(
    val action: String,
    val details: String?,
    val createdContractId: String?
)

/**
 * A contribution row is written as "pending" before the ledger call and confirmed after it, so a
 * second request for the same transaction arriving meanwhile waits instead of creating a
 * competing record.
 */
class ContributionService(
    private val config: OperatorConfig,
    private val ledgerClient: LedgerClient,
    private val contributionRepo: ContributionRepository,
    private val disputeService: DisputeService,
    private val tokenMintAuthorityService: TokenMintAuthorityService
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MAX_PENDING_WAIT_ATTEMPTS = 10
        private const val PENDING_WAIT_DELAY_MS = 500L
    }

    // Same transactionId and identical data merges, different data disputes, a new transactionId
    // is accepted as a new contribution.
    fun processContributionRequest(
        contractId: String,
        request: com.dsrs.daml.contribution.ContributionRequest
    ): ContributionProcessResult {
        val transactionId = request.transactionId.unpack
        val contributor = request.contributor
        val contributionData = request.contributionData

        logger.info { "Processing contribution for transaction: $transactionId from $contributor" }

        // the role is the one the contributor declared, since it decides their coefficient
        val contributorData = buildContributorData(contributor, request.roleInTransaction)

        var attempts = 0
        while (attempts < MAX_PENDING_WAIT_ATTEMPTS) {
            val existing = contributionRepo.findByTransactionId(transactionId)

            when {
                existing == null -> {
                    val existingDispute = disputeService.findDisputeByTransactionId(transactionId)
                    if (existingDispute != null) {
                        val (disputeCid, dispute) = existingDispute
                        if (dispute.contributorsData.containsKey(contributor)) {
                            return rejectAlreadyContributed(contractId, transactionId, contributor,
                                "is already part of the open dispute on this transaction")
                        }
                        logger.info { "Found existing dispute for transaction, joining it" }
                        return joinExistentDispute(contractId, disputeCid, contributorData)
                    }
                    logger.info { "No existing contribution or dispute, accepting as new (attempt ${attempts + 1})" }
                    return acceptNewContributionWithPending(
                        contractId,
                        transactionId,
                        contributionData,
                        contributorData
                    )
                }

                existing.isPending -> {
                    attempts++
                    logger.info { "Found pending contribution, waiting... (attempt $attempts/$MAX_PENDING_WAIT_ATTEMPTS)" }
                    Thread.sleep(PENDING_WAIT_DELAY_MS)
                }

                existing.isConfirmed -> {
                    // The ledger would reject a second submission by the same party, and the failed
                    // command would leave the ContributionRequest unconsumed on the ledger.
                    if (contributorAlreadyPresent(existing.contributorsDataJson, contributor)) {
                        return rejectAlreadyContributed(contractId, transactionId, contributor,
                            "has already contributed to this transaction")
                    }

                    if (dataMatchesStored(existing.contributionDataJson, contributionData)) {
                        logger.info { "Data matches existing contribution, merging" }
                        return mergeContribution(contractId, existing.contractId, contributorData)
                    } else {
                        val existingDispute = disputeService.findDisputeByTransactionId(transactionId)
                        if (existingDispute != null) {
                            val (disputeCid, _) = existingDispute
                            logger.info { "Data differs and dispute already exists, joining it" }
                            return joinExistentDispute(contractId, disputeCid, contributorData)
                        }
                        logger.info { "Data differs from existing contribution, opening dispute" }
                        return openDispute(contractId, existing.contractId, contributorData)
                    }
                }

                else -> {
                    logger.warn { "Unknown contribution status: ${existing.status}" }
                    return openDispute(contractId, existing.contractId, contributorData)
                }
            }
        }

        logger.error { "Timeout waiting for pending contribution to complete for transaction: $transactionId" }
        throw IllegalStateException("Timeout waiting for pending contribution: $transactionId")
    }

    private fun acceptNewContributionWithPending(
        requestContractId: String,
        transactionId: String,
        contributionData: com.dsrs.daml.types.ContributionData,
        contributorData: ContributorData
    ): ContributionProcessResult {
        val pendingContractId = "pending-${UUID.randomUUID()}"
        val pendingContributionId = UUID.randomUUID().toString()
        val now = LocalDateTime.now()

        val contributionDataJson = serializeContributionData(contributionData)
        val contributorsDataJson = serializeContributorData(contributorData)

        val pendingEntity = ContributionEntity(
            contractId = pendingContractId,
            contributionId = pendingContributionId,
            transactionId = transactionId,
            status = "pending",
            contributionDataJson = contributionDataJson,
            contributorsDataJson = contributorsDataJson,
            // uncorroborated until someone else matches it; rewritten from the ledger on confirmation
            contributorCount = 1,
            trustScoreJson = "{}",
            totalTrustScore = BigDecimal.ONE,
            originalSubmissionDate = now,
            disputeDatesJson = "[]",
            propertyCity = contributionData.propertyAddress.map { it.city.orElse(null) }.orElse(null),
            propertyCountry = contributionData.propertyAddress.map { it.country.orElse(null) }.orElse(null),
            propertyPostalCode = contributionData.propertyAddress.map { it.postalCode.orElse(null) }.orElse(null),
            salePrice = contributionData.salePrice.orElse(null),
            saleDate = contributionData.saleDate.map { java.time.LocalDate.parse(it.toString()) }.orElse(null),
            propertyType = contributionData.propertyType.orElse(null),
            squareMeters = contributionData.squareMeters.orElse(null),
            numberOfRooms = contributionData.numberOfRooms.map { it.toInt() }.orElse(null),
            yearBuilt = contributionData.yearBuilt.map { it.toInt() }.orElse(null),
            energyRating = contributionData.energyRating.map { it.name }.orElse(null),
            tagsJson = contributionData.tags.map { json.encodeToString(it) }.orElse(null),
            createdAt = now,
            updatedAt = now
        )

        try {
            contributionRepo.upsert(pendingEntity)
            logger.debug { "Inserted pending contribution for transaction: $transactionId" }

            val cid = ContributionRequest.ContractId(requestContractId)
            val update = cid.exerciseAcceptNewContribution(contributorData)
            val result = ledgerClient.submitUpdate(config.operatorParty, update)

            val createdId = result.getCreatedContractIds().firstOrNull()

            if (createdId != null) {
                contributionRepo.confirmPending(transactionId, createdId)
                logger.info { "Confirmed contribution $createdId for transaction: $transactionId" }
            } else {
                logger.warn { "No contract ID returned from Canton for transaction: $transactionId" }
            }

            return ContributionProcessResult(
                action = "AcceptNewContribution",
                details = "Created contribution: $createdId",
                createdContractId = createdId
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create contribution for transaction: $transactionId" }
            contributionRepo.delete(pendingContractId)
            throw e
        }
    }

    private fun serializeContributionData(data: com.dsrs.daml.types.ContributionData): String {
        return buildJsonObject {
            data.propertyAddress.ifPresent { addr ->
                put("propertyAddress", buildJsonObject {
                    addr.street.ifPresent { put("street", it) }
                    addr.city.ifPresent { put("city", it) }
                    addr.postalCode.ifPresent { put("postalCode", it) }
                    addr.country.ifPresent { put("country", it) }
                    addr.additionalInfo.ifPresent { put("additionalInfo", it) }
                })
            }
            data.salePrice.ifPresent { put("salePrice", it.toDouble()) }
            data.saleDate.ifPresent { put("saleDate", it.toString()) }
            data.propertyType.ifPresent { put("propertyType", it) }
            data.squareMeters.ifPresent { put("squareMeters", it.toDouble()) }
            data.numberOfRooms.ifPresent { put("numberOfRooms", it) }
            data.yearBuilt.ifPresent { put("yearBuilt", it) }
            data.energyRating.ifPresent { put("energyRating", it.name) }
            data.tags.ifPresent { tags ->
                put("tags", buildJsonArray { tags.forEach { add(it) } })
            }
            data.additionalInfo.ifPresent { put("additionalInfo", it) }
        }.toString()
    }

    private fun serializeContributorData(data: ContributorData): String {
        return buildJsonObject {
            put(data.contributor, buildJsonObject {
                put("roleInTransaction", data.roleInTransaction.name)
                put("disputeCoefficient", data.disputeCoefficient.toDouble())
                put("reputation", data.reputation.toDouble())
            })
        }.toString()
    }

    private fun dataMatchesStored(
        storedJson: String,
        incoming: com.dsrs.daml.types.ContributionData
    ): Boolean {
        return try {
            val stored = json.parseToJsonElement(storedJson).jsonObject

            val storedPrice = stored["salePrice"]?.jsonPrimitive?.doubleOrNull?.let { BigDecimal.valueOf(it) }
            val incomingPrice = incoming.salePrice.orElse(null)
            if (!bigDecimalEquals(storedPrice, incomingPrice)) {
                logger.debug { "salePrice mismatch: $storedPrice vs $incomingPrice" }
                return false
            }

            val storedDate = stored["saleDate"]?.jsonPrimitive?.contentOrNull
            val incomingDate = incoming.saleDate.map { it.toString() }.orElse(null)
            if (storedDate != incomingDate) {
                logger.debug { "saleDate mismatch: $storedDate vs $incomingDate" }
                return false
            }

            val storedType = stored["propertyType"]?.jsonPrimitive?.contentOrNull
            val incomingType = incoming.propertyType.orElse(null)
            if (storedType != incomingType) {
                logger.debug { "propertyType mismatch" }
                return false
            }

            val storedSqm = stored["squareMeters"]?.jsonPrimitive?.doubleOrNull?.let { BigDecimal.valueOf(it) }
            val incomingSqm = incoming.squareMeters.orElse(null)
            if (!bigDecimalEquals(storedSqm, incomingSqm)) {
                logger.debug { "squareMeters mismatch" }
                return false
            }

            val storedRooms = stored["numberOfRooms"]?.jsonPrimitive?.longOrNull
            val incomingRooms = incoming.numberOfRooms.orElse(null)
            if (storedRooms != incomingRooms) {
                logger.debug { "numberOfRooms mismatch" }
                return false
            }

            val storedYear = stored["yearBuilt"]?.jsonPrimitive?.longOrNull
            val incomingYear = incoming.yearBuilt.orElse(null)
            if (storedYear != incomingYear) {
                logger.debug { "yearBuilt mismatch" }
                return false
            }

            val storedRating = stored["energyRating"]?.jsonPrimitive?.contentOrNull
            val incomingRating = incoming.energyRating.map { it.name }.orElse(null)
            if (storedRating != incomingRating) {
                logger.debug { "energyRating mismatch" }
                return false
            }

            val storedAddr = stored["propertyAddress"]?.jsonObject
            val incomingAddr = incoming.propertyAddress.orElse(null)
            if (!addressMatchesStored(storedAddr, incomingAddr)) {
                logger.debug { "propertyAddress mismatch" }
                return false
            }

            true
        } catch (e: Exception) {
            logger.warn(e) { "Error comparing contribution data, assuming mismatch" }
            false
        }
    }

    private fun addressMatchesStored(stored: JsonObject?, incoming: Address?): Boolean {
        if (stored == null && incoming == null) return true
        if (stored == null || incoming == null) return false

        val storedCity = stored["city"]?.jsonPrimitive?.contentOrNull
        val incomingCity = incoming.city.orElse(null)
        if (storedCity != incomingCity) return false

        val storedCountry = stored["country"]?.jsonPrimitive?.contentOrNull
        val incomingCountry = incoming.country.orElse(null)
        if (storedCountry != incomingCountry) return false

        val storedPostal = stored["postalCode"]?.jsonPrimitive?.contentOrNull
        val incomingPostal = incoming.postalCode.orElse(null)
        if (storedPostal != incomingPostal) return false

        val storedStreet = stored["street"]?.jsonPrimitive?.contentOrNull
        val incomingStreet = incoming.street.orElse(null)
        if (storedStreet != incomingStreet) return false

        return true
    }

    private fun bigDecimalEquals(a: BigDecimal?, b: BigDecimal?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.compareTo(b) == 0
    }

    private fun mergeContribution(
        requestContractId: String,
        existingContributionCid: String,
        contributorData: ContributorData
    ): ContributionProcessResult {
        val cid = ContributionRequest.ContractId(requestContractId)
        val existingCid = Contribution.ContractId(existingContributionCid)
        val tokenMintAuthorityCid = tokenMintAuthorityService.getAuthorityCid()

        val update = cid.exerciseMergeContribution(existingCid, contributorData, tokenMintAuthorityCid)
        val result = ledgerClient.submitUpdate(config.operatorParty, update)

        val createdId = result.getCreatedContractIds().firstOrNull()
        return ContributionProcessResult(
            action = "MergeContribution",
            details = "Merged with existing, new contribution: $createdId",
            createdContractId = createdId
        )
    }

    private fun openDispute(
        requestContractId: String,
        existingContributionCid: String,
        contributorData: ContributorData
    ): ContributionProcessResult {
        val cid = ContributionRequest.ContractId(requestContractId)
        val existingCid = Contribution.ContractId(existingContributionCid)
        val tokenMintAuthorityCid = tokenMintAuthorityService.getAuthorityCid()

        val update = cid.exerciseOpenDispute(existingCid, contributorData, tokenMintAuthorityCid)
        val result = ledgerClient.submitUpdate(config.operatorParty, update)

        val createdId = result.getCreatedContractIds().firstOrNull()
        return ContributionProcessResult(
            action = "OpenDispute",
            details = "Opened dispute: $createdId",
            createdContractId = createdId
        )
    }

    private fun joinExistentDispute(
        requestContractId: String,
        existingDisputeCid: String,
        contributorData: ContributorData
    ): ContributionProcessResult {
        logger.info { "Joining existing dispute $existingDisputeCid for request $requestContractId" }

        val requestCid = ContributionRequest.ContractId(requestContractId)
        val disputeCid = Dispute.ContractId(existingDisputeCid)

        val update = requestCid.exerciseJoinExistentDispute(disputeCid, contributorData)
        val result = ledgerClient.submitUpdate(config.operatorParty, update)

        val createdDisputeId = result.getCreatedContractIds().firstOrNull()

        return ContributionProcessResult(
            action = "JoinExistentDispute",
            details = "Joined existing dispute, new dispute contract: $createdDisputeId",
            createdContractId = createdDisputeId
        )
    }

    private fun buildContributorData(contributor: String, role: Role): ContributorData {
        return ContributorData(
            contributor,
            role,
            // the ledger seats every contributor at the initial internal reputation anyway
            BigDecimal("0.5"),
            BigDecimal("1.0")
        )
    }

    private fun contributorAlreadyPresent(contributorsDataJson: String, contributor: String): Boolean =
        try {
            json.parseToJsonElement(contributorsDataJson).jsonObject.containsKey(contributor)
        } catch (e: Exception) {
            logger.warn(e) { "Could not parse contributorsData; treating $contributor as not present" }
            false
        }

    // Consumes the request with a reason instead of letting it fail against the ledger.
    private fun rejectAlreadyContributed(
        contractId: String,
        transactionId: String,
        contributor: String,
        why: String
    ): ContributionProcessResult {
        val reason = "$contributor $why ($transactionId). " +
            "A party contributes once per transaction; to change a value already on the ledger, " +
            "open a dispute from a different party or use the dispute's ProposeDisputeData choice."
        logger.info { "Rejecting contribution request $contractId: $reason" }
        rejectContributionRequest(contractId, reason)
        return ContributionProcessResult(
            action = "RejectContribution",
            details = reason,
            createdContractId = null
        )
    }

    fun rejectContributionRequest(contractId: String, reason: String) {
        val cid = ContributionRequest.ContractId(contractId)
        val update = cid.exerciseRejectContribution(reason)
        ledgerClient.submitUpdate(config.operatorParty, update)
    }
}
