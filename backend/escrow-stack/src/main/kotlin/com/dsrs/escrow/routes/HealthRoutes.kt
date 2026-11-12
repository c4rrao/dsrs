package com.dsrs.escrow.routes

import com.dsrs.escrow.streaming.ProposalSynchronizer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String
)

@Serializable
data class ReadyResponse(
    val ready: Boolean,
    val syncStatus: String,
    val lastSyncTime: String?,
    val proposalsProcessed: Int,
    val proposalsAccepted: Int,
    val errorMessage: String?
)

fun Route.healthRoutes(synchronizer: ProposalSynchronizer) {
    get("/health") {
        call.respond(HealthResponse(
            status = "healthy",
            service = "escrow-stack"
        ))
    }

    get("/ready") {
        val status = synchronizer.getStatus()
        val ready = synchronizer.isReady

        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            ReadyResponse(
                ready = ready,
                syncStatus = status.status,
                lastSyncTime = status.lastSyncTime?.toString(),
                proposalsProcessed = status.proposalsProcessed,
                proposalsAccepted = status.proposalsAccepted,
                errorMessage = status.errorMessage
            )
        )
    }
}
