package com.dsrs.operator.routes

import com.dsrs.operator.db.repositories.ContributionRepository
import com.dsrs.operator.db.repositories.ProcessingLogRepository
import com.dsrs.operator.db.repositories.SyncStateRepository
import com.dsrs.operator.streaming.ContractSynchronizer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class SystemStateResponse(
    val syncState: SyncStateDto,
    val counts: ContractCountsDto,
    val processingStats: ProcessingStatsDto
)

@Serializable
data class SyncStateDto(
    val lastOffset: String?,
    val lastSyncTime: String?,
    val contractsSynced: Int,
    val syncStatus: String,
    val syncError: String?
)

@Serializable
data class ContractCountsDto(
    val contributions: Long
)

@Serializable
data class ProcessingStatsDto(
    val successfulProcessing: Long,
    val failedProcessing: Long
)

@Serializable
data class ResyncResponse(
    val message: String,
    val status: String
)

@Serializable
data class ProcessingLogDto(
    val id: Long?,
    val eventType: String,
    val contractId: String,
    val action: String,
    val status: String,
    val details: String?,
    val errorMessage: String?,
    val createdAt: String
)

fun Route.systemRoutes(
    syncStateRepo: SyncStateRepository,
    contributionRepo: ContributionRepository,
    processingLogRepo: ProcessingLogRepository,
    synchronizer: ContractSynchronizer
) {
    authenticate("admin-auth") {
        route("/api/system") {

            get("/state") {
                val syncState = syncStateRepo.getState()

                call.respond(SystemStateResponse(
                    syncState = SyncStateDto(
                        lastOffset = syncState.lastOffset,
                        lastSyncTime = syncState.lastSyncTime?.toString(),
                        contractsSynced = syncState.contractsSynced,
                        syncStatus = syncState.syncStatus,
                        syncError = syncState.syncError
                    ),
                    counts = ContractCountsDto(
                        contributions = contributionRepo.count()
                    ),
                    processingStats = ProcessingStatsDto(
                        successfulProcessing = processingLogRepo.countByStatus("success"),
                        failedProcessing = processingLogRepo.countByStatus("error")
                    )
                ))
            }

            post("/resync") {
                val currentState = syncStateRepo.getState()
                if (currentState.syncStatus == "syncing") {
                    call.respond(HttpStatusCode.Conflict, ResyncResponse(
                        message = "Resync already in progress",
                        status = "in_progress"
                    ))
                    return@post
                }

                launch {
                    try {
                        synchronizer.fullResync()
                    } catch (e: Exception) {
                        // the synchronizer logs it
                    }
                }

                call.respond(HttpStatusCode.Accepted, ResyncResponse(
                    message = "Resync started",
                    status = "started"
                ))
            }

            get("/log") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val logs = processingLogRepo.findRecent(limit)

                call.respond(logs.map { log ->
                    ProcessingLogDto(
                        id = log.id,
                        eventType = log.eventType,
                        contractId = log.contractId,
                        action = log.action,
                        status = log.status,
                        details = log.details,
                        errorMessage = log.errorMessage,
                        createdAt = log.createdAt.toString()
                    )
                })
            }

            get("/log/{contractId}") {
                val contractId = call.parameters["contractId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Contract ID required"))

                val logs = processingLogRepo.findByContractId(contractId)

                call.respond(logs.map { log ->
                    ProcessingLogDto(
                        id = log.id,
                        eventType = log.eventType,
                        contractId = log.contractId,
                        action = log.action,
                        status = log.status,
                        details = log.details,
                        errorMessage = log.errorMessage,
                        createdAt = log.createdAt.toString()
                    )
                })
            }
        }
    }
}
