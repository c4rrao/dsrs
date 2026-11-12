package com.dsrs.auditor.routes

import com.dsrs.auditor.db.repositories.AuditLogRepository
import com.dsrs.auditor.db.repositories.SyncStateRepository
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
    val status: String,
    val syncStatus: String,
    val lastOffset: String?,
    val contractCount: Long,
    val auditStatistics: AuditStatisticsResponse?
)

@Serializable
data class AuditStatisticsResponse(
    val totalRequests: Long,
    val failedVerifications: Long,
    val requestsByType: Map<String, Long>
)

fun Route.healthRoutes(
    syncStateRepo: SyncStateRepository,
    auditLogRepo: AuditLogRepository
) {
    route("/health") {
        get {
            call.respond(HttpStatusCode.OK, HealthResponse(
                status = "healthy",
                service = "auditor-stack"
            ))
        }

        get("/ready") {
            val state = syncStateRepo.getState()
            val isReady = state.status in listOf("completed", "streaming")

            val response = ReadyResponse(
                status = if (isReady) "ready" else "not_ready",
                syncStatus = state.status,
                lastOffset = state.lastOffset,
                contractCount = state.contractCount,
                auditStatistics = null
            )

            if (isReady) {
                call.respond(HttpStatusCode.OK, response)
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, response)
            }
        }

        get("/detailed") {
            val state = syncStateRepo.getState()
            val stats = auditLogRepo.getStatistics()

            val response = ReadyResponse(
                status = "healthy",
                syncStatus = state.status,
                lastOffset = state.lastOffset,
                contractCount = state.contractCount,
                auditStatistics = AuditStatisticsResponse(
                    totalRequests = stats.totalRequests,
                    failedVerifications = stats.failedVerifications,
                    requestsByType = stats.requestsByType
                )
            )

            call.respond(HttpStatusCode.OK, response)
        }
    }
}
