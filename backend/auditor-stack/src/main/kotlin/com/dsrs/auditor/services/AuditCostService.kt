package com.dsrs.auditor.services

import com.dsrs.daml.audit.AuditRequestParams
import com.dsrs.daml.audit.AuditRequestType
import mu.KotlinLogging
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

class AuditCostService {

    companion object {
        const val BASE_COST_PRICING_VERIFICATION = 5_000L
        const val BASE_COST_REWARD_VERIFICATION = 8_000L
        const val BASE_COST_INCLUSION_VERIFICATION = 3_000L

        const val COST_PER_DAY_IN_TIME_WINDOW = 100L
    }

    fun estimateCost(requestType: AuditRequestType, params: AuditRequestParams): Long {
        val baseCost = when (requestType) {
            AuditRequestType.PRICINGVERIFICATION -> BASE_COST_PRICING_VERIFICATION
            AuditRequestType.REWARDVERIFICATION -> BASE_COST_REWARD_VERIFICATION
            AuditRequestType.INCLUSIONVERIFICATION -> BASE_COST_INCLUSION_VERIFICATION
        }

        val timeWindowCost = calculateTimeWindowCost(params)

        val totalCost = baseCost + timeWindowCost

        logger.debug {
            "Estimated cost for $requestType: base=$baseCost, timeWindow=$timeWindowCost, total=$totalCost"
        }

        return totalCost
    }

    private fun calculateTimeWindowCost(params: AuditRequestParams): Long {
        val fromTime = params.fromTime.orElse(null) ?: return 0L
        val toTime = params.toTime.orElse(null) ?: return 0L

        val fromInstant = Instant.ofEpochSecond(fromTime.epochSecond)
        val toInstant = Instant.ofEpochSecond(toTime.epochSecond)
        val days = Duration.between(fromInstant, toInstant).toDays()

        return days * COST_PER_DAY_IN_TIME_WINDOW
    }

    fun generateDescription(requestType: AuditRequestType, params: AuditRequestParams): String {
        return when (requestType) {
            AuditRequestType.PRICINGVERIFICATION -> {
                val queryId = params.queryId.orElse("unknown")
                val proposalId = params.proposalId.orElse("unknown")
                "Verify pricing calculation for query $queryId (proposal $proposalId)"
            }
            AuditRequestType.REWARDVERIFICATION -> {
                val groupId = params.rewardGroupId.orElse("unknown")
                "Verify reward distribution for group $groupId"
            }
            AuditRequestType.INCLUSIONVERIFICATION -> {
                val txId = params.transactionId.orElse("unknown")
                val window = when {
                    params.fromTime.isPresent && params.toTime.isPresent ->
                        "between ${params.fromTime.get()} and ${params.toTime.get()}"
                    params.fromTime.isPresent -> "since ${params.fromTime.get()}"
                    params.toTime.isPresent -> "up to ${params.toTime.get()}"
                    else -> "over all recorded history"
                }
                "Verify every query that should have used transaction $txId $window also " +
                    "rewarded it"
            }
        }
    }
}
