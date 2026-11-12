package com.dsrs.auditor.services

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.dsrs.auditor.db.repositories.ContributionSnapshotRepository
import com.dsrs.auditor.db.repositories.QuerySnapshotRepository
import com.dsrs.auditor.db.repositories.RewardSnapshotRepository
import com.dsrs.daml.audit.AuditRequestParams
import com.dsrs.daml.audit.AuditRequestType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Decides whether a party may ask a given audit question. Each check answers a question that
 * belongs to somebody: was I charged correctly, was I paid correctly, was my data used.
 *
 * Only the auditor can decide it. Contributors observe their RewardGroup, but DistributeInGroup
 * archives it as it pays, so by the time an audit is requested only the auditor's history still
 * holds the answer.
 */
class AuditStandingService(
    private val querySnapshotRepo: QuerySnapshotRepository,
    private val rewardSnapshotRepo: RewardSnapshotRepository,
    private val contributionSnapshotRepo: ContributionSnapshotRepository,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    sealed class Standing {
        object Allowed : Standing()
        data class Refused(val reason: String) : Standing()
    }

    fun check(requester: String, requestType: AuditRequestType, params: AuditRequestParams): Standing =
        try {
            when (requestType) {
                AuditRequestType.PRICINGVERIFICATION -> checkPricing(requester, params)
                AuditRequestType.REWARDVERIFICATION -> checkReward(requester, params)
                AuditRequestType.INCLUSIONVERIFICATION -> checkInclusion(requester, params)
            }
        } catch (e: Exception) {
            // never fail open
            logger.error(e) { "Could not establish standing for $requester ($requestType)" }
            Standing.Refused("the auditor could not establish who this request belongs to: ${e.message}")
        }

    // pricing: only the consumer who paid
    private fun checkPricing(requester: String, params: AuditRequestParams): Standing {
        val queryId = params.queryId.orElse(null)
        val proposalId = params.proposalId.orElse(null)

        val snapshot = queryId?.let { querySnapshotRepo.findCurrentByQueryId(it) }
            ?: proposalId?.let { querySnapshotRepo.findCurrentByProposalId(it) }
            ?: proposalId?.let { querySnapshotRepo.findCompletedByProposalId(it) }
            ?: return Standing.Refused(
                "no query found for ${queryId ?: proposalId ?: "the given parameters"}"
            )

        return if (snapshot.consumer == requester) {
            Standing.Allowed
        } else {
            Standing.Refused(
                "pricing verification belongs to the consumer who paid for the query, " +
                    "and ${shortName(requester)} did not pay for ${snapshot.queryId}"
            )
        }
    }

    // Reward: a contributor to the record as it stood when the group was paid. Only the
    // requester's own share is returned (VerificationService.verifyRewardDistribution).
    private fun checkReward(requester: String, params: AuditRequestParams): Standing {
        val groupId = params.rewardGroupId.orElse(null)
            ?: return Standing.Refused("no rewardGroupId given")

        val reward = rewardSnapshotRepo.findByGroupId(groupId)
            ?: return Standing.Refused("no reward group found for $groupId")

        if (requester in reward.contributorParties) return Standing.Allowed

        // the snapshot's recipient list may predate the payout, so fall back to the contribution
        val transactionId = reward.transactionId
        if (transactionId != null) {
            val asPaid = contributionSnapshotRepo.findAtTime(transactionId, reward.distributionDate)
            if (asPaid != null && requester in parseContributorParties(asPaid.contributorsDataJson)) {
                return Standing.Allowed
            }
        }

        return Standing.Refused(
            "reward verification belongs to the contributors of the transaction the group was " +
                "created for, and ${shortName(requester)} did not contribute to " +
                "${transactionId ?: "it"}"
        )
    }

    // Inclusion: any version counts, so a contributor replaced by a dispute can still ask.
    private fun checkInclusion(requester: String, params: AuditRequestParams): Standing {
        val transactionId = params.transactionId.orElse(null)
            ?: return Standing.Refused("no transactionId given")

        val versions = contributionSnapshotRepo.findAllVersions(transactionId)
        if (versions.isEmpty()) {
            return Standing.Refused("no contribution found for $transactionId")
        }

        val everContributed = versions.any { requester in parseContributorParties(it.contributorsDataJson) }
        return if (everContributed) {
            Standing.Allowed
        } else {
            Standing.Refused(
                "inclusion verification belongs to the contributors of the transaction, " +
                    "and ${shortName(requester)} never contributed to $transactionId"
            )
        }
    }

    private fun parseContributorParties(contributorsDataJson: String): Set<String> =
        try {
            json.parseToJsonElement(contributorsDataJson).jsonObject.keys
        } catch (e: Exception) {
            logger.warn(e) { "Could not parse contributorsData while resolving standing" }
            emptySet()
        }

    private fun shortName(party: String): String = party.substringBefore("::")
}
