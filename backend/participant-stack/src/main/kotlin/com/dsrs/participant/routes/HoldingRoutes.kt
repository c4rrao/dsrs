package com.dsrs.participant.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.token.holding.Holding
import com.dsrs.daml.token.holding.HoldingOffer
import com.dsrs.participant.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.holdingRoutes(ledgerClient: LedgerClient) {
    authenticate("auth-jwt") {
        route("/api/holdings") {

            get {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing holdings for party $party" }

                // The ACS returns every holding the party signs, and operator and auditor sign
                // nearly all of them as issuer or witness, so keep only the ones this party owns.
                val contracts = ledgerClient.queryContractsTyped(party, Holding.COMPANION)
                    .filter { it.data.owner == party }

                val holdings = contracts.map { contract ->
                    val data = contract.data
                    HoldingDto(
                        contractId = contract.id.contractId,
                        owner = data.owner,
                        tokenType = data.tokenType.name,
                        amount = data.amount,
                        locked = data.locker.isPresent,
                        lockedFor = data.locker.orElse(null)
                    )
                }

                call.respond(HttpStatusCode.OK, holdings)
            }

            post("/transfer") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val request = call.receive<TransferRequest>()

                logger.info { "Transferring tokens from party $party to ${request.recipient}" }

                val cid = Holding.ContractId(request.holdingContractId)
                val reference = java.util.Optional.ofNullable(request.reference?.takeIf { it.isNotBlank() })
                val result = if (request.amount != null) {
                    val update = cid.exerciseTransfer(request.recipient, request.amount.toLong(), reference)
                    ledgerClient.submitUpdate(party, update)
                } else {
                    val update = cid.exerciseTransferAll(request.recipient, reference)
                    ledgerClient.submitUpdate(party, update)
                }

                // the offer is the first contract Transfer creates
                val offerContractId = result.getCreatedContractIds().firstOrNull()

                call.respond(HttpStatusCode.OK, SuccessResponse(
                    message = "Transfer initiated, offer created",
                    contractId = offerContractId
                ))
            }

            post("/merge") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val request = call.receive<MergeRequest>()

                logger.info { "Merging ${request.otherContractIds.size + 1} holdings for party $party" }

                val cid = Holding.ContractId(request.holdingContractId)
                val otherCids = request.otherContractIds.map { Holding.ContractId(it) }
                val update = cid.exerciseMerge(otherCids)
                val result = ledgerClient.submitUpdate(party, update)

                val mergedContractId = result.getCreatedContractIds().firstOrNull()

                call.respond(HttpStatusCode.OK, SuccessResponse(
                    message = "Holdings merged",
                    contractId = mergedContractId
                ))
            }

            get("/offers") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing holding offers for party $party" }

                // same filtering as above: offers to this party, plus their own still-pending ones
                val contracts = ledgerClient.queryContractsTyped(party, HoldingOffer.COMPANION)
                    .filter { it.data.to == party || it.data.from == party }

                val offers = contracts.map { contract ->
                    val data = contract.data
                    HoldingOfferDto(
                        contractId = contract.id.contractId,
                        from = data.from,
                        to = data.to,
                        tokenType = data.tokenType.name,
                        amount = data.amount,
                        reference = data.reference.orElse(null)
                    )
                }

                call.respond(HttpStatusCode.OK, offers)
            }

            post("/offers/{id}/accept") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                logger.info { "Accepting holding offer $contractId for party $party" }

                val cid = HoldingOffer.ContractId(contractId)
                val update = cid.exerciseAcceptHoldingOffer()
                val result = ledgerClient.submitUpdate(party, update)

                val holdingContractId = result.getCreatedContractIds().firstOrNull()

                call.respond(HttpStatusCode.OK, SuccessResponse(
                    message = "Holding offer accepted",
                    contractId = holdingContractId
                ))
            }

            post("/offers/{id}/reject") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                logger.info { "Rejecting holding offer $contractId for party $party" }

                val cid = HoldingOffer.ContractId(contractId)
                val update = cid.exerciseRejectHoldingOffer()
                val result = ledgerClient.submitUpdate(party, update)

                val returnedHoldingId = result.getCreatedContractIds().firstOrNull()

                call.respond(HttpStatusCode.OK, SuccessResponse(
                    message = "Holding offer rejected, tokens returned to sender",
                    contractId = returnedHoldingId
                ))
            }
        }
    }
}
