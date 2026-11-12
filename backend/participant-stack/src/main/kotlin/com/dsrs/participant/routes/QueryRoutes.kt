package com.dsrs.participant.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.query.FundedQuery
import com.dsrs.daml.query.QueryProposal
import com.dsrs.daml.query.QueryResult
import com.dsrs.daml.query.QueryRequest
import com.dsrs.daml.token.holding.Holding
import com.dsrs.daml.types.Address
import com.dsrs.daml.types.EnergyRating
import com.dsrs.daml.types.QueryParams
import com.dsrs.daml.types.Range
import com.dsrs.participant.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun Route.queryRoutes(
    ledgerClient: LedgerClient,
    operatorParty: String,
    auditorParty: String
) {
    authenticate("auth-jwt") {
        route("/api/queries") {

            post("/requests") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val request = call.receive<CreateQueryRequest>()

                // any other level would be priced at the level-2 rate and produce an unusable insight
                if (request.params.level != 1 && request.params.level != 2) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "Unknown query level '${request.params.level}' - expected 1 (aggregated) or 2 (detailed)"
                        )
                    )
                }

                logger.info { "Creating query request for party $party" }

                val now = Instant.now()
                val template = QueryRequest(
                    "QREQ-${UUID.randomUUID()}",
                    party,
                    operatorParty,
                    auditorParty,
                    request.params.toDamlType(),
                    now,
                    now
                )
                val update = template.create()
                val result = ledgerClient.submitUpdate(party, update)
                val contractId = result.getCreatedContractIds().firstOrNull() ?: ""

                call.respond(HttpStatusCode.Created, SuccessResponse(
                    message = "Query request created",
                    contractId = contractId
                ))
            }

            get("/requests") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing query requests for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, QueryRequest.COMPANION)

                val requests = contracts.map { contract ->
                    val data = contract.data
                    QueryRequestDto(
                        contractId = contract.id.contractId,
                        requestId = data.requestId,
                        consumer = data.consumer,
                        params = queryParamsToDto(data.params),
                        requestDate = data.requestDate.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, requests)
            }

            delete("/requests/{id}") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                logger.info { "Withdrawing query request $contractId for party $party" }

                val cid = QueryRequest.ContractId(contractId)
                val update = cid.exerciseWithdrawQueryRequest()
                ledgerClient.submitUpdate(party, update)

                call.respond(HttpStatusCode.OK, SuccessResponse("Query request withdrawn"))
            }

            get("/proposals") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing query proposals for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, QueryProposal.COMPANION)

                val proposals = contracts.map { contract ->
                    val data = contract.data
                    QueryProposalDto(
                        contractId = contract.id.contractId,
                        proposalId = data.proposalId,
                        consumer = data.consumer,
                        escrow = data.possibleEscrows.firstOrNull(),
                        params = queryParamsToDto(data.params),
                        tokenCost = data.tokenCost,
                        proposalDate = data.proposalDate.toString(),
                        escrowAccepted = data.signerEscrow.isPresent
                    )
                }

                call.respond(HttpStatusCode.OK, proposals)
            }

            post("/proposals/{id}/accept") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                val request = call.receive<AcceptProposalRequest>()

                logger.info { "Accepting query proposal $contractId for party $party" }

                val cid = QueryProposal.ContractId(contractId)
                val holdingCid = Holding.ContractId(request.holdingContractId)
                val update = cid.exerciseLockFundsForQuery(holdingCid)
                val result = ledgerClient.submitUpdate(party, update)

                val fundedQueryId = result.getCreatedContractIds().firstOrNull()

                call.respond(HttpStatusCode.OK, SuccessResponse(
                    message = "Query proposal accepted, funds locked",
                    contractId = fundedQueryId
                ))
            }

            post("/proposals/{id}/reject") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                logger.info { "Rejecting query proposal $contractId for party $party" }

                val cid = QueryProposal.ContractId(contractId)
                val update = cid.exerciseRejectQueryProposal()
                ledgerClient.submitUpdate(party, update)

                call.respond(HttpStatusCode.OK, SuccessResponse("Query proposal rejected"))
            }

            // lets a consumer find a funded query to reclaim without its contract id
            get("/funded") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing funded queries for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, FundedQuery.COMPANION)

                val funded = contracts.map { contract ->
                    val data = contract.data
                    FundedQueryDto(
                        contractId = contract.id.contractId,
                        queryId = data.queryId,
                        proposalId = data.proposalId,
                        consumer = data.consumer,
                        escrow = data.escrow,
                        tokenCost = data.tokenCost,
                        fundedDate = data.fundedDate.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, funded)
            }

            post("/funded/{id}/reclaim") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                logger.info { "Reclaiming expired funds from funded query $contractId for party $party" }

                // the deadline is enforced on-ledger by RemoveLock
                val cid = FundedQuery.ContractId(contractId)
                val update = cid.exerciseReclaimExpiredFunds()
                val result = ledgerClient.submitUpdate(party, update)
                val holdingId = result.getCreatedContractIds().firstOrNull() ?: ""

                call.respond(HttpStatusCode.OK, SuccessResponse(
                    message = "Locked funds reclaimed",
                    contractId = holdingId
                ))
            }

            get("/results") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing query results for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, QueryResult.COMPANION)

                val results = contracts.map { contract ->
                    val data = contract.data
                    QueryResultDto(
                        contractId = contract.id.contractId,
                        resultId = data.resultId,
                        queryId = data.queryId,
                        proposalId = data.proposalId,
                        consumer = data.consumer,
                        params = queryParamsToDto(data.params),
                        tokenCost = data.tokenCost,
                        completionDate = data.completionDate.toString(),
                        executionTimestamp = data.executionTimestamp.toString(),
                        results = insightsToDto(data.results)
                    )
                }

                call.respond(HttpStatusCode.OK, results)
            }
        }
    }
}

