package com.dsrs.participant.services

import com.daml.ledger.api.v1.CommandsOuterClass.DisclosedContract
import com.daml.ledger.api.v1.ValueOuterClass.Identifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Serializable
private data class DisclosedContractResponse(
    val contractId: String,
    val templateId: String,
    val createdEventBlob: String  // base64
)

/**
 * Caches the disclosed TokenMintAuthority fetched from the operator stack. Contributors are not
 * stakeholders of it, so a Vote that mints needs it attached as an explicitly disclosed contract.
 */
class TokenMintAuthorityService(
    private val operatorBaseUrl: String,
    private val operatorAdminApiKey: String
) {
    private val httpClient = HttpClient.newHttpClient()

    @Volatile
    private var cached: DisclosedContract? = null

    fun getDisclosedAuthority(): DisclosedContract {
        cached?.let { return it }

        synchronized(this) {
            cached?.let { return it }

            logger.debug { "Fetching TokenMintAuthority disclosure from operator..." }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$operatorBaseUrl/api/system/mint-authority-disclosure"))
                .header("Authorization", "Bearer $operatorAdminApiKey")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) {
                "Failed to fetch TokenMintAuthority disclosure: HTTP ${response.statusCode()} ${response.body()}"
            }

            val dto = Json.decodeFromString<DisclosedContractResponse>(response.body())
            val (packageId, moduleName, entityName) = dto.templateId.split(":", limit = 3)

            val disclosed = DisclosedContract.newBuilder()
                .setContractId(dto.contractId)
                .setTemplateId(
                    Identifier.newBuilder()
                        .setPackageId(packageId)
                        .setModuleName(moduleName)
                        .setEntityName(entityName)
                        .build()
                )
                .setCreatedEventBlob(
                    com.google.protobuf.ByteString.copyFrom(Base64.getDecoder().decode(dto.createdEventBlob))
                )
                .build()

            cached = disclosed
            logger.info { "Cached TokenMintAuthority disclosure for contract ${dto.contractId}" }
            return disclosed
        }
    }

    // the authority is created once at bootstrap and never archived, so this is a safety valve
    fun invalidateCache() {
        logger.info { "Invalidating TokenMintAuthority disclosure cache" }
        cached = null
    }
}
