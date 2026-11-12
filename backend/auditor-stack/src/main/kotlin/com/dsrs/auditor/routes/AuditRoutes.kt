package com.dsrs.auditor.routes

import com.dsrs.auditor.db.repositories.MintObservationRepository
import com.dsrs.auditor.models.*
import com.dsrs.auditor.services.VerificationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.auditRoutes(
    verificationService: VerificationService,
    mintObservationRepo: MintObservationRepository
) {
    route("/api/audit") {
        authenticate("admin-auth") {
            get("/mints") {
                val mints = mintObservationRepo.findAll()
                val anomalies = mintObservationRepo.findAnomalous()
                call.respond(HttpStatusCode.OK, MintObservationResult(
                    observed = mints.size,
                    mintedByTokenType = mintObservationRepo.mintedByTokenType(),
                    anomalous = anomalies.size,
                    note = "audit tokens are minted only by workflow events, so one arriving " +
                        "with no event is an anomaly; reward tokens are issued by the operator " +
                        "against off-platform payment and arriving alone is expected",
                    mints = mints.map {
                        MintObservation(
                            transactionOffset = it.transactionOffset,
                            tokenType = it.tokenType,
                            totalAmount = it.totalAmount,
                            accompaniedBy = it.accompaniedBy,
                            reason = it.reason,
                            sourceEventId = it.sourceEventId,
                            anomalous = it.anomalous
                        )
                    }
                ))
            }

            post("/pricing") {
                try {
                    val request = call.receive<VerifyPricingRequest>()
                    logger.info { "Verifying pricing for query: ${request.queryId ?: request.proposalId}" }

                    val result = verificationService.verifyPricing(request)

                    if (result.matches) {
                        call.respond(HttpStatusCode.OK, result)
                    } else {
                        logger.warn { "Pricing verification FAILED: expected=${result.expected}, actual=${result.actual}" }
                        call.respond(HttpStatusCode.OK, result)  // a mismatch is still a 200
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Pricing verification failed" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Verification failed"))
                }
            }

            // The route authenticates with an admin key, so the requester comes in the body.
            post("/reward") {
                try {
                    val request = call.receive<VerifyRewardRequest>()
                    logger.info {
                        "Verifying reward for query=${request.queryId}, " +
                            "transaction=${request.transactionId}, requester=${request.requester}"
                    }

                    val result = verificationService.verifyReward(request)

                    if (result.matches) {
                        call.respond(HttpStatusCode.OK, result)
                    } else {
                        logger.warn { "Reward verification FAILED for ${request.transactionId}" }
                        call.respond(HttpStatusCode.OK, result)
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Reward verification failed" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Verification failed"))
                }
            }

            post("/inclusion") {
                try {
                    val request = call.receive<VerifyInclusionRequest>()
                    logger.info { "Verifying inclusion for transaction=${request.transactionId}, from=${request.fromTime} to=${request.toTime}" }

                    val result = verificationService.verifyInclusion(request)
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Inclusion verification failed" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Verification failed"))
                }
            }
        }
    }
}