private fun queryParamsToDto(params: QueryParams): QueryParamsDto {
    return QueryParamsDto(
        level = params.level.toInt(),
        trustScore = params.trustScore.map { rangeToDto<Double>(it) }.orElse(null),
        propertyAddress = params.propertyAddress.map { addrs -> addrs.map { addressToDto(it) } }.orElse(null),
        salePrice = params.salePrice.map { rangeToDto<Double>(it) }.orElse(null),
        saleDate = params.saleDate.map { rangeToDto<String>(it) }.orElse(null),
        propertyTypes = params.propertyTypes.orElse(null),
        squareMeters = params.squareMeters.map { rangeToDto<Double>(it) }.orElse(null),
        numberOfRooms = params.numberOfRooms.map { it.map { r -> r.toInt() } }.orElse(null),
        yearBuilt = params.yearBuilt.map { rangeToDto<Int>(it) }.orElse(null),
        energyRating = params.energyRating.map { ratings -> ratings.map { it.name } }.orElse(null),
        tags = params.tags.orElse(null)
    )
}

private fun addressToDto(address: Address): AddressDto {
    return AddressDto(
        postalCode = address.postalCode.orElse(null),
        street = address.street.orElse(null),
        city = address.city.orElse(null),
        country = address.country.orElse(null),
        additionalInfo = address.additionalInfo.orElse(null)
    )
}

@Suppress("UNCHECKED_CAST")
private fun <T> rangeToDto(range: Range<*>): RangeDto<T> {
    return RangeDto(
        from = range.from.map {
            when (it) {
                is java.math.BigDecimal -> it.toDouble() as T
                is java.time.LocalDate -> it.toString() as T
                is Long -> it.toInt() as T
                else -> it as T
            }
        }.orElse(null),
        to = range.to.map {
            when (it) {
                is java.math.BigDecimal -> it.toDouble() as T
                is java.time.LocalDate -> it.toString() as T
                is Long -> it.toInt() as T
                else -> it as T
            }
        }.orElse(null)
    )
}

private fun insightsToDto(insights: List<com.dsrs.daml.types.Insight>): List<InsightDto> {
    return insights.map { insight ->
        when (insight) {
            is com.dsrs.daml.types.insight.Level1 -> InsightDto(
                level = 1,
                transactionCount = insight.base.transactionCount.toInt(),
                avgSalePrice = insight.base.avgSalePrice.map { it.toDouble() }.orElse(null),
                avgTrustScore = insight.base.avgTrustScore.map { it.toDouble() }.orElse(null),
                avgYearBuilt = insight.base.avgYearBuilt.map { it.toDouble() }.orElse(null),
                avgSquareMeters = insight.base.avgSquareMeters.map { it.toDouble() }.orElse(null),
                avgNumberOfRooms = insight.base.avgNumberOfRooms.map { it.toDouble() }.orElse(null),
                medianEnergyRating = insight.base.medianEnergyRating.map { it.name }.orElse(null),
                details = null
            )
            is com.dsrs.daml.types.insight.Level2 -> InsightDto(
                level = 2,
                transactionCount = insight.base.transactionCount.toInt(),
                avgSalePrice = insight.base.avgSalePrice.map { it.toDouble() }.orElse(null),
                avgTrustScore = insight.base.avgTrustScore.map { it.toDouble() }.orElse(null),
                avgYearBuilt = insight.base.avgYearBuilt.map { it.toDouble() }.orElse(null),
                avgSquareMeters = insight.base.avgSquareMeters.map { it.toDouble() }.orElse(null),
                avgNumberOfRooms = insight.base.avgNumberOfRooms.map { it.toDouble() }.orElse(null),
                medianEnergyRating = insight.base.medianEnergyRating.map { it.name }.orElse(null),
                details = detailedMetricsToDto(insight.details)
            )
            else -> InsightDto(level = 0, transactionCount = 0)
        }
    }
}

