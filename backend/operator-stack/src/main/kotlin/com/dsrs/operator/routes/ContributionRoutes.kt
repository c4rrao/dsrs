package com.dsrs.operator.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.contribution.ContributionRequest
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ContributionRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class ContributionDto(
    val contractId: String,
    val contributionId: String,
    val transactionId: String,
    val status: String,
    val totalTrustScore: String,
    val contributorCount: Int,
    // one entry per resolved dispute; a transaction can be disputed again later
    val disputeCount: Int,
    val propertyCity: String?,
    val salePrice: String?,
    val createdAt: String
)

@Serializable
data class ContributionRequestSummaryDto(
    val contractId: String,
    val requestId: String,
    val contributor: String,
    val transactionId: String,
    val requestDate: String
)

fun Route.contributionRoutes(
    contributionRepo: ContributionRepository,
    ledgerClient: LedgerClient,
    config: OperatorConfig
) {
    authenticate("admin-auth") {
        route("/api/contributions") {

            get("/requests") {
                val contracts = ledgerClient.queryContractsTyped(
                    party = config.operatorParty,
                    companion = ContributionRequest.COMPANION
                )

                call.respond(contracts.map { contract ->
                    val data = contract.data
                    ContributionRequestSummaryDto(
                        contractId = contract.id.contractId,
                        requestId = data.requestId,
                        contributor = data.contributor,
                        transactionId = data.transactionId.unpack,
                        requestDate = data.requestDate.toString()
                    )
                })
            }

            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0
                val includePending = call.request.queryParameters["includePending"]?.toBoolean() ?: false

                val contributions = contributionRepo.findAll(limit, offset, includePending)

                call.respond(contributions.map { c ->
                    val contributorsJson = try { Json.decodeFromString<JsonObject>(c.contributorsDataJson) } catch (e: Exception) { null }
                    ContributionDto(
                        contractId = c.contractId,
                        contributionId = c.contributionId,
                        transactionId = c.transactionId,
                        status = c.status,
                        totalTrustScore = c.totalTrustScore.toString(),
                        contributorCount = contributorsJson?.size ?: 0,
                        disputeCount = disputeCount(c.disputeDatesJson),
                        propertyCity = c.propertyCity,
                        salePrice = c.salePrice?.toString(),
                        createdAt = c.createdAt.toString()
                    )
                })
            }

            get("/transaction/{txId}") {
                val txId = call.parameters["txId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Transaction ID required"))

                val contribution = contributionRepo.findByTransactionId(txId)

                if (contribution == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Contribution not found"))
                } else {
                    val contributorsJson = try { Json.decodeFromString<JsonObject>(contribution.contributorsDataJson) } catch (e: Exception) { null }
                    call.respond(ContributionDto(
                        contractId = contribution.contractId,
                        contributionId = contribution.contributionId,
                        transactionId = contribution.transactionId,
                        status = contribution.status,
                        totalTrustScore = contribution.totalTrustScore.toString(),
                        contributorCount = contributorsJson?.size ?: 0,
                        disputeCount = disputeCount(contribution.disputeDatesJson),
                        propertyCity = contribution.propertyCity,
                        salePrice = contribution.salePrice?.toString(),
                        createdAt = contribution.createdAt.toString()
                    ))
                }
            }
        }
    }
}

private fun disputeCount(disputeDatesJson: String): Int =
    try { Json.decodeFromString<JsonArray>(disputeDatesJson).size } catch (e: Exception) { 0 }
