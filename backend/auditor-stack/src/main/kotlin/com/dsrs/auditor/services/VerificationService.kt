package com.dsrs.auditor.services

import com.dsrs.auditor.db.repositories.*
import com.dsrs.auditor.models.*
import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.pricing.PricingConfig
import com.dsrs.daml.query.QueryProposal
import com.dsrs.daml.query.FundedQuery
import com.dsrs.daml.contribution.Contribution
import com.dsrs.daml.types.Insight
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/**
 * Distribution runs a moment after CompleteQuery, so a query that completed seconds ago may have
 * no reward groups yet. Within this window that is not reported as an unpaid use. The real gap is
 * under a second; too short a window means accusing an honest operator.
 */
private val DISTRIBUTION_GRACE: Duration = Duration.ofMinutes(5)

/**
 * Daml's fixed-point Decimal and Kotlin's Double approximate log2/exp/pow differently, so the
 * same inputs can floor to adjacent integers (seen live: 20,000,000 against 19,999,999). A
 * millionth of the price is not worth anything to an attacker and still rejects a wrong formula.
 */
private const val PRICE_MATCH_RELATIVE_TOLERANCE = 1e-6

private fun pricesMatch(expected: Long, actual: Long): Boolean {
    val tolerance = maxOf(1L, (expected * PRICE_MATCH_RELATIVE_TOLERANCE).toLong())
    return kotlin.math.abs(expected - actual) <= tolerance
}

