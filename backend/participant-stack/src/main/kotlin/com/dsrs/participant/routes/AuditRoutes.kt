package com.dsrs.participant.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.audit.AuditProposal
import com.dsrs.daml.audit.AuditRequest
import com.dsrs.daml.audit.AuditRequestParams
import com.dsrs.daml.audit.AuditRequestType
import com.dsrs.daml.audit.AuditResult
import com.dsrs.daml.audit.FundedAuditRequest
import com.dsrs.daml.token.holding.Holding
import com.dsrs.participant.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun Route.auditRoutes(
    ledgerClient: LedgerClient,
    auditorParty: String
) {
    authenticate("auth-jwt") {
        route("/api/audit") {

            post("/requests") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val request = call.receive<CreateAuditRequestDto>()

                logger.info { "Creating audit request for party $party: ${request.requestType}" }

                val requestType = when (request.requestType) {
                    "PricingVerification" -> AuditRequestType.PRICINGVERIFICATION
                    "RewardVerification" -> AuditRequestType.REWARDVERIFICATION
                    "InclusionVerification" -> AuditRequestType.INCLUSIONVERIFICATION
                    else -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid request type. Must be PricingVerification, RewardVerification, or InclusionVerification")
                    )
                }

                val params = AuditRequestParams(
                    Optional.ofNullable(request.params.queryId),
                    Optional.ofNullable(request.params.proposalId),
                    Optional.ofNullable(request.params.rewardGroupId),
                    Optional.ofNullable(request.params.transactionId),
                    request.params.fromTime?.let { Optional.of(Instant.parse(it)) } ?: Optional.empty(),
                    request.params.toTime?.let { Optional.of(Instant.parse(it)) } ?: Optional.empty()
                )

                val now = Instant.now()
                val requestId = "AUDIT-REQ-${party}-${UUID.randomUUID()}"

                val template = AuditRequest(
                    requestId,
                    party,
                    auditorParty,
                    requestType,
                    params,
                    now
                )

                val update = template.create()
                val result = ledgerClient.submitUpdate(party, update)
                val contractId = result.getCreatedContractIds().firstOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to create audit request")
                    )

                logger.info { "Audit request created: $contractId (ID: $requestId)" }

                call.respond(HttpStatusCode.Created, SuccessResponse(
                    message = "Audit request created. Auditor will propose a price.",
                    contractId = contractId
                ))
            }

            get("/requests") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing audit requests for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, AuditRequest.COMPANION)

                val requests = contracts.map { contract ->
                    val data = contract.data
                    AuditRequestDto(
                        contractId = contract.id.contractId,
                        requestId = data.requestId,
                        requester = data.requester,
                        requestType = data.requestType.toString(),
                        params = auditParamsToDto(data.params),
                        createdAt = data.createdAt.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, requests)
            }

            get("/proposals") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing audit proposals for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, AuditProposal.COMPANION)

                val proposals = contracts.map { contract ->
                    val data = contract.data
                    AuditProposalDto(
                        contractId = contract.id.contractId,
                        requestId = data.requestId,
                        requester = data.requester,
                        requestType = data.requestType.toString(),
                        params = auditParamsToDto(data.params),
                        estimatedCost = data.estimatedCost,
                        description = data.description,
                        proposedAt = data.proposedAt.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, proposals)
            }

            post("/proposals/{proposalId}/fund") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val proposalId = call.parameters["proposalId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Proposal ID required"))
                val request = call.receive<FundAuditRequestDto>()

                logger.info { "Funding audit proposal $proposalId for party $party" }

                val holding = ledgerClient.getContractTyped(
                    party = party,
                    contractId = request.holdingContractId,
                    companion = Holding.COMPANION
                )?.data ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Holding not found")
                )

                if (holding.tokenType != com.dsrs.daml.types.TokenType.AUDITTOKEN) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Holding must contain audit tokens")
                    )
                }

                val proposal = ledgerClient.getContractTyped(
                    party = party,
                    contractId = proposalId,
                    companion = AuditProposal.COMPANION
                )?.data ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Audit proposal not found")
                )

                if (holding.amount < proposal.estimatedCost) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Insufficient audit tokens. Need ${proposal.estimatedCost}, have ${holding.amount}")
                    )
                }

                val cid = AuditProposal.ContractId(proposalId)
                val update = cid.exerciseFundAudit(Holding.ContractId(request.holdingContractId))
                val result = ledgerClient.submitUpdate(party, update)

                val fundedCid = result.getCreatedContractIds().firstOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to fund audit request")
                    )

                logger.info { "Audit proposal funded: $fundedCid" }

                call.respond(HttpStatusCode.OK, SuccessResponse(
                    message = "Audit request funded. Auditor will now perform verification.",
                    contractId = fundedCid
                ))
            }

            get("/results") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing audit results for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, AuditResult.COMPANION)

                val results = contracts.map { contract ->
                    val data = contract.data
                    AuditResultDto(
                        contractId = contract.id.contractId,
                        requestId = data.requestId,
                        requester = data.requester,
                        verificationType = data.verificationType.toString(),
                        expected = data.result.expected.number.toInt(),
                        actual = data.result.actual.number.toInt(),
                        matches = data.result.matches,
                        // recomputed from the stored statistics rather than read off the verdict
                        statisticsChecked = data.result.expected.statistics.isPresent,
                        statisticsAgree = if (data.result.expected.statistics.isPresent)
                            data.result.expected.statistics == data.result.actual.statistics else null,
                        details = data.result.details,
                        tokensBurned = data.tokensBurned,
                        completedAt = data.completedAt.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, results)
            }

            delete("/requests/{requestId}") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val requestId = call.parameters["requestId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))

                logger.info { "Cancelling audit request $requestId for party $party" }

                val cid = AuditRequest.ContractId(requestId)
                val update = cid.exerciseCancelAuditRequest()
                ledgerClient.submitUpdate(party, update)

                call.respond(HttpStatusCode.OK, SuccessResponse("Audit request cancelled"))
            }

            delete("/proposals/{proposalId}") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val proposalId = call.parameters["proposalId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Proposal ID required"))

                logger.info { "Rejecting audit proposal $proposalId for party $party" }

                val cid = AuditProposal.ContractId(proposalId)
                val update = cid.exerciseRejectAuditProposal()
                ledgerClient.submitUpdate(party, update)

                call.respond(HttpStatusCode.OK, SuccessResponse("Audit proposal rejected"))
            }
        }
    }
}

private fun auditParamsToDto(params: AuditRequestParams): AuditRequestParamsDto {
    return AuditRequestParamsDto(
        queryId = params.queryId.orElse(null),
        proposalId = params.proposalId.orElse(null),
        rewardGroupId = params.rewardGroupId.orElse(null),
        transactionId = params.transactionId.orElse(null),
        fromTime = params.fromTime.map { it.toString() }.orElse(null),
        toTime = params.toTime.map { it.toString() }.orElse(null)
    )
}
