package com.dsrs.operator.routes

import com.dsrs.operator.services.DisputeService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class DisputeDto(
    val contractId: String,
    val disputeId: String,
    val transactionId: String,
    val disputedContributionId: String,
    val reason: String,
    val votesCount: Int,
    val contributorsCount: Int,
    val allDataCount: Int,
    val isUnanimous: Boolean,
    val originalSubmissionDate: String
)

@Serializable
data class DisputeDetailDto(
    val contractId: String,
    val disputeId: String,
    val transactionId: String,
    val disputedContributionId: String,
    val reason: String,
    val votes: Map<String, Int>,
    val contributorsCount: Int,
    val allDataCount: Int,
    val isUnanimous: Boolean,
    val originalSubmissionDate: String
)

fun Route.disputeRoutes(disputeService: DisputeService) {
    authenticate("admin-auth") {
        route("/api/disputes") {

            get {
                val disputes = disputeService.getAllDisputes()

                call.respond(disputes.map { (contractId, dispute) ->
                    DisputeDto(
                        contractId = contractId,
                        disputeId = dispute.disputeId,
                        transactionId = dispute.transactionId.unpack,
                        disputedContributionId = dispute.disputedContributionId,
                        reason = dispute.reason,
                        votesCount = dispute.votes.size,
                        contributorsCount = dispute.contributorsData.size,
                        allDataCount = dispute.allData.size,
                        isUnanimous = disputeService.isUnanimous(dispute),
                        originalSubmissionDate = dispute.originalSubmissionDate.toString()
                    )
                })
            }

            get("/pending") {
                val pendingDisputes = disputeService.getPendingDisputes()

                call.respond(pendingDisputes.map { (contractId, dispute) ->
                    DisputeDto(
                        contractId = contractId,
                        disputeId = dispute.disputeId,
                        transactionId = dispute.transactionId.unpack,
                        disputedContributionId = dispute.disputedContributionId,
                        reason = dispute.reason,
                        votesCount = dispute.votes.size,
                        contributorsCount = dispute.contributorsData.size,
                        allDataCount = dispute.allData.size,
                        isUnanimous = false,
                        originalSubmissionDate = dispute.originalSubmissionDate.toString()
                    )
                })
            }

            get("/{id}") {
                val contractId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                val dispute = disputeService.getDispute(contractId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Dispute not found"))

                call.respond(DisputeDetailDto(
                    contractId = contractId,
                    disputeId = dispute.disputeId,
                    transactionId = dispute.transactionId.unpack,
                    disputedContributionId = dispute.disputedContributionId,
                    reason = dispute.reason,
                    votes = dispute.votes.mapValues { it.value.toInt() },
                    contributorsCount = dispute.contributorsData.size,
                    allDataCount = dispute.allData.size,
                    isUnanimous = disputeService.isUnanimous(dispute),
                    originalSubmissionDate = dispute.originalSubmissionDate.toString()
                ))
            }
        }
    }
}