class VerificationService(
    private val ledgerClient: LedgerClient,
    private val auditorParty: String,
    private val contributionSnapshotRepo: ContributionSnapshotRepository,
    private val pricingConfigSnapshotRepo: PricingConfigSnapshotRepository,
    private val querySnapshotRepo: QuerySnapshotRepository,
    private val rewardSnapshotRepo: RewardSnapshotRepository,
    private val auditLogRepo: AuditLogRepository,
    private val queryMatchingService: QueryMatchingService,
    private val resultStatisticsService: ResultStatisticsService,
    private val json: Json
) {

    /**
     * Checks three separate things:
     *  1. inputs: the pricing metadata re-derived from the auditor's own replay of the filter at
     *     the pinned execution time, so an inflated numTransactions is caught
     *  2. arithmetic: the formula re-run over those inputs with the config valid at the time
     *  3. answer: once completed, the delivered statistics recomputed over the same replay
     */
    suspend fun verifyPricing(request: VerifyPricingRequest): PricingVerificationResult {
        val now = Instant.now().toString()

        val queryId = request.queryId ?: request.proposalId!!
        val priced = findPricedSnapshot(queryId)
            ?: throw IllegalArgumentException("Query not found: $queryId")

        val actualPrice = priced.tokenCost
            ?: throw IllegalArgumentException("Query has no token cost (not yet proposed)")

        val replay = replayQuery(priced)

        val pricingConfigSnapshot = pricingConfigSnapshotRepo.findAtTime(replay.pricingConfigTime)
            ?: throw IllegalStateException("No PricingConfig found at execution time ${replay.pricingConfigTime}")

        logger.info { "Using pricing config valid at ${replay.pricingConfigTime} for verification" }

        val claimedMetadata = parsePricingMetadata(priced.pricingMetadataJson)
        val params = queryMatchingService.parseParams(priced.queryParamsJson)

        val discrepancies = queryMatchingService.compareMetadata(claimedMetadata, replay.derivedMetadata)

        val expectedPrice = calculatePriceFromSnapshot(pricingConfigSnapshot, replay.derivedMetadata, params.level)

        val priceMatches = pricesMatch(expectedPrice, actualPrice)
        val answer = checkAnswer(replay, params.level)
        val matches = priceMatches && answer?.agrees != false

        // A query that charged correctly but created no RewardGroup still prices as honest, so
        // check the payment turned into a distribution.
        val completed = querySnapshotRepo.findCompletedByProposalId(priced.proposalId ?: queryId)
        val rewardGroups = completed?.queryId?.let { rewardSnapshotRepo.findByQueryId(it) } ?: emptyList()
        val paymentProducedDistribution = if (completed == null) null else rewardGroups.isNotEmpty()

        val result = PricingVerificationResult(
            queryId = queryId,
            expected = expectedPrice,
            actual = actualPrice,
            matches = matches,
            priceMatches = priceMatches,
            resultChecked = answer?.delivered != null,
            resultMatches = answer?.agrees,
            resultDiscrepancies = answer?.discrepancies.orEmpty().map {
                StatisticDiscrepancyDto(it.field, it.delivered, it.derived)
            },
            pricingConfigId = pricingConfigSnapshot.contractId,
            verifiedAt = now,
            claimedNumTransactions = claimedMetadata.numTransactions,
            derivedNumTransactions = replay.derivedMetadata.numTransactions,
            metadataMatches = discrepancies.isEmpty(),
            metadataDiscrepancies = discrepancies.map {
                MetadataDiscrepancyDto(it.field, it.claimed, it.derived)
            },
            replayedAt = replay.snapshotTime.toString(),
            replayTimeApproximate = replay.approximate,
            rewardGroupsCreated = rewardGroups.size,
            paymentProducedDistribution = paymentProducedDistribution
        )

        auditLogRepo.insert(AuditLogEntity(
            requestType = "pricing",
            requestParams = json.encodeToString(VerifyPricingRequest.serializer(), request),
            result = json.encodeToString(PricingVerificationResult.serializer(), result),
            matches = matches
        ))

        return result
    }

    // Only the QueryProposal row carries the price and metadata.
    private fun findPricedSnapshot(queryId: String): QuerySnapshotEntity? {
        val direct = querySnapshotRepo.findCurrentByQueryId(queryId)
            ?: querySnapshotRepo.findCurrentByProposalId(queryId)
            ?: return null

        if (direct.pricingMetadataJson != null) return direct

        // a funded or result id is followed to its proposal rather than compared against nothing
        return direct.proposalId
            ?.takeIf { it != queryId }
            ?.let { querySnapshotRepo.findCurrentByQueryId(it) }
            ?: direct
    }

    private data class QueryReplay(
        val snapshotTime: LocalDateTime,
        val pricingConfigTime: LocalDateTime,
        val derivedMetadata: PricingMetadataDto,
        val matched: List<ContributionSnapshotEntity>,
        val approximate: Boolean,
        val completed: QuerySnapshotEntity?
    ) {
        val matchedTransactionIds: List<String> get() = matched.map { it.transactionId }
    }

    // delivered is null for queries completed before the auditor kept what they delivered
    private data class AnswerCheck(
        val delivered: List<Insight>?,
        val derived: List<Insight>,
        val discrepancies: List<StatisticDiscrepancy>
    ) {
        val agrees: Boolean? get() = delivered?.let { discrepancies.isEmpty() }
    }

    private fun checkAnswer(replay: QueryReplay, level: Int): AnswerCheck? {
        val completed = replay.completed ?: return null
        val derived = listOf(resultStatisticsService.normalize(
            resultStatisticsService.compute(replay.matched, level)))
        val delivered = resultStatisticsService.decode(completed.resultsJson)
            ?.map(resultStatisticsService::normalize)
            ?: return AnswerCheck(null, derived, emptyList())
        return AnswerCheck(delivered, derived, resultStatisticsService.compare(delivered, derived))
    }

    // Replayed at the pinned execution time; completion time or now would include contributions
    // that arrived after pricing.
    private fun replayQuery(priced: QuerySnapshotEntity): QueryReplay {
        val completed = priced.proposalId?.let { querySnapshotRepo.findCompletedByProposalId(it) }
        val pinned = completed?.executionTimestamp

        // Before completion there is no pinned time, so use when the proposal was observed and
        // mark the result approximate.
        val snapshotTime = pinned ?: priced.validFrom
        val approximate = pinned == null

        if (approximate) {
            logger.warn {
                "No pinned executionTimestamp for ${priced.queryId} (query not completed yet); " +
                    "replaying at proposal observation time $snapshotTime instead"
            }
        }

        val params = queryMatchingService.parseParams(priced.queryParamsJson)
        val match = queryMatchingService.replay(params, snapshotTime)

        return QueryReplay(
            snapshotTime = snapshotTime,
            pricingConfigTime = completed?.completionDate ?: snapshotTime,
            derivedMetadata = match.derivedMetadata,
            matched = match.matched,
            approximate = approximate,
            completed = completed
        )
    }

    /**
     * Starts from the query, not the payout: a split checked against the group's own observed
     * total agrees with itself even when the group was funded wrong. The chain is price, minus
     * fees, across matched transactions by trust score, then within the transaction by role,
     * individual score and internal reputation. Each link gets its own verdict, and only the
     * requester's own figures are returned.
     */
    suspend fun verifyReward(request: VerifyRewardRequest): RewardVerificationResult {
        val now = Instant.now().toString()
        val failureModes = mutableListOf<String>()

        // The observed payouts for this transaction under the requested query. Every query that
        // matches a transaction creates its own group, so any other would be the wrong pool.
        val rewardSnapshot = request.queryId
            .takeIf { it.isNotBlank() }
            ?.let { rewardSnapshotRepo.findByTransactionAndQuery(request.transactionId, it) }
            ?: rewardSnapshotRepo.findByTransactionId(request.transactionId)
            ?: throw IllegalArgumentException("No completed reward distribution found for transaction: ${request.transactionId}")

        val contributionSnapshot = contributionAsPaid(request.transactionId, rewardSnapshot)
            ?: throw IllegalArgumentException("Contribution not found for transaction: ${request.transactionId}")

        val chain = deriveRewardChain(rewardSnapshot, request.queryId, failureModes)

        val contributors = parseContributors(contributionSnapshot.contributorsDataJson)

        // The route carries an admin credential, so the requester is checked here.
        if (contributors.none { it.party == request.requester }) {
            throw IllegalArgumentException(
                "reward verification belongs to the contributors of the transaction the group " +
                    "was created for, and ${request.requester} did not contribute to " +
                    request.transactionId
            )
        }

        val actualByParty = rewardSnapshot.recipients.associate { it.party to it.amount }

        // the derived group total, so a wrong total cannot hide behind a proportional split of it
        val groupTotalForSplit = chain?.expectedGroupTotal ?: rewardSnapshot.totalAmount
        val expectedByParty = RewardMathService.expectedContributorRewards(contributors, groupTotalForSplit)

        val contributorResults = contributors.map { contributor ->
            val expectedAmount = expectedByParty[contributor.party] ?: 0L
            val actualAmount = actualByParty[contributor.party] ?: 0L
            ContributorRewardResult(
                contributor = contributor.party,
                expectedAmount = expectedAmount,
                actualAmount = actualAmount,
                matches = amountsMatch(expectedAmount, actualAmount)
            )
        }

        val ownResult = contributorResults.first { it.contributor == request.requester }

        val withinGroupMatches = contributorResults.all { it.matches }
        if (!withinGroupMatches) {
            failureModes += "within-group split: the group total was not divided by the roles, " +
                "individual scores and dispute coefficients the record carries"
        }

        val actualGroupTotal = rewardSnapshot.totalAmount
        val groupTotalMatches = chain?.let {
            // flooring loses under one token per contributor
            val tolerance = maxOf(2L, contributors.size.toLong() + 1)
            kotlin.math.abs(it.expectedGroupTotal - actualGroupTotal) <= tolerance
        }
        if (groupTotalMatches == false) {
            failureModes += "group funding: this transaction's group was not funded with its " +
                "trust-score share of the pool the query's price left after fees"
        }

        // Every group can be right on its own while the query as a whole paid out less than it
        // collected.
        val queryIdForTotals = rewardSnapshot.queryId ?: request.queryId
        val allGroups = queryIdForTotals.takeIf { it.isNotBlank() }
            ?.let { rewardSnapshotRepo.findByQueryId(it) }
            ?.filter { it.distributed }
            ?: emptyList()
        val queryTotalDistributed = allGroups.sumOf { it.totalAmount }
        val queryTotalMatches = chain?.distributablePool?.let { pool ->
            val tolerance = maxOf(2L, allGroups.sumOf { it.recipients.size.toLong() } + allGroups.size)
            queryTotalDistributed <= pool && (pool - queryTotalDistributed) <= tolerance
        }
        if (queryTotalMatches == false) {
            failureModes += "query total: the groups the query funded do not add up to the pool " +
                "its price left after fees, by more than floor-rounding can account for"
        }

        val matches = withinGroupMatches && (groupTotalMatches ?: true) && failureModes.isEmpty()

        val result = RewardVerificationResult(
            queryId = rewardSnapshot.queryId ?: request.queryId,
            transactionId = request.transactionId,
            contributorResult = ownResult,
            matches = matches,
            verifiedAt = now,
            queryTotalMatches = queryTotalMatches,
            groupTotalMatches = groupTotalMatches,
            withinGroupMatches = withinGroupMatches,
            failureModes = failureModes
        )

        auditLogRepo.insert(AuditLogEntity(
            requestType = "reward",
            requestParams = json.encodeToString(VerifyRewardRequest.serializer(), request),
            result = json.encodeToString(RewardVerificationResult.serializer(), result),
            matches = matches
        ))

        return result
    }

    /**
     * The contribution as it stood when the reward was paid. A corroborator who joined later would
     * otherwise make a correct full payout to one contributor look like it should have been halved.
     * Falls back to the current version only if no version covers the distribution date.
     */
    private fun contributionAsPaid(
        transactionId: String,
        rewardSnapshot: RewardSnapshotEntity
    ): ContributionSnapshotEntity? =
        contributionSnapshotRepo.findAtTime(transactionId, rewardSnapshot.distributionDate)
            ?: contributionSnapshotRepo.findCurrentByTransactionId(transactionId)

    private data class RewardChain(
        val queryId: String,
        val fees: RewardMathService.FeeSplit,
        val distributablePool: Long,
        val expectedGroupTotal: Long,
        val matchedTransactionCount: Int,
        val rewardGroupCount: Int
    )

    // Null, with the reason in failureModes, when the group cannot be traced to a query the
    // auditor saw; that is itself a finding.
    private fun deriveRewardChain(
        rewardSnapshot: RewardSnapshotEntity,
        requestedQueryId: String,
        failureModes: MutableList<String>
    ): RewardChain? {
        val queryId = rewardSnapshot.queryId
        if (queryId == null) {
            failureModes += "unlinked distribution: RewardGroup ${rewardSnapshot.distributionId} " +
                "carries no queryId, so it cannot be checked against any query's payment"
            return null
        }
        if (requestedQueryId.isNotBlank() && requestedQueryId != queryId) {
            failureModes += "wrong query: the distribution is linked to $queryId, not the " +
                "requested $requestedQueryId"
        }

        val fundedSnapshot = querySnapshotRepo.findCurrentByQueryId(queryId)
        val tokenCost = fundedSnapshot?.tokenCost
        if (tokenCost == null) {
            failureModes += "untraceable payment: no funded query $queryId with a recorded token cost"
            return null
        }

        val fees = RewardMathService.splitFees(tokenCost)

        // which transactions should have shared the pool: the auditor's replay, not the groups
        // the operator created
        val priced = fundedSnapshot.proposalId?.let { findPricedSnapshot(it) }
        val matchedTransactionIds = priced?.let { replayQuery(it).matchedTransactionIds }
        if (matchedTransactionIds == null) {
            failureModes += "unverifiable scope: the proposal query $queryId was priced from " +
                "could not be found, so the set of transactions sharing its pool is unknown"
            return null
        }

        val observedGroups = rewardSnapshotRepo.findByQueryId(queryId)
        if (matchedTransactionIds.size != observedGroups.size) {
            failureModes += "scope mismatch: the query produced a different number of reward " +
                "groups than the number of transactions the auditor's own replay says it matched"
        }

        val trustScores = matchedTransactionIds.mapNotNull { transactionId ->
            val snapshot = contributionSnapshotRepo.findAtTime(transactionId, rewardSnapshot.distributionDate)
                ?: contributionSnapshotRepo.findCurrentByTransactionId(transactionId)
            snapshot?.let { transactionId to it.totalTrustScore }
        }.toMap()

        val expectedAmounts = RewardMathService.expectedGroupAmounts(fees.distributablePool, trustScores)
        val expectedGroupTotal = expectedAmounts[rewardSnapshot.transactionId] ?: 0L

        return RewardChain(
            queryId = queryId,
            fees = fees,
            distributablePool = fees.distributablePool,
            expectedGroupTotal = expectedGroupTotal,
            matchedTransactionCount = matchedTransactionIds.size,
            rewardGroupCount = observedGroups.size
        )
    }

    // A contribution archived by a dispute between pricing and distribution is legitimately
    // skipped. Asked at the distribution instant, since it may have been restored since.
    private fun wasActiveAtDistribution(
        transactionId: String,
        groupsForQuery: List<RewardSnapshotEntity>
    ): Boolean {
        val distributedAt = groupsForQuery.minOfOrNull { it.distributionDate } ?: return true
        val asOf = contributionSnapshotRepo.findAtTime(transactionId, distributedAt)
        return asOf?.isActive == true
    }

    private fun stillSettling(query: QuerySnapshotEntity): Boolean {
        val completedAt = query.completionDate ?: return false
        return Duration.between(completedAt, LocalDateTime.now()) < DISTRIBUTION_GRACE
    }

    private fun amountsMatch(expected: Long, actual: Long): Boolean =
        kotlin.math.abs(expected - actual) <= 1L

    // Same derivation as the on-ledger inclusion check, but returns the queries themselves.
    suspend fun verifyInclusion(request: VerifyInclusionRequest): InclusionVerificationResult {
        val now = Instant.now().toString()

        val fromTime = LocalDateTime.parse(request.fromTime)
        val toTime = LocalDateTime.parse(request.toTime)

        // any version counts
        if (contributionSnapshotRepo.findAllVersions(request.transactionId).isEmpty()) {
            throw IllegalArgumentException("Contribution not found for transaction: ${request.transactionId}")
        }

        val completedQueries = querySnapshotRepo.findCompletedInTimeRange(fromTime, toTime)

        val inclusionInfo = mutableListOf<QueryInclusionInfo>()
        val unverifiable = mutableListOf<String>()

        for (query in completedQueries) {
            val priced = findPricedSnapshot(query.queryId)
            // groups are keyed by the funded query's id, not the result's
            val fundedQueryId = query.proposalId
                ?.let { querySnapshotRepo.findFundedByProposalId(it)?.queryId }

            if (priced?.pricingMetadataJson == null || fundedQueryId == null) {
                unverifiable += query.queryId
                continue
            }

            if (request.transactionId !in replayQuery(priced).matchedTransactionIds) continue

            val groupsForQuery = rewardSnapshotRepo.findByQueryId(fundedQueryId)
            if (groupsForQuery.isEmpty() && stillSettling(query)) {
                unverifiable += query.queryId
                continue
            }

            val group = groupsForQuery.firstOrNull { it.transactionId == request.transactionId }

            if (group == null && !wasActiveAtDistribution(request.transactionId, groupsForQuery)) {
                continue
            }

            inclusionInfo += QueryInclusionInfo(
                queryId = query.queryId,
                completionDate = query.completionDate.toString(),
                rewardAmount = group?.totalAmount,
                rewarded = group != null
            )
        }

        val result = InclusionVerificationResult(
            transactionId = request.transactionId,
            fromTime = request.fromTime,
            toTime = request.toTime,
            queries = inclusionInfo,
            included = inclusionInfo.isNotEmpty(),
            verifiedAt = now,
            matchedButNotRewarded = inclusionInfo.count { !it.rewarded },
            unverifiable = unverifiable
        )

        auditLogRepo.insert(AuditLogEntity(
            requestType = "inclusion",
            requestParams = json.encodeToString(VerifyInclusionRequest.serializer(), request),
            result = json.encodeToString(InclusionVerificationResult.serializer(), result),
            matches = result.matchedButNotRewarded == 0
        ))

        return result
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    // Pricing.daml's CalculatePrice written a second time. This is the arithmetic under audit;
    // the independence of its inputs comes from QueryMatchingService.
    private fun calculatePriceFromSnapshot(
        config: PricingConfigSnapshot,
        metadata: PricingMetadataDto,
        level: Int
    ): Long {
        val n = metadata.numTransactions.toDouble()
        val bigN = maxOf(metadata.corpusTransactions, metadata.numTransactions).coerceAtLeast(1).toDouble()

        val coverage = n / bigN
        val completeness = 1.0 - exp(-config.coverageSaturation.toDouble() * coverage)
        var price = config.basePrice.toDouble() *
            log2(1.0 + n) * (1.0 + config.coverageWeight.toDouble() * completeness)

        // Exactly as Daml: anything other than 1 is charged at the level-2 rate. The auditor
        // checks queries as they were priced, not as they should have been submitted.
        price *= (if (level == 1) config.level1Multiplier else config.level2Multiplier).toDouble()

        val k = config.narrownessWeight.toDouble()
        listOf(
            metadata.addressSelectivity, metadata.salePriceSelectivity, metadata.saleDateSelectivity,
            metadata.squareMetersSelectivity, metadata.yearBuiltSelectivity, metadata.roomsSelectivity,
            metadata.propertyTypeSelectivity, metadata.energyRatingSelectivity, metadata.tagsSelectivity
        ).forEach { sel ->
            val sc = sel.coerceIn(0.0, 1.0)
            price *= 1.0 + k * sc * sc
        }

        price *= config.trustWeight.toDouble().pow(metadata.trustQuality.coerceIn(-1.0, 1.0))

        return maxOf(1L, floor(price).toLong())
    }

    private fun parsePricingMetadata(json: String?): PricingMetadataDto {
        if (json == null) return PricingMetadataDto()
        return try {
            val map = this.json.decodeFromString<Map<String, String>>(json)
            fun d(k: String) = map[k]?.toDoubleOrNull() ?: 0.0
            PricingMetadataDto(
                numTransactions = map["numTransactions"]?.toLongOrNull() ?: 0,
                corpusTransactions = map["corpusTransactions"]?.toLongOrNull() ?: 0,
                addressSelectivity = d("addressSelectivity"),
                salePriceSelectivity = d("salePriceSelectivity"),
                saleDateSelectivity = d("saleDateSelectivity"),
                squareMetersSelectivity = d("squareMetersSelectivity"),
                yearBuiltSelectivity = d("yearBuiltSelectivity"),
                roomsSelectivity = d("roomsSelectivity"),
                propertyTypeSelectivity = d("propertyTypeSelectivity"),
                energyRatingSelectivity = d("energyRatingSelectivity"),
                tagsSelectivity = d("tagsSelectivity"),
                trustQuality = d("trustQuality")
            )
        } catch (e: Exception) {
            logger.warn { "Failed to parse pricing metadata: $json" }
            PricingMetadataDto()
        }
    }

    private fun parseContributors(json: String): List<RewardMathService.Contributor> {
        return try {
            this.json.decodeFromString<Map<String, Map<String, String>>>(json).values.map { v ->
                RewardMathService.Contributor(
                    party = v["contributor"] ?: "",
                    role = v["roleInTransaction"] ?: "",
                    reputation = v["reputation"]?.toBigDecimalOrNull() ?: BigDecimal.ONE,
                    // missing means the initial value, not the top of the range
                    disputeCoefficient = v["disputeCoefficient"]?.toBigDecimalOrNull() ?: BigDecimal("0.5")
                )
            }
        } catch (e: Exception) {
            logger.warn { "Failed to parse contributors data: $json" }
            emptyList()
        }
    }

    // Variants for the on-ledger audit flow (AuditRequestHandler).
    /**
     * Same checks as the HTTP flow. CompleteAudit asserts matches == (expected == actual), so the
     * price and statistics of both sides travel with the result and the verdict is computed the
     * way the ledger recomputes it.
     */
    fun verifyPricing(queryId: String): SimpleVerificationOutcome {
        try {
            val priced = findPricedSnapshot(queryId)
                ?: throw IllegalArgumentException("Query not found: $queryId")

            val actualPrice = priced.tokenCost ?: 0

            val replay = replayQuery(priced)

            val pricingConfigSnapshot = pricingConfigSnapshotRepo.findAtTime(replay.pricingConfigTime)
                ?: throw IllegalStateException("No PricingConfig found at execution time ${replay.pricingConfigTime}")

            val claimedMetadata = parsePricingMetadata(priced.pricingMetadataJson)
            val params = queryMatchingService.parseParams(priced.queryParamsJson)
            val discrepancies = queryMatchingService.compareMetadata(claimedMetadata, replay.derivedMetadata)

            val expectedPrice = calculatePriceFromSnapshot(pricingConfigSnapshot, replay.derivedMetadata, params.level)

            val answer = checkAnswer(replay, params.level)
            if (answer != null && answer.delivered == null) {
                throw IllegalStateException(
                    "The statistics query $queryId delivered were not recorded, so its answer cannot be checked")
            }
            // As for rewards: within the tolerance actual is set to expected, since CompleteAudit
            // asserts matches == (expected == actual); outside it the charged price is stored.
            val priceMatches = pricesMatch(expectedPrice, actualPrice.toLong())
            val storedActual = if (priceMatches) expectedPrice else actualPrice.toLong()
            val matches = priceMatches && answer?.agrees != false

            val metadataNote = when {
                discrepancies.isEmpty() ->
                    "independently matched ${replay.derivedMetadata.numTransactions} transaction(s), " +
                        "metadata agrees with the operator"
                else -> "metadata disagrees with the operator on " +
                    discrepancies.joinToString(", ") { "${it.field} (claimed ${it.claimed}, derived ${it.derived})" }
            }

            val answerNote = when {
                answer == null -> "statistics not checked, the query has not completed"
                answer.discrepancies.isEmpty() ->
                    "the delivered statistics agree with the auditor's own"
                else -> "the delivered statistics disagree on " +
                    answer.discrepancies.joinToString(", ") { "${it.field} (delivered ${it.delivered}, derived ${it.derived})" }
            }

            return SimpleVerificationOutcome(
                expectedPrice = expectedPrice,
                actualPrice = storedActual,
                matches = matches,
                details = (if (priceMatches) "Price verified correctly (charged $actualPrice)" else "Price mismatch: expected $expectedPrice, actual $actualPrice") +
                    "; $answerNote; $metadataNote",
                statistics = answer?.delivered?.let { SideStatistics(expected = answer.derived, actual = it) }
            )
        } catch (e: Exception) {
            // Rethrown instead of returning a sentinel: (0, 0, false) is inconsistent, the ledger
            // rejects it, and AuditRequestHandler refunds.
            logger.error(e) { "Pricing verification failed for $queryId" }
            throw e
        }
    }

    /**
     * The whole chain is derived and compared, but only the requester's own pair is stored,
     * because AuditResult discloses it to them permanently. What they were owed comes from the
     * query's pool, so a group funded with the wrong amount fails here too.
     *
     * CompleteAudit requires matches == (expected == actual) exactly, so the rounding tolerance is
     * applied by moving `actual` onto `expected` when they are within it.
     */
    fun verifyRewardDistribution(rewardGroupId: String, requester: String): SimpleVerificationOutcome {
        logger.info { "Verifying reward distribution for group: $rewardGroupId" }

        try {
            val rewardSnapshot = rewardSnapshotRepo.findByGroupId(rewardGroupId)
                ?: throw IllegalArgumentException("Reward group not found: $rewardGroupId")

            if (!rewardSnapshot.distributed) {
                throw IllegalStateException("Reward group $rewardGroupId has not been distributed yet")
            }

            val transactionId = rewardSnapshot.transactionId
                ?: throw IllegalStateException("Reward group $rewardGroupId has no linked transaction")

            val contributionSnapshot = contributionAsPaid(transactionId, rewardSnapshot)
                ?: throw IllegalArgumentException("Contribution not found for transaction: $transactionId")

            val failureModes = mutableListOf<String>()
            val chain = deriveRewardChain(rewardSnapshot, "", failureModes)

            val contributors = parseContributors(contributionSnapshot.contributorsDataJson)

            // already refused by AuditStandingService; checked again before writing to a contract
            // the requester observes
            if (contributors.none { it.party == requester }) {
                throw IllegalArgumentException(
                    "reward verification belongs to the contributors of the transaction the " +
                        "group was created for, and $requester did not contribute to $transactionId"
                )
            }

            val actualByParty = rewardSnapshot.recipients.associate { it.party to it.amount }

            val groupTotalForSplit = chain?.expectedGroupTotal ?: rewardSnapshot.totalAmount
            val expectedByParty = RewardMathService.expectedContributorRewards(contributors, groupTotalForSplit)

            val owed = expectedByParty[requester] ?: 0L
            val paid = actualByParty[requester] ?: 0L

            // Within the ±1 rounding tolerance actual is set to expected; outside it the real figure
            // is stored, so an overpayment fails as clearly as a shortfall.
            val actual = if (amountsMatch(owed, paid)) owed else paid
            val matches = owed == actual

            // the links behind the pair, as verdicts only
            val splitMatches = contributors.all {
                amountsMatch(expectedByParty[it.party] ?: 0L, actualByParty[it.party] ?: 0L)
            }
            val groupTotalMatches = chain?.let {
                val tolerance = maxOf(2L, contributors.size.toLong() + 1)
                kotlin.math.abs(it.expectedGroupTotal - rewardSnapshot.totalAmount) <= tolerance
            }
            val links = listOf(
                "funding chain traced: " + if (chain != null) "ok" else "FAILED",
                "group funding: " + when (groupTotalMatches) {
                    null -> "not checked"
                    true -> "ok"
                    false -> "FAILED"
                },
                "within-group split: " + if (splitMatches) "ok" else "FAILED"
            ).joinToString(", ")

            return SimpleVerificationOutcome(
                expectedPrice = owed,
                actualPrice = actual,
                matches = matches,
                details = (if (matches) {
                    "Your share of this distribution was paid in full"
                } else {
                    "Your share of this distribution is not what the chain says you were owed"
                }) + "; links checked - " + links +
                    (if (failureModes.isEmpty()) "" else "; issues: " + failureModes.joinToString("; "))
            )
        } catch (e: Exception) {
            // rethrown, see verifyPricing
            logger.error(e) { "Reward verification failed for group $rewardGroupId" }
            throw e
        }
    }

    /**
     * "Was my data used, and was I paid for each use", over a window. Contributors cannot see query
     * ids, and the interesting case is a query nobody told them about anyway.
     *
     * Should have been included: each completed query in the window replayed over the auditor's
     * own history at its execution timestamp. Was included: a RewardGroup for the transaction under
     * that query, the only per-transaction evidence, since insights name no transaction.
     * expected and actual count the two.
     */
    fun verifyInclusion(
        transactionId: String,
        fromTime: LocalDateTime,
        toTime: LocalDateTime
    ): InclusionCountOutcome {
        try {
            // any version counts
            if (contributionSnapshotRepo.findAllVersions(transactionId).isEmpty()) {
                throw IllegalArgumentException("Contribution not found for transaction: $transactionId")
            }

            val completedQueries = querySnapshotRepo.findCompletedInTimeRange(fromTime, toTime)

            val shouldHaveIncluded = mutableListOf<String>()
            val actuallyIncluded = mutableListOf<String>()
            val missing = mutableListOf<String>()
            val unverifiable = mutableListOf<String>()
            val excluded = mutableListOf<String>()

            for (query in completedQueries) {
                val priced = findPricedSnapshot(query.queryId)
                if (priced?.pricingMetadataJson == null) {
                    // cannot be replayed without its proposal; reported, not counted as a pass
                    unverifiable += query.queryId
                    continue
                }

                val matched = replayQuery(priced).matchedTransactionIds
                if (transactionId !in matched) continue

                // groups are keyed by the funded query's id, not the result's
                val fundedQueryId = query.proposalId
                    ?.let { querySnapshotRepo.findFundedByProposalId(it)?.queryId }
                if (fundedQueryId == null) {
                    unverifiable += query.queryId
                    continue
                }

                // Only skipped while the payout may be in flight. A query that paid nobody at all
                // must still be judged, or paying nobody would be safer than skipping one.
                val groupsForQuery = rewardSnapshotRepo.findByQueryId(fundedQueryId)
                if (groupsForQuery.isEmpty() && stillSettling(query)) {
                    unverifiable += query.queryId
                    continue
                }

                val paid = groupsForQuery.any { it.transactionId == transactionId }

                // A matched transaction disputed before distribution is skipped legitimately, so it
                // is left out of both sides and named separately.
                if (!paid && !wasActiveAtDistribution(transactionId, groupsForQuery)) {
                    excluded += query.queryId
                    continue
                }

                shouldHaveIncluded += query.queryId
                if (paid) actuallyIncluded += query.queryId else missing += query.queryId
            }

            val expected = shouldHaveIncluded.size.toLong()
            val actual = actuallyIncluded.size.toLong()
            val matches = expected == actual

            val summary = if (matches) {
                "Inclusion verified: $transactionId matched $expected of " +
                    "${completedQueries.size} completed query(ies) in the window, and was " +
                    "rewarded in every one"
            } else {
                "Inclusion mismatch: $transactionId matched $expected query(ies) but was " +
                    "rewarded in only $actual; not rewarded in ${missing.joinToString(", ")}"
            }

            val note = (if (unverifiable.isEmpty()) "" else
                "; ${unverifiable.size} query(ies) could not be replayed and were not counted: " +
                    unverifiable.joinToString(", ")) +
                (if (excluded.isEmpty()) "" else
                    "; ${excluded.size} query(ies) matched it but it was in dispute by the time " +
                        "they paid out, so no reward group was owed: " + excluded.joinToString(", "))

            return InclusionCountOutcome(
                expected = expected,
                actual = actual,
                matches = matches,
                queriesInWindow = completedQueries.size,
                includedIn = shouldHaveIncluded,
                details = summary + note
            )
        } catch (e: Exception) {
            // rethrown, see verifyPricing
            logger.error(e) { "Inclusion verification failed for transaction $transactionId" }
            throw e
        }
    }
}

data class SimpleVerificationOutcome(
    val expectedPrice: Long,
    val actualPrice: Long,
    val matches: Boolean,
    val details: String,
    val statistics: SideStatistics? = null
) {
    val expectedTotal: Long get() = expectedPrice
    val actualTotal: Long get() = actualPrice
}

// expected and actual map onto the two numbers CompleteAudit stores.
data class InclusionCountOutcome(
    val expected: Long,
    val actual: Long,
    val matches: Boolean,
    val queriesInWindow: Int,
    val includedIn: List<String>,
    val details: String
)

data class SideStatistics(
    val expected: List<Insight>,
    val actual: List<Insight>
)
