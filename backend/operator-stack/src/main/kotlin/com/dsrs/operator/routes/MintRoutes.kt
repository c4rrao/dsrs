package com.dsrs.operator.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.da.types.Tuple2
import com.dsrs.daml.token.mint.MintEntitlement
import com.dsrs.daml.token.mint.TokenMintAuthority
import com.dsrs.daml.types.TokenType
import com.dsrs.operator.services.TokenMintAuthorityService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.time.Instant
import java.util.Base64
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Serializable
data class MintRecipient(
    val party: String,
    val amount: Long
)

@Serializable
data class MintRequest(
    val recipients: List<MintRecipient>,
    // Recorded as the entitlement's sourceEventId. An admin mint has no event behind it, and
    // NO_SOURCE_EVENT says so instead of pointing at something that does not exist.
    val justification: String? = null
)

const val NO_SOURCE_EVENT = "no-source-event"

private fun sourceEventOrMarker(justification: String?): String =
    justification?.takeIf { it.isNotBlank() } ?: NO_SOURCE_EVENT

@Serializable
data class MintResponse(
    val message: String,
    val holdingContractId: String?,
    val offerContractIds: List<String>
)

@Serializable
data class MintErrorResponse(val error: String)

@Serializable
data class DisclosedContractDto(
    val contractId: String,
    val templateId: String,
    val createdEventBlob: String  // base64
)

@Serializable
data class TopUpRequest(
    val consumer: String,
    val amount: Long,
    val paymentReference: String
)

@Serializable
data class TopUpResponse(
    val message: String,
    val entitlementId: String,
    val holdingOffers: List<String>,
    val consumer: String,
    val amount: Long,
    val note: String
)