private fun detailedMetricsToDto(details: com.dsrs.daml.types.DetailedMetrics): DetailedMetricsDto {
    return DetailedMetricsDto(
        priceRange = details.priceRange.map { rangeToDto<Double>(it) }.orElse(null),
        priceVariance = details.priceVariance.map { it.toDouble() }.orElse(null),
        medianPrice = details.medianPrice.map { it.toDouble() }.orElse(null),
        avgPricePerSqm = details.avgPricePerSqm.map { it.toDouble() }.orElse(null),
        pricePerSqmRange = details.pricePerSqmRange.map { rangeToDto<Double>(it) }.orElse(null),
        yearBuiltRange = details.yearBuiltRange.map { rangeToDto<Int>(it) }.orElse(null),
        sqmRange = details.sqmRange.map { rangeToDto<Double>(it) }.orElse(null),
        sqmVariance = details.sqmVariance.map { it.toDouble() }.orElse(null),
        roomsRange = details.roomsRange.map { rangeToDto<Int>(it) }.orElse(null),
        propertyTypeDistribution = details.propertyTypeDistribution.map { tuples ->
            tuples.map { DistributionEntryDto(it._1, it._2.toInt()) }
        }.orElse(null),
        energyRatingDistribution = details.energyRatingDistribution.map { tuples ->
            tuples.map { DistributionEntryDto(it._1.name, it._2.toInt()) }
        }.orElse(null),
        cityDistribution = details.cityDistribution.map { tuples ->
            tuples.map { DistributionEntryDto(it._1, it._2.toInt()) }
        }.orElse(null),
        saleDateRange = details.saleDateRange.map { rangeToDto<String>(it) }.orElse(null),
        topTags = details.topTags.map { tuples ->
            tuples.map { DistributionEntryDto(it._1, it._2.toInt()) }
        }.orElse(null),
        avgContributorsPerTx = details.avgContributorsPerTx.map { it.toDouble() }.orElse(null),
        avgDisputesPerTx = details.avgDisputesPerTx.map { it.toDouble() }.orElse(null),
        disputeRate = details.disputeRate.map { it.toDouble() }.orElse(null)
    )
}

private fun QueryParamsDto.toDamlType(): QueryParams {
    return QueryParams(
        level.toLong(),
        Optional.ofNullable(trustScore?.toDecimalRange()),
        Optional.ofNullable(propertyAddress?.map { it.toDamlType() }),
        Optional.ofNullable(salePrice?.toDecimalRange()),
        Optional.ofNullable(saleDate?.toDateRange()),
        Optional.ofNullable(propertyTypes),
        Optional.ofNullable(squareMeters?.toDecimalRange()),
        Optional.ofNullable(numberOfRooms?.map { it.toLong() }),
        Optional.ofNullable(yearBuilt?.toLongRange()),
        Optional.ofNullable(energyRating?.mapNotNull { parseEnergyRating(it) }),
        Optional.ofNullable(tags)
    )
}

private fun RangeDto<Double>.toDecimalRange(): Range<BigDecimal> {
    return Range(
        Optional.ofNullable(from?.let { BigDecimal(it.toString()) }),
        Optional.ofNullable(to?.let { BigDecimal(it.toString()) })
    )
}

private fun RangeDto<String>.toDateRange(): Range<LocalDate> {
    return Range(
        Optional.ofNullable(from?.let { LocalDate.parse(it) }),
        Optional.ofNullable(to?.let { LocalDate.parse(it) })
    )
}

private fun RangeDto<Int>.toLongRange(): Range<Long> {
    return Range(
        Optional.ofNullable(from?.toLong()),
        Optional.ofNullable(to?.toLong())
    )
}
