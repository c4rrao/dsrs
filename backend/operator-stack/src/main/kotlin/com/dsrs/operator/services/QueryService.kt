package com.dsrs.operator.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.common.types.Constants
import com.dsrs.daml.da.types.Tuple2
import com.dsrs.daml.pricing.PricingConfig
import com.dsrs.daml.query.FundedQuery
import com.dsrs.daml.query.QueryRequest
import com.dsrs.daml.types.*
import com.dsrs.daml.types.insight.Level1
import com.dsrs.daml.types.insight.Level2
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ContributionRepository
import com.dsrs.operator.db.repositories.QueryProposalRepository
import com.dsrs.operator.db.repositories.QueryProposalSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

private val logger = KotlinLogging.logger {}

data class QueryRequestResult(
    val resultCount: Int,
    val proposalContractId: String?,
    val rejected: Boolean = false,
    val rejectionReason: String? = null
)

data class FundedQueryResult(
    val insightCount: Int,
    val resultContractId: String?,
    val rewardContractId: String?,
    val failed: Boolean = false,
    val failureReason: String? = null
)

// A query reads the contribution set exactly once, at pricing. See [QueryProposalRepository].
class QueryService(
    private val config: OperatorConfig,
    private val ledgerClient: LedgerClient,
    private val insightService: InsightService,
    private val tokenMintAuthorityService: TokenMintAuthorityService,
    private val queryProposalRepo: QueryProposalRepository,
    private val contributionRepo: ContributionRepository,
    private val ledgerLock: LedgerLock
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun processQueryRequest(
        contractId: String,
        queryRequest: com.dsrs.daml.query.QueryRequest
    ): QueryRequestResult {
        val params = queryRequest.params
        val level = params.level.toInt()

        logger.info { "Processing query request: $contractId" }

        val paramsMap = toParamsMap(params)

        // stored with the insights and replayed into CompleteQuery as the executionTimestamp the
        // auditor reproduces the match at
        val executionTime = Instant.now()
        val queryResult = insightService.executeQuery(paramsMap, level, executionTime)

        // refuse before quoting, so nobody is charged for a query that can never be answered
        if (belowPrivacyThreshold(queryResult.contributions.size)) {
            val reason = "Query matches ${queryResult.contributions.size} transaction(s); " +
                "minimum ${Constants.MIN_TRANSACTIONS_FOR_PRIVACY} required to preserve contributor privacy"
            logger.warn { "Rejecting query request $contractId: $reason" }
            rejectQueryRequest(contractId, reason)
            return QueryRequestResult(
                resultCount = queryResult.contributions.size,
                proposalContractId = null,
                rejected = true,
                rejectionReason = reason
            )
        }

        val pricingConfigContracts = ledgerClient.queryContractsTyped(
            party = config.operatorParty,
            companion = PricingConfig.COMPANION
        )
        val pricingConfigPair = pricingConfigContracts.firstOrNull()
            ?: throw IllegalStateException(
                "No PricingConfig found on ledger. Create one via POST /api/pricing/config/init"
            )
        val pricingConfigContractId = pricingConfigPair.id.contractId

        // the unfiltered corpus at the same instant, as the denominator of every selectivity
        val corpus = contributionRepo.queryContributionsAtTime(
            snapshotTime = executionTime.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        )

        val metadata = insightService.extractPricingMetadata(
            params = paramsMap,
            contributions = queryResult.contributions,
            corpus = corpus
        )

        logger.info { "Extracted pricing metadata for ${queryResult.contributions.size} results" }

        val escrows = listOf(config.escrowParty)

        val update = QueryRequest.ContractId(contractId).exerciseProposeCost(
            escrows,
            PricingConfig.ContractId(pricingConfigContractId),
            metadata
        )

        val result = ledgerClient.submitUpdate(
            party = config.operatorParty,
            update = update
        )

        val proposalContractId = result.getCreatedContractIds().firstOrNull()

        // keyed by the proposal's business id, which the FundedQuery carries forward
        if (proposalContractId != null) {
            val proposalId = ledgerClient.getContractTyped(
                config.operatorParty,
                proposalContractId,
                com.dsrs.daml.query.QueryProposal.COMPANION
            )?.data?.proposalId

            if (proposalId != null) {
                // The stream is sequential, so nothing runs during this handler. The lease covers
                // the gap between here and distribution, where a corroboration or dispute could land.
                ledgerLock.acquireForQuery(proposalId)

                queryProposalRepo.save(QueryProposalSnapshot(
                    proposalId = proposalId,
                    insightsJson = serializeInsights(queryResult.insights),
                    executionTimestamp = executionTime,
                    resultCount = queryResult.contributions.size,
                    matchedTransactionIds = queryResult.contributions.map { it.transactionId }
                ))
                logger.info {
                    "Pinned ${queryResult.insights.size} insight(s) over " +
                        "${queryResult.contributions.size} transaction(s) for proposal $proposalId " +
                        "at execution time $executionTime"
                }
            } else {
                logger.error {
                    "Could not read proposalId back from $proposalContractId - the funded query " +
                        "will have no snapshot to replay and will be failed/refunded"
                }
            }
        }

        return QueryRequestResult(
            resultCount = queryResult.contributions.size,
            proposalContractId = proposalContractId
        )
    }

    // Replays the insights the query was priced from. Re-reading the data now would deliver a
    // different dataset from the one that was quoted.
    fun processFundedQuery(
        contractId: String,
        fundedQuery: com.dsrs.daml.query.FundedQuery
    ): FundedQueryResult {
        val queryId = fundedQuery.queryId
        val proposalId = fundedQuery.proposalId

        logger.info { "Processing funded query: $contractId (queryId: $queryId, proposalId: $proposalId)" }

        ledgerLock.pinForFundedQuery(proposalId)

        // without the pinned snapshot the quote cannot be honoured, so refund instead of recomputing
        val snapshot = queryProposalRepo.findByProposalId(proposalId)
        if (snapshot == null) {
            val reason = "No pinned insight snapshot for proposal $proposalId; " +
                "the results this query was priced for can no longer be reproduced"
            logger.error { "Failing funded query $contractId: $reason" }
            failFundedQuery(contractId, reason)
            ledgerLock.releaseQuery(proposalId)
            return FundedQueryResult(
                insightCount = 0,
                resultContractId = null,
                rewardContractId = null,
                failed = true,
                failureReason = reason
            )
        }

        // No liveness re-check: a dispute opened after pricing does not shrink the set the
        // statistics were taken over.

        val insights = deserializeInsights(snapshot.insightsJson)
        val executionTime = snapshot.executionTimestamp

        logger.info {
            "Replaying ${insights.size} pinned insight(s) over ${snapshot.resultCount} " +
                "transaction(s), execution time $executionTime"
        }

        // executionTime is the pricing instant, not now()
        val fundedQueryCid = FundedQuery.ContractId(contractId)
        val tokenMintAuthorityCid = tokenMintAuthorityService.getAuthorityCid()
        val completeQuery = com.dsrs.daml.query.CompleteQuery(insights, executionTime, tokenMintAuthorityCid)
        val update = fundedQueryCid.exerciseCompleteQuery(completeQuery)

        val result = ledgerClient.submitUpdate(
            party = config.operatorParty,
            update = update
        )

        val createdIds = result.getCreatedContractIds()
        val resultId = createdIds.firstOrNull()
        val rewardId = createdIds.getOrNull(1)

        // the snapshot is kept: distribution, which runs next, needs the matched set

        return FundedQueryResult(
            insightCount = insights.size,
            resultContractId = resultId,
            rewardContractId = rewardId
        )
    }

    fun rejectQueryRequest(contractId: String, reason: String) {
        val queryRequestCid = QueryRequest.ContractId(contractId)
        val update = queryRequestCid.exerciseRejectQueryRequest(reason)
        ledgerClient.submitUpdate(
            party = config.operatorParty,
            update = update
        )
    }

    fun failFundedQuery(contractId: String, reason: String) {
        val fundedQueryCid = FundedQuery.ContractId(contractId)
        val update = fundedQueryCid.exerciseFailQuery(reason)
        ledgerClient.submitUpdate(
            party = config.operatorParty,
            update = update
        )
    }

    // Every filter the consumer can express must be in here, or it is silently not applied and
    // the auditor, replaying the same QueryParams, disagrees. Key names are the ones
    // InsightService reads back with unchecked casts.
    private fun toParamsMap(params: QueryParams): Map<String, Any?> = mapOf(
        "level" to params.level.toInt(),
        "trustScore" to params.trustScore.map { decimalRangeToMap(it) }.orElse(null),
        "propertyAddress" to params.propertyAddress.map { addresses ->
            addresses.map { address ->
                mapOf(
                    "postalCode" to address.postalCode.orElse(null),
                    "street" to address.street.orElse(null),
                    "city" to address.city.orElse(null),
                    "country" to address.country.orElse(null),
                    "additionalInfo" to address.additionalInfo.orElse(null)
                )
            }
        }.orElse(null),
        "salePrice" to params.salePrice.map { decimalRangeToMap(it) }.orElse(null),
        "saleDate" to params.saleDate.map {
            mapOf(
                "from" to it.from.map { d -> d.toString() }.orElse(null),
                "to" to it.to.map { d -> d.toString() }.orElse(null)
            )
        }.orElse(null),
        "propertyTypes" to params.propertyTypes.orElse(null),
        "squareMeters" to params.squareMeters.map { decimalRangeToMap(it) }.orElse(null),
        // Daml Ints arrive as Long
        "numberOfRooms" to params.numberOfRooms.map { rooms -> rooms.map { it.toInt() } }.orElse(null),
        "yearBuilt" to params.yearBuilt.map {
            mapOf(
                "from" to it.from.orElse(null),
                "to" to it.to.orElse(null)
            )
        }.orElse(null),
        "energyRating" to params.energyRating.map { ratings -> ratings.map { it.name } }.orElse(null),
        "tags" to params.tags.orElse(null)
    )

    private fun decimalRangeToMap(range: Range<BigDecimal>): Map<String, Any?> = mapOf(
        "from" to range.from.orElse(null),
        "to" to range.to.orElse(null)
    )

    /**
     * Zero is included. With n = 0, log2(1 + n) is 0 and the price is clamped to 1 token, which
     * could then be neither distributed nor refunded. It also keeps "matched nothing"
     * indistinguishable from "matched too little".
     */
    private fun belowPrivacyThreshold(resultCount: Int): Boolean =
        resultCount < Constants.MIN_TRANSACTIONS_FOR_PRIVACY

    private fun serializeInsights(insights: List<Insight>): String {
        val jsonArray = buildJsonArray {
            insights.forEach { insight ->
                add(serializeInsight(insight))
            }
        }
        return json.encodeToString(jsonArray)
    }

    private fun deserializeInsights(jsonString: String): List<Insight> {
        val jsonArray = json.parseToJsonElement(jsonString).jsonArray
        return jsonArray.map { deserializeInsight(it.jsonObject) }
    }

    private fun serializeInsight(insight: Insight): JsonObject {
        return when (insight) {
            is Level1 -> buildJsonObject {
                put("level", 1)
                put("base", serializeInsightBase(insight.base))
            }
            is Level2 -> buildJsonObject {
                put("level", 2)
                put("base", serializeInsightBase(insight.base))
                put("details", serializeDetailedMetrics(insight.details))
            }
            else -> buildJsonObject { put("level", 0) }
        }
    }

    private fun deserializeInsight(obj: JsonObject): Insight {
        val level = obj["level"]?.jsonPrimitive?.intOrNull ?: 1
        val base = deserializeInsightBase(obj["base"]?.jsonObject ?: buildJsonObject {})

        return if (level >= 2) {
            val details = deserializeDetailedMetrics(obj["details"]?.jsonObject ?: buildJsonObject {})
            Level2(base, details)
        } else {
            Level1(base)
        }
    }

    private fun serializeInsightBase(base: InsightBase): JsonObject {
        return buildJsonObject {
            put("transactionCount", base.transactionCount)
            base.avgSalePrice.ifPresent { put("avgSalePrice", it.toPlainString()) }
            base.avgTrustScore.ifPresent { put("avgTrustScore", it.toPlainString()) }
            base.avgYearBuilt.ifPresent { put("avgYearBuilt", it.toPlainString()) }
            base.avgSquareMeters.ifPresent { put("avgSquareMeters", it.toPlainString()) }
            base.avgNumberOfRooms.ifPresent { put("avgNumberOfRooms", it.toPlainString()) }
            base.medianEnergyRating.ifPresent { put("medianEnergyRating", it.name) }
        }
    }

    private fun deserializeInsightBase(obj: JsonObject): InsightBase {
        return InsightBase(
            obj["transactionCount"]?.jsonPrimitive?.longOrNull ?: 0L,
            Optional.ofNullable(obj["avgSalePrice"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["avgTrustScore"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["avgYearBuilt"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["avgSquareMeters"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["avgNumberOfRooms"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["medianEnergyRating"]?.jsonPrimitive?.contentOrNull?.let {
                try { EnergyRating.valueOf(it) } catch (_: Exception) { null }
            })
        )
    }

    private fun serializeDetailedMetrics(details: DetailedMetrics): JsonObject {
        return buildJsonObject {
            details.priceRange.ifPresent { put("priceRange", serializeDecimalRange(it)) }
            details.priceVariance.ifPresent { put("priceVariance", it.toPlainString()) }
            details.medianPrice.ifPresent { put("medianPrice", it.toPlainString()) }
            details.avgPricePerSqm.ifPresent { put("avgPricePerSqm", it.toPlainString()) }
            details.pricePerSqmRange.ifPresent { put("pricePerSqmRange", serializeDecimalRange(it)) }
            details.yearBuiltRange.ifPresent { put("yearBuiltRange", serializeLongRange(it)) }
            details.sqmRange.ifPresent { put("sqmRange", serializeDecimalRange(it)) }
            details.sqmVariance.ifPresent { put("sqmVariance", it.toPlainString()) }
            details.roomsRange.ifPresent { put("roomsRange", serializeLongRange(it)) }
            details.propertyTypeDistribution.ifPresent { put("propertyTypeDistribution", serializeStringLongTuples(it)) }
            details.energyRatingDistribution.ifPresent { put("energyRatingDistribution", serializeEnergyRatingTuples(it)) }
            details.saleDateRange.ifPresent { put("saleDateRange", serializeDateRange(it)) }
            details.cityDistribution.ifPresent { put("cityDistribution", serializeStringLongTuples(it)) }
            details.topTags.ifPresent { put("topTags", serializeStringLongTuples(it)) }
            details.avgContributorsPerTx.ifPresent { put("avgContributorsPerTx", it.toPlainString()) }
            details.avgDisputesPerTx.ifPresent { put("avgDisputesPerTx", it.toPlainString()) }
            details.disputeRate.ifPresent { put("disputeRate", it.toPlainString()) }
        }
    }

    private fun deserializeDetailedMetrics(obj: JsonObject): DetailedMetrics {
        return DetailedMetrics(
            Optional.ofNullable(obj["priceRange"]?.jsonObject?.let { deserializeDecimalRange(it) }),
            Optional.ofNullable(obj["priceVariance"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["medianPrice"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["avgPricePerSqm"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["pricePerSqmRange"]?.jsonObject?.let { deserializeDecimalRange(it) }),
            Optional.ofNullable(obj["yearBuiltRange"]?.jsonObject?.let { deserializeLongRange(it) }),
            Optional.ofNullable(obj["sqmRange"]?.jsonObject?.let { deserializeDecimalRange(it) }),
            Optional.ofNullable(obj["sqmVariance"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["roomsRange"]?.jsonObject?.let { deserializeLongRange(it) }),
            Optional.ofNullable(obj["propertyTypeDistribution"]?.jsonArray?.let { deserializeStringLongTuples(it) }),
            Optional.ofNullable(obj["energyRatingDistribution"]?.jsonArray?.let { deserializeEnergyRatingTuples(it) }),
            Optional.ofNullable(obj["saleDateRange"]?.jsonObject?.let { deserializeDateRange(it) }),
            Optional.ofNullable(obj["cityDistribution"]?.jsonArray?.let { deserializeStringLongTuples(it) }),
            Optional.ofNullable(obj["topTags"]?.jsonArray?.let { deserializeStringLongTuples(it) }),
            Optional.ofNullable(obj["avgContributorsPerTx"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["avgDisputesPerTx"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["disputeRate"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) })
        )
    }

    private fun serializeDecimalRange(range: Range<BigDecimal>): JsonObject {
        return buildJsonObject {
            range.from.ifPresent { put("from", it.toPlainString()) }
            range.to.ifPresent { put("to", it.toPlainString()) }
        }
    }

    private fun deserializeDecimalRange(obj: JsonObject): Range<BigDecimal> {
        return Range(
            Optional.ofNullable(obj["from"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) }),
            Optional.ofNullable(obj["to"]?.jsonPrimitive?.contentOrNull?.let { BigDecimal(it) })
        )
    }

    private fun serializeLongRange(range: Range<Long>): JsonObject {
        return buildJsonObject {
            range.from.ifPresent { put("from", it) }
            range.to.ifPresent { put("to", it) }
        }
    }

    private fun deserializeLongRange(obj: JsonObject): Range<Long> {
        return Range(
            Optional.ofNullable(obj["from"]?.jsonPrimitive?.longOrNull),
            Optional.ofNullable(obj["to"]?.jsonPrimitive?.longOrNull)
        )
    }

    private fun serializeDateRange(range: Range<LocalDate>): JsonObject {
        return buildJsonObject {
            range.from.ifPresent { put("from", it.toString()) }
            range.to.ifPresent { put("to", it.toString()) }
        }
    }

    private fun deserializeDateRange(obj: JsonObject): Range<LocalDate> {
        return Range(
            Optional.ofNullable(obj["from"]?.jsonPrimitive?.contentOrNull?.let { LocalDate.parse(it) }),
            Optional.ofNullable(obj["to"]?.jsonPrimitive?.contentOrNull?.let { LocalDate.parse(it) })
        )
    }

    private fun serializeStringLongTuples(tuples: List<Tuple2<String, Long>>): JsonArray {
        return buildJsonArray {
            tuples.forEach { tuple ->
                add(buildJsonArray {
                    add(tuple._1)
                    add(tuple._2)
                })
            }
        }
    }

    private fun deserializeStringLongTuples(array: JsonArray): List<Tuple2<String, Long>> {
        return array.mapNotNull { element ->
            val arr = element.jsonArray
            if (arr.size >= 2) {
                Tuple2(
                    arr[0].jsonPrimitive.content,
                    arr[1].jsonPrimitive.long
                )
            } else null
        }
    }

    private fun serializeEnergyRatingTuples(tuples: List<Tuple2<EnergyRating, Long>>): JsonArray {
        return buildJsonArray {
            tuples.forEach { tuple ->
                add(buildJsonArray {
                    add(tuple._1.name)
                    add(tuple._2)
                })
            }
        }
    }

    private fun deserializeEnergyRatingTuples(array: JsonArray): List<Tuple2<EnergyRating, Long>> {
        return array.mapNotNull { element ->
            val arr = element.jsonArray
            if (arr.size >= 2) {
                try {
                    Tuple2(
                        EnergyRating.valueOf(arr[0].jsonPrimitive.content),
                        arr[1].jsonPrimitive.long
                    )
                } catch (_: Exception) { null }
            } else null
        }
    }
}
