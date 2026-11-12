package com.dsrs.auditor.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.audit.*
import mu.KotlinLogging
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

private val logger = KotlinLogging.logger {}

private val EARLIEST: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)

class AuditRequestHandler(
    private val ledgerClient: LedgerClient,
    private val auditorParty: String,
    private val costService: AuditCostService,
    private val verificationService: VerificationService,
    private val standingService: AuditStandingService
) {

    fun handleAuditRequestCreated(contractId: String, request: AuditRequest) {
        logger.info { "Processing AuditRequest: ${request.requestId} (type: ${request.requestType})" }

        try {
            // Standing is checked before quoting. Declining consumes the request on-ledger with
            // a reason.
            val standing = standingService.check(request.requester, request.requestType, request.params)
            if (standing is AuditStandingService.Standing.Refused) {
                val requestCid = AuditRequest.ContractId(contractId)
                ledgerClient.submitUpdate(auditorParty, requestCid.exerciseDeclineAuditRequest(standing.reason))
                logger.info { "Declined AuditRequest ${request.requestId}: ${standing.reason}" }
                return
            }

            val cost = costService.estimateCost(request.requestType, request.params)
            val description = costService.generateDescription(request.requestType, request.params)

            val requestCid = AuditRequest.ContractId(contractId)
            val update = requestCid.exerciseProposeAuditPrice(cost, description)

            ledgerClient.submitUpdate(auditorParty, update)

            logger.info {
                "Proposed audit price: ${request.requestId} -> cost=$cost"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to propose audit price for ${request.requestId}" }
        }
    }

    fun handleFundedAuditRequestCreated(contractId: String, fundedRequest: FundedAuditRequest) {
        logger.info { "Processing FundedAuditRequest: ${fundedRequest.requestId}" }

        try {
            val result = executeVerification(fundedRequest)

            val fundedCid = FundedAuditRequest.ContractId(contractId)
            val update = fundedCid.exerciseCompleteAudit(result)

            ledgerClient.submitUpdate(auditorParty, update)

            logger.info {
                "Completed audit: ${fundedRequest.requestId} -> matches=${result.matches}, " +
                    "expected=${result.expected}, actual=${result.actual}"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to complete audit for ${fundedRequest.requestId}" }

            try {
                val fundedCid = FundedAuditRequest.ContractId(contractId)
                val refundUpdate = fundedCid.exerciseRefundAudit(
                    "Verification failed: ${e.message}"
                )
                ledgerClient.submitUpdate(auditorParty, refundUpdate)
                logger.info { "Refunded audit tokens for ${fundedRequest.requestId}" }
            } catch (refundError: Exception) {
                logger.error(refundError) { "Failed to refund audit for ${fundedRequest.requestId}" }
            }
        }
    }

    private fun executeVerification(request: FundedAuditRequest): VerificationResult {
        return when (request.requestType) {
            AuditRequestType.PRICINGVERIFICATION -> verifyPricing(request)
            AuditRequestType.REWARDVERIFICATION -> verifyReward(request)
            AuditRequestType.INCLUSIONVERIFICATION -> verifyInclusion(request)
        }
    }

    private fun verifyPricing(request: FundedAuditRequest): VerificationResult {
        val queryId = request.params.queryId.orElse(null)
            ?: return failedResult("Missing queryId parameter")

        val verificationOutcome = verificationService.verifyPricing(queryId)

        val statistics = verificationOutcome.statistics
        return VerificationResult(
            AuditValue(verificationOutcome.expectedPrice, Optional.ofNullable(statistics?.expected)),
            AuditValue(verificationOutcome.actualPrice, Optional.ofNullable(statistics?.actual)),
            verificationOutcome.matches,
            verificationOutcome.details
        )
    }

    // The AuditResult is observed by the requester for good, so only their own share goes in.
    private fun verifyReward(request: FundedAuditRequest): VerificationResult {
        val rewardGroupId = request.params.rewardGroupId.orElse(null)
            ?: return failedResult("Missing rewardGroupId parameter")

        val verificationOutcome = verificationService.verifyRewardDistribution(rewardGroupId, request.requester)

        return VerificationResult(
            AuditValue(verificationOutcome.expectedTotal, Optional.empty()),
            AuditValue(verificationOutcome.actualTotal, Optional.empty()),
            verificationOutcome.matches,
            verificationOutcome.details
        )
    }

    // Contributors cannot see query ids, so inclusion is asked about a transaction over a window,
    // which defaults to all recorded history.
    private fun verifyInclusion(request: FundedAuditRequest): VerificationResult {
        val transactionId = request.params.transactionId.orElse(null)
            ?: return failedResult("Missing transactionId parameter")

        val fromTime = request.params.fromTime.map(::toLocalDateTime).orElse(EARLIEST)
        val toTime = request.params.toTime.map(::toLocalDateTime).orElse(LocalDateTime.now())

        val verificationOutcome = verificationService.verifyInclusion(transactionId, fromTime, toTime)

        return VerificationResult(
            AuditValue(verificationOutcome.expected, Optional.empty()),
            AuditValue(verificationOutcome.actual, Optional.empty()),
            verificationOutcome.matches,
            verificationOutcome.details
        )
    }

    // equal sides with a false verdict: CompleteAudit rejects it and the handler refunds
    private fun failedResult(details: String): VerificationResult =
        VerificationResult(AuditValue(0L, Optional.empty()), AuditValue(0L, Optional.empty()), false, details)

    // same conversion as HistorySynchronizer
    private fun toLocalDateTime(instant: Instant): LocalDateTime =
        LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
}
