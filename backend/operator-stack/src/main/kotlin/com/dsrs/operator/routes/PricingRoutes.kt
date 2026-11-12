package com.dsrs.operator.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.pricing.PricingConfig
import com.dsrs.operator.config.OperatorConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.exp

private val logger = KotlinLogging.logger {}

@Serializable
data class PricingConfigDto(
    val contractId: String,
    val basePrice: Long,
    val level1Multiplier: String,
    val level2Multiplier: String,
    val coverageWeight: String,
    val coverageSaturation: String,
    val narrownessWeight: String,
    val trustWeight: String
)

@Serializable
data class UpdatePricingRequest(
    val basePrice: Long,
    val level1Multiplier: String,
    val level2Multiplier: String,
    val coverageWeight: String = "2.0",
    val coverageSaturation: String = "5.0",
    val narrownessWeight: String = "0.5",
    val trustWeight: String = "4.0",
    val unit: String = "TOKENS"  // "TOKENS", "EUR" or "€"
)

@Serializable
data class SimulatePriceRequest(
    val level: Int = 1,
    val resultCount: Int = 100,
    // coverage is resultCount / corpusCount
    val corpusCount: Int = 100,
    // 0.0 unfiltered to 1.0 a single value, per filtered dimension
    val selectivities: List<Double> = emptyList(),
    // -1.0 worst, 0.0 corpus average, 1.0 best
    val trustQuality: Double = 0.0
)

@Serializable
data class SimulatePriceResponse(
    val calculatedPrice: Long,
    val breakdown: PriceBreakdown
)

@Serializable
data class PriceBreakdown(
    val basePrice: Long,
    val transactionFactor: String,
    val levelMultiplier: String,
    val filterFactors: String
)

@Serializable
data class InitPricingConfigRequest(
    val basePrice: Long = 10_000_000,
    val level1Multiplier: String = "1.0",
    val level2Multiplier: String = "3.0",
    val coverageWeight: String = "2.0",
    val coverageSaturation: String = "5.0",
    val narrownessWeight: String = "0.5",
    val trustWeight: String = "4.0",
    val unit: String = "TOKENS"  // "TOKENS", "EUR" or "€"
)

private const val EUR_TO_TOKEN_RATE = 1_000_000

