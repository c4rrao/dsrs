package com.dsrs.operator.routes

import com.dsrs.operator.db.repositories.SyncStateRepository
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
    val contractsSynced: Int
)

fun Route.healthRoutes(syncStateRepo: SyncStateRepository) {
    get("/health") {
        call.respond(HealthResponse(
            status = "healthy",
            service = "operator-stack"
        ))
    }

    get("/ready") {
        val state = syncStateRepo.getState()
        val ready = state.syncStatus == "completed" || state.syncStatus == "idle"

        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            ReadyResponse(
                ready = ready,
                syncStatus = state.syncStatus,
                lastSyncTime = state.lastSyncTime?.toString(),
                contractsSynced = state.contractsSynced
            )
        )
    }
}
