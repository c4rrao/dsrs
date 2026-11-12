package com.dsrs.participant.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.contribution.Contribution
import com.dsrs.daml.contribution.ContributionRequest
import com.dsrs.daml.types.Address
import com.dsrs.daml.types.ContributionData
import com.dsrs.daml.types.EnergyRating
import com.dsrs.daml.types.Role
import com.dsrs.daml.types.TransactionId
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

fun Route.contributionRoutes(
    ledgerClient: LedgerClient,
    operatorParty: String,
    auditorParty: String
) {
    authenticate("auth-jwt") {
        route("/api/contributions") {

            post("/requests") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val request = call.receive<CreateContributionRequest>()

                logger.info { "Creating contribution request for party $party, transaction ${request.transactionId}" }

                val role = parseRole(request.roleInTransaction)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "Unknown role '${request.roleInTransaction}' - expected one of " +
                                "Buyer, Seller, Broker, Notary, TaxAuthority"
                        )
                    )

                val now = Instant.now()
                val template = ContributionRequest(
                    "REQ-${UUID.randomUUID()}",
                    party,
                    operatorParty,
                    auditorParty,
                    TransactionId(request.transactionId),
                    request.contributionData.toDamlType(),
                    role,
                    now,
                    now
                )
                val update = template.create()
                // the ledger refuses malformed values when the request is created; that is a 400
                val result = try {
                    ledgerClient.submitUpdate(party, update)
                } catch (e: io.grpc.StatusRuntimeException) {
                    if (e.status.code != io.grpc.Status.Code.INVALID_ARGUMENT &&
                        e.status.code != io.grpc.Status.Code.FAILED_PRECONDITION) throw e
                    logger.info { "Ledger refused contribution request from $party: ${e.status.description}" }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "Contribution refused: a sale date is required and cannot be after " +
                                "today, a price, area or construction year must be above zero, " +
                                "and a room count cannot be negative"
                        )
                    )
                }
                val contractId = result.getCreatedContractIds().firstOrNull() ?: ""

                call.respond(HttpStatusCode.Created, SuccessResponse(
                    message = "Contribution request created",
                    contractId = contractId
                ))
            }

            get("/requests") {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing contribution requests for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, ContributionRequest.COMPANION)

                val requests = contracts.map { contract ->
                    val data = contract.data
                    ContributionRequestDto(
                        contractId = contract.id.contractId,
                        requestId = data.requestId,
                        contributor = data.contributor,
                        transactionId = data.transactionId.unpack,
                        contributionData = contributionDataToDto(data.contributionData),
                        requestDate = data.requestDate.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, requests)
            }

            delete("/requests/{id}") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val contractId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                logger.info { "Withdrawing contribution request $contractId for party $party" }

                val cid = ContributionRequest.ContractId(contractId)
                val update = cid.exerciseWithdrawContribution()
                ledgerClient.submitUpdate(party, update)

                call.respond(HttpStatusCode.OK, SuccessResponse("Contribution request withdrawn"))
            }

            get {
                val party = call.principal<JWTPrincipal>()!!.getParty()

                logger.debug { "Listing contributions for party $party" }

                val contracts = ledgerClient.queryContractsTyped(party, Contribution.COMPANION)

                val contributions = contracts.map { contract ->
                    val data = contract.data

                    ContributionDto(
                        contractId = contract.id.contractId,
                        contributionId = data.contributionId,
                        transactionId = data.transactionId.unpack,
                        contributionData = contributionDataToDto(data.contributionData),
                        trustScore = data.trustScore.total.toDouble(),
                        contributors = data.contributorsData.keys.toList(),
                        originalSubmissionDate = data.originalSubmissionDate.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, contributions)
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

private fun addressToDto(address: Address): AddressDto {
    return AddressDto(
        postalCode = address.postalCode.orElse(null),
        street = address.street.orElse(null),
        city = address.city.orElse(null),
        country = address.country.orElse(null),
        additionalInfo = address.additionalInfo.orElse(null)
    )
}

// Internal because the dispute propose-data endpoint builds the same Daml value.

internal fun ContributionDataDto.toDamlType(): ContributionData {
    return ContributionData(
        Optional.ofNullable(propertyAddress?.toDamlType()),
        Optional.ofNullable(salePrice?.let { BigDecimal(it.toString()) }),
        Optional.ofNullable(saleDate?.let { LocalDate.parse(it) }),
        Optional.ofNullable(propertyType),
        Optional.ofNullable(squareMeters?.let { BigDecimal(it.toString()) }),
        Optional.ofNullable(numberOfRooms?.toLong()),
        Optional.ofNullable(yearBuilt?.toLong()),
        Optional.ofNullable(energyRating?.let { parseEnergyRating(it) }),
        Optional.ofNullable(tags),
        Optional.ofNullable(additionalInfo)
    )
}

internal fun AddressDto.toDamlType(): Address {
    return Address(
        Optional.ofNullable(postalCode),
        Optional.ofNullable(street),
        Optional.ofNullable(city),
        Optional.ofNullable(country),
        Optional.ofNullable(additionalInfo)
    )
}

// selects the trust score coefficient, and with it the contributor's share of every reward
internal fun parseRole(role: String): Role? = when (role.uppercase()) {
    "BUYER" -> Role.BUYER
    "SELLER" -> Role.SELLER
    "BROKER" -> Role.BROKER
    "NOTARY" -> Role.NOTARY
    "TAXAUTHORITY" -> Role.TAXAUTHORITY
    else -> null
}

internal fun parseEnergyRating(rating: String): EnergyRating? {
    return when (rating.uppercase()) {
        "A" -> EnergyRating.A
        "B" -> EnergyRating.B
        "C" -> EnergyRating.C
        "D" -> EnergyRating.D
        "E" -> EnergyRating.E
        "F" -> EnergyRating.F
        "G" -> EnergyRating.G
        else -> null
    }
}