fun Route.pricingRoutes(
    ledgerClient: LedgerClient,
    config: OperatorConfig
) {
    fun convertToTokens(value: Long, unit: String): Long {
        return when (unit.uppercase()) {
            "EUR", "€", "EUROS" -> value * EUR_TO_TOKEN_RATE.toLong()
            "TOKENS", "TOKEN" -> value
            else -> value
        }
    }

    suspend fun getPricingConfigFromLedger(): Pair<String, PricingConfig>? {
        val contracts = ledgerClient.queryContractsTyped(
            party = config.operatorParty,
            companion = PricingConfig.COMPANION
        )
        return contracts.firstOrNull()?.let { it.id.contractId to it.data }
    }

    authenticate("admin-auth") {
        route("/api/pricing") {

            post("/config/init") {
                val request = call.receive<InitPricingConfigRequest>()

                try {
                    val existing = getPricingConfigFromLedger()
                    if (existing != null) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("PricingConfig already exists with contract ID: ${existing.first}")
                        )
                    }

                    val basePriceInTokens = convertToTokens(request.basePrice, request.unit)

                    logger.info {
                        "Initializing PricingConfig with unit=${request.unit}: " +
                        "basePrice=${request.basePrice} ${request.unit} -> $basePriceInTokens tokens, " +
                        "coverageWeight=${request.coverageWeight}, narrownessWeight=${request.narrownessWeight}"
                    }

                    val pricingConfig = PricingConfig(
                        config.operatorParty,
                        config.auditorParty,
                        config.operatorParty,  // the operator doubles as the public party
                        basePriceInTokens,
                        BigDecimal(request.level1Multiplier),
                        BigDecimal(request.level2Multiplier),
                        BigDecimal(request.coverageWeight),
                        BigDecimal(request.coverageSaturation),
                        BigDecimal(request.narrownessWeight),
                        BigDecimal(request.trustWeight),
                        Instant.now()
                    )

                    val update = pricingConfig.create()
                    val result = ledgerClient.submitUpdate(
                        party = config.operatorParty,
                        update = update
                    )

                    val contractId = result.getCreatedContractIds().firstOrNull()
                        ?: throw IllegalStateException("No contract ID returned from ledger")

                    logger.info { "PricingConfig created on ledger with ID: $contractId" }

                    call.respond(SuccessResponse(
                        message = "PricingConfig initialized on ledger and will be synced to database",
                        contractId = contractId
                    ))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to initialize PricingConfig" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Unknown error"))
                }
            }

            get("/config") {
                val pricingPair = getPricingConfigFromLedger()
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("No PricingConfig found. Initialize one via POST /api/pricing/config/init")
                    )

                val (contractId, pricingConfig) = pricingPair

                call.respond(PricingConfigDto(
                    contractId = contractId,
                    basePrice = pricingConfig.basePrice,
                    level1Multiplier = pricingConfig.level1Multiplier.toString(),
                    level2Multiplier = pricingConfig.level2Multiplier.toString(),
                    coverageWeight = pricingConfig.coverageWeight.toString(),
                    coverageSaturation = pricingConfig.coverageSaturation.toString(),
                    narrownessWeight = pricingConfig.narrownessWeight.toString(),
                    trustWeight = pricingConfig.trustWeight.toString()
                ))
            }

            put("/config") {
                val request = call.receive<UpdatePricingRequest>()

                try {
                    val existing = getPricingConfigFromLedger()
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("No PricingConfig found to update")
                        )

                    val (contractId, _) = existing

                    val basePriceInTokens = convertToTokens(request.basePrice, request.unit)

                    logger.info {
                        "Updating PricingConfig: basePrice=${request.basePrice} ${request.unit} -> $basePriceInTokens tokens"
                    }

                    val cid = PricingConfig.ContractId(contractId)
                    val update = cid.exerciseUpdateConfig(
                        basePriceInTokens,
                        BigDecimal(request.level1Multiplier),
                        BigDecimal(request.level2Multiplier),
                        BigDecimal(request.coverageWeight),
                        BigDecimal(request.coverageSaturation),
                        BigDecimal(request.narrownessWeight),
                        BigDecimal(request.trustWeight)
                    )

                    val result = ledgerClient.submitUpdate(config.operatorParty, update)

                    val newContractId = result.getCreatedContractIds().firstOrNull()
                        ?: throw IllegalStateException("No contract ID returned")

                    call.respond(SuccessResponse(
                        message = "PricingConfig updated",
                        contractId = newContractId
                    ))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to update PricingConfig" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Unknown error"))
                }
            }

            // What-if pricing that never touches the ledger
            post("/simulate") {
                val request = call.receive<SimulatePriceRequest>()

                val pricingPair = getPricingConfigFromLedger()
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("No PricingConfig found. Initialize one first.")
                    )

                val (_, pricingConfig) = pricingPair

                // same terms as CalculatePrice in Pricing.daml
                val n = request.resultCount.coerceAtLeast(0)
                val bigN = request.corpusCount.coerceAtLeast(n).coerceAtLeast(1)

                val coverage = n.toDouble() / bigN
                val completeness = 1.0 - exp(-1.0 * pricingConfig.coverageSaturation.toDouble() * coverage)
                val txFactor = (ln(1.0 + n.toDouble()) / ln(2.0)) *
                    (1.0 + pricingConfig.coverageWeight.toDouble() * completeness)

                val levelMult = if (request.level == 1)
                    pricingConfig.level1Multiplier.toDouble()
                else
                    pricingConfig.level2Multiplier.toDouble()

                val k = pricingConfig.narrownessWeight.toDouble()
                val precision = request.selectivities.fold(1.0) { acc, s0 ->
                    val sc = s0.coerceIn(0.0, 1.0); acc * (1.0 + k * sc * sc)
                }
                val quality = pricingConfig.trustWeight.toDouble().pow(request.trustQuality.coerceIn(-1.0, 1.0))

                val price = pricingConfig.basePrice.toDouble() * txFactor * levelMult * precision * quality

                call.respond(SimulatePriceResponse(
                    calculatedPrice = price.toLong().coerceAtLeast(1),
                    breakdown = PriceBreakdown(
                        basePrice = pricingConfig.basePrice,
                        transactionFactor = String.format("%.2f", txFactor),
                        levelMultiplier = levelMult.toString(),
                        filterFactors = "coverage %.3f, precision x%.3f, quality x%.3f (simulation only - the real price is computed on-ledger)"
                            .format(coverage, precision, quality)
                    )
                ))
            }
        }
    }
}
