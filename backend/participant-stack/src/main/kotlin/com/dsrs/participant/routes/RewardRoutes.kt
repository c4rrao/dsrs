package com.dsrs.participant.routes

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.reward.RewardGroup
import com.dsrs.participant.models.RewardGroupDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The reward groups that paid this contributor, so they can find the groupId a reward audit needs.
 * No party filter is needed: contributors only observe the groups that pay them.
 */
fun Route.rewardRoutes(ledgerClient: LedgerClient) {
    authenticate("auth-jwt") {
        route("/api/rewards") {

            // Read from history, not the ACS: DistributeInGroup archives a group as it pays, so
            // the ACS would only hold the unpaid ones.
            get("/groups") {
                val party = call.principal<JWTPrincipal>()!!.getParty()
                val transactionId = call.request.queryParameters["transactionId"]

                logger.debug { "Listing reward groups for party $party" }

                val groups = ledgerClient.queryContractHistoryTyped(party, RewardGroup.COMPANION)
                    .filter { transactionId == null || it.contract.data.transactionId.unpack == transactionId }
                    .map { historical ->
                        val data = historical.contract.data
                        RewardGroupDto(
                            groupId = data.groupId,
                            queryId = data.queryId,
                            transactionId = data.transactionId.unpack,
                            contributors = data.contributorsData.keys.toList(),
                            distributionDate = data.distributionDate.toString(),
                            pending = historical.stillActive
                        )
                    }
                    .sortedBy { it.distributionDate }

                call.respond(HttpStatusCode.OK, groups)
            }
        }
    }
}
