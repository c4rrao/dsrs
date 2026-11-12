package com.dsrs.participant.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.contribution.Dispute
import com.dsrs.daml.types.ContributionData
import com.dsrs.participant.models.*
import com.dsrs.participant.services.TokenMintAuthorityService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.disputeRoutes(
    ledgerClient: LedgerClient,
    tokenMintAuthorityService: TokenMintAuthorityService
) {
    authenticate("auth-jwt") {
        route("/api/disputes") {

            get {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing disputes for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, Dispute.COMPANION)

                val disputes = contracts.map { contract ->
                    val data = contract.data

                    DisputeDto(
                        contractId = contract.id.contractId,
                        disputeId = data.disputeId,
                        transactionId = data.transactionId.unpack,
                        reason = data.reason,
                        contributors = data.contributorsData.keys.toList(),
                        votesCount = data.votes.size,
                        contributorsCount = data.contributorsData.size
                    )
                }

                call.respond(HttpStatusCode.OK, disputes)
            }

            get("/{id}") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                logger.debug { "Getting dispute details $contractId for party $party" }

                val contract = ledgerClient.getContractTyped(party, contractId, Dispute.COMPANION)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Dispute not found"))

                val data = contract.data
                val myVoteIndex = data.votes[party]?.toInt()

                val detail = DisputeDetailDto(
                    contractId = contract.id.contractId,
                    disputeId = data.disputeId,
                    transactionId = data.transactionId.unpack,
                    reason = data.reason,
                    contributors = data.contributorsData.keys.toList(),
                    votesCount = data.votes.size,
                    contributorsCount = data.contributorsData.size,
                    allContributionOptions = data.allData.map { contributionDataToDto(it) },
                    myVote = myVoteIndex,
                    votesByParty = data.contributorsData.keys.sorted().map { contributor ->
                        DisputeVoteDto(
                            party = contributor,
                            votedFor = data.votes[contributor]?.toInt(),
                            initialPosition = data.initialPositions[contributor]?.toInt()
                        )
                    }
                )

                call.respond(HttpStatusCode.OK, detail)
            }

            post("/{id}/vote") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                val request = call.receive<VoteRequest>()

                logger.info { "Voting on dispute $contractId for party $party, option ${request.voteForDataIndex}" }

                // the voter cannot see the mint authority, and a resolving vote mints through it
                val authorityDisclosure = tokenMintAuthorityService.getDisclosedAuthority()

                val cid = Dispute.ContractId(contractId)
                val update = cid.exerciseVote(request.voteForDataIndex.toLong(), party)
                val result = ledgerClient.submitUpdate(party, update, listOf(authorityDisclosure))

                // A resolving vote creates the mint's HoldingOffers before the Contribution, so the
                // outcome is found by template, not by taking the first created contract.
                val createdContracts = result.getCreatedContracts()
                val resolvedContribution = createdContracts.firstOrNull { it.second.entityName == "Contribution" }
                val continuingDispute = createdContracts.firstOrNull { it.second.entityName == "Dispute" }

                val (newContractId, resolved) = when {
                    resolvedContribution != null -> resolvedContribution.first to true
                    continuingDispute != null -> continuingDispute.first to false
                    else -> return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("No Contribution or Dispute contract created")
                    )
                }

                call.respond(HttpStatusCode.OK, VoteResponse(
                    message = if (resolved) "Dispute resolved" else "Vote recorded",
                    contractId = newContractId,
                    resolved = resolved
                ))
            }

            // For a party already in the dispute who thinks none of the options is right.
            post("/{id}/propose-data") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                val request = call.receive<ProposeDisputeDataRequest>()

                val contract = ledgerClient.getContractTyped(party, contractId, Dispute.COMPANION)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Dispute not found"))

                val proposedData = request.contributionData.toDamlType()

                // only to tell the caller whether the proposal became a plain vote
                val existingIndex = contract.data.allData.indexOf(proposedData)
                val deduplicated = existingIndex >= 0

                logger.info {
                    "Party $party proposing data on dispute $contractId " +
                        (if (deduplicated) "(matches existing option $existingIndex)" else "(new option)")
                }

                // a deduplicated proposal is a Vote and may resolve the dispute, so same disclosure
                val authorityDisclosure = tokenMintAuthorityService.getDisclosedAuthority()

                val cid = Dispute.ContractId(contractId)
                val update = cid.exerciseProposeDisputeData(party, proposedData)
                val result = ledgerClient.submitUpdate(party, update, listOf(authorityDisclosure))

                val createdContracts = result.getCreatedContracts()
                val resolvedContribution = createdContracts.firstOrNull { it.second.entityName == "Contribution" }
                val updatedDispute = createdContracts.firstOrNull { it.second.entityName == "Dispute" }

                val (newContractId, resolved) = when {
                    resolvedContribution != null -> resolvedContribution.first to true
                    updatedDispute != null -> updatedDispute.first to false
                    else -> return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("No Contribution or Dispute contract created")
                    )
                }

                call.respond(HttpStatusCode.OK, ProposeDisputeDataResponse(
                    message = when {
                        resolved -> "Proposal matched an existing option and resolved the dispute"
                        deduplicated -> "Proposal matched an existing option; recorded as a vote for it"
                        else -> "New data alternative added and voted for"
                    },
                    contractId = newContractId,
                    resolved = resolved,
                    deduplicated = deduplicated,
                    optionCount = if (resolved) null
                                  else if (deduplicated) contract.data.allData.size
                                  else contract.data.allData.size + 1
                ))
            }
        }
    }
}

private fun contributionDataToDto(data: ContributionData): ContributionDataDto {
    return ContributionDataDto(
        propertyAddress = data.propertyAddress.map { addressToDto(it) }.orElse(null),
        salePrice = data.salePrice.map { it.toDouble() }.orElse(null),
        saleDate = data.saleDate.map { it.toString() }.orElse(null),
        propertyType = data.propertyType.orElse(null),
        squareMeters = data.squareMeters.map { it.toDouble() }.orElse(null),
        numberOfRooms = data.numberOfRooms.map { it.toInt() }.orElse(null),
        yearBuilt = data.yearBuilt.map { it.toInt() }.orElse(null),
        energyRating = data.energyRating.map { it.name }.orElse(null),
        tags = data.tags.orElse(null),
        additionalInfo = data.additionalInfo.orElse(null)
    )
}

private fun addressToDto(address: com.dsrs.daml.types.Address): AddressDto {
    return AddressDto(
        postalCode = address.postalCode.orElse(null),
        street = address.street.orElse(null),
        city = address.city.orElse(null),
        country = address.country.orElse(null),
        additionalInfo = address.additionalInfo.orElse(null)
    )
}
