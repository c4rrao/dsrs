package com.dsrs.operator.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.query.FundedQuery
import com.dsrs.daml.query.QueryProposal
import com.dsrs.daml.query.QueryRequest
import com.dsrs.daml.query.QueryResult
import com.dsrs.operator.config.OperatorConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class QueryRequestDto(
    val contractId: String,
    val requestId: String,
    val consumer: String,
    val level: Int,
    val requestDate: String
)

@Serializable
data class QueryProposalDto(
    val contractId: String,
    val proposalId: String,
    val consumer: String,
    val tokenCost: Long,
    val estimatedResults: Long,
    val proposalDate: String
)

@Serializable
data class FundedQueryDto(
    val contractId: String,
    val queryId: String,
    val proposalId: String,
    val consumer: String,
    val escrow: String,
    val tokenCost: Long,
    val fundedDate: String
)

@Serializable
data class QueryResultDto(
    val contractId: String,
    val resultId: String,
    val consumer: String,
    val tokenCost: Long,
    val resultsCount: Int,
    val completionDate: String
)

fun Route.queryRoutes(
    ledgerClient: LedgerClient,
    config: OperatorConfig
) {
    authenticate("admin-auth") {
        route("/api/queries") {

            get("/requests") {
                val contracts = ledgerClient.queryContractsTyped(
                    party = config.operatorParty,
                    companion = QueryRequest.COMPANION
                )

                call.respond(contracts.map { contract ->
                    val query = contract.data
                    QueryRequestDto(
                        contractId = contract.id.contractId,
                        requestId = query.requestId,
                        consumer = query.consumer,
                        level = query.params.level.toInt(),
                        requestDate = query.requestDate.toString()
                    )
                })
            }

            get("/proposals") {
                val contracts = ledgerClient.queryContractsTyped(
                    party = config.operatorParty,
                    companion = QueryProposal.COMPANION
                )

                call.respond(contracts.map { contract ->
                    val proposal = contract.data
                    QueryProposalDto(
                        contractId = contract.id.contractId,
                        proposalId = proposal.proposalId,
                        consumer = proposal.consumer,
                        tokenCost = proposal.tokenCost,
                        estimatedResults = proposal.pricingMetadata.numTransactions,
                        proposalDate = proposal.proposalDate.toString()
                    )
                })
            }

            get("/funded") {
                val contracts = ledgerClient.queryContractsTyped(
                    party = config.operatorParty,
                    companion = FundedQuery.COMPANION
                )

                call.respond(contracts.map { contract ->
                    val funded = contract.data
                    FundedQueryDto(
                        contractId = contract.id.contractId,
                        queryId = funded.queryId,
                        proposalId = funded.proposalId,
                        consumer = funded.consumer,
                        escrow = funded.escrow,
                        tokenCost = funded.tokenCost,
                        fundedDate = funded.fundedDate.toString()
                    )
                })
            }

            get("/results") {
                val contracts = ledgerClient.queryContractsTyped(
                    party = config.operatorParty,
                    companion = QueryResult.COMPANION
                )

                call.respond(contracts.map { contract ->
                    val result = contract.data
                    QueryResultDto(
                        contractId = contract.id.contractId,
                        resultId = result.resultId,
                        consumer = result.consumer,
                        tokenCost = result.tokenCost,
                        resultsCount = result.results.size,
                        completionDate = result.completionDate.toString()
                    )
                })
            }

            get("/requests/{id}") {
                val contractId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                val contract = ledgerClient.getContractTyped(
                    party = config.operatorParty,
                    contractId = contractId,
                    companion = QueryRequest.COMPANION
                )

                if (contract == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Query request not found"))
                } else {
                    val query = contract.data
                    call.respond(QueryRequestDto(
                        contractId = contract.id.contractId,
                        requestId = query.requestId,
                        consumer = query.consumer,
                        level = query.params.level.toInt(),
                        requestDate = query.requestDate.toString()
                    ))
                }
            }
        }
    }
}