fun Route.mintRoutes(
    ledgerClient: LedgerClient,
    operatorParty: String,
    auditorParty: String,
    tokenMintAuthorityService: TokenMintAuthorityService
) {
    authenticate("admin-auth") {
    route("/api/mint") {
        // Reward tokens bought with a payment made off the platform; the payment reference becomes
        // the entitlement's sourceEventId.
        post("/topup") {
            try {
                val request = call.receive<TopUpRequest>()

                if (request.amount <= 0) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        MintErrorResponse("Amount must be positive")
                    )
                }

                if (request.paymentReference.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        MintErrorResponse("Payment reference is required")
                    )
                }

                logger.info { "Processing top-up: ${request.amount} tokens for ${request.consumer} (payment: ${request.paymentReference})" }

                val authorityCid = try {
                    tokenMintAuthorityService.getAuthorityCid()
                } catch (e: Exception) {
                    logger.error(e) { "TokenMintAuthority not found" }
                    return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        MintErrorResponse("Token minting service unavailable: ${e.message}")
                    )
                }

                val now = Instant.now()
                val entitlementId = "TOPUP-${request.consumer}-${UUID.randomUUID()}"

                val entitlement = MintEntitlement(
                    entitlementId,
                    operatorParty,
                    auditorParty,
                    listOf(
                        Tuple2(request.consumer, request.amount)
                    ),
                    TokenType.REWARDTOKEN,
                    "consumer_topup",
                    request.paymentReference,
                    now
                ).create()

                val entitlementResult = ledgerClient.submitUpdate(
                    party = operatorParty,
                    update = entitlement
                )

                val entitlementCid = entitlementResult.getCreatedContractIds().firstOrNull()
                    ?: throw IllegalStateException("Failed to create MintEntitlement")

                logger.info { "Created MintEntitlement: $entitlementCid" }

                val claim = authorityCid.exerciseClaimMintEntitlement(
                    MintEntitlement.ContractId(entitlementCid)
                )

                val claimResult = ledgerClient.submitUpdate(
                    party = operatorParty,
                    update = claim
                )

                val offerCids = claimResult.getCreatedContractIds()

                logger.info { "Top-up successful: Created ${offerCids.size} HoldingOffers for ${request.consumer}" }

                call.respond(HttpStatusCode.Created, TopUpResponse(
                    message = "Reward tokens minted successfully",
                    entitlementId = entitlementId,
                    holdingOffers = offerCids,
                    consumer = request.consumer,
                    amount = request.amount,
                    note = "Consumer must accept HoldingOffer to use tokens"
                ))

            } catch (e: Exception) {
                logger.error(e) { "Error processing token top-up" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MintErrorResponse("Error processing top-up: ${e.message}")
                )
            }
        }
    }

    // Used by the participant stack: a voter is not a stakeholder of the mint authority, so their
    // Vote submission needs it attached as a disclosed contract.
        route("/api/system/mint-authority-disclosure") {
            get {
                val authorityCid = try {
                    tokenMintAuthorityService.getAuthorityCid()
                } catch (e: Exception) {
                    logger.error(e) { "TokenMintAuthority not found for disclosure request" }
                    return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        MintErrorResponse("TokenMintAuthority not available: ${e.message}")
                    )
                }

                val templateId = TokenMintAuthority.COMPANION.TEMPLATE_ID
                val disclosed = ledgerClient.getDisclosedContract(
                    party = operatorParty,
                    contractId = authorityCid.contractId,
                    templateId = com.daml.ledger.api.v1.ValueOuterClass.Identifier.newBuilder()
                        .setPackageId(templateId.packageId)
                        .setModuleName(templateId.moduleName)
                        .setEntityName(templateId.entityName)
                        .build()
                ) ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    MintErrorResponse("TokenMintAuthority contract not found on ledger")
                )

                call.respond(HttpStatusCode.OK, DisclosedContractDto(
                    contractId = disclosed.contractId,
                    templateId = "${disclosed.templateId.packageId}:${disclosed.templateId.moduleName}:${disclosed.templateId.entityName}",
                    createdEventBlob = Base64.getEncoder().encodeToString(disclosed.createdEventBlob.toByteArray())
                ))
            }
        }

    route("/api/admin/mint") {
        post {
            try {
                val request = call.receive<com.dsrs.operator.routes.MintRequest>()

                if (request.recipients.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, MintErrorResponse("Recipients list cannot be empty"))
                }

                if (request.recipients.any { it.amount <= 0 }) {
                    return@post call.respond(HttpStatusCode.BadRequest, MintErrorResponse("All amounts must be positive"))
                }

                val totalAmount = request.recipients.sumOf { it.amount }

                logger.info { "Minting $totalAmount tokens to ${request.recipients.size} recipients" }

                val authorityCid = try {
                    tokenMintAuthorityService.getAuthorityCid()
                } catch (e: Exception) {
                    logger.error(e) { "TokenMintAuthority not found" }
                    return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        MintErrorResponse("Token minting service unavailable: ${e.message}")
                    )
                }

                val damlRecipients = request.recipients.map { recipient ->
                    Tuple2(recipient.party, recipient.amount)
                }

                val now = Instant.now()
                val entitlementId = "MINT-${UUID.randomUUID()}"

                val entitlement = MintEntitlement(
                    entitlementId,
                    operatorParty,
                    auditorParty,
                    damlRecipients,
                    TokenType.REWARDTOKEN,
                    "admin_batch_mint",
                    sourceEventOrMarker(request.justification),
                    now
                ).create()

                val entitlementResult = ledgerClient.submitUpdate(party = operatorParty, update = entitlement)
                val entitlementCid = entitlementResult.getCreatedContractIds().firstOrNull()
                    ?: throw IllegalStateException("Failed to create MintEntitlement")

                logger.info { "Created MintEntitlement: $entitlementCid" }

                val claim = authorityCid.exerciseClaimMintEntitlement(
                    MintEntitlement.ContractId(entitlementCid)
                )
                val claimResult = ledgerClient.submitUpdate(party = operatorParty, update = claim)
                val offerCids = claimResult.getCreatedContractIds()

                logger.info { "Batch mint successful: created ${offerCids.size} HoldingOffers" }

                call.respond(HttpStatusCode.OK, MintResponse(
                    message = "Successfully minted $totalAmount tokens to ${request.recipients.size} recipients",
                    holdingContractId = null,
                    offerContractIds = offerCids
                ))

            } catch (e: Exception) {
                logger.error(e) { "Error processing batch mint" }
                call.respond(HttpStatusCode.InternalServerError, MintErrorResponse("Error processing batch mint: ${e.message}"))
            }
        }
    }
    }
}
