package com.dsrs.common.ktor

import com.dsrs.common.models.HealthResponse
import com.dsrs.common.models.ReadyResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.installHealthRoutes(
    serviceName: String,
    readinessChecker: (() -> Pair<Boolean, Map<String, Boolean>?>)? = null
) {
    get("/health") {
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "healthy",
                service = serviceName
            )
        )
    }

    if (readinessChecker != null) {
        get("/ready") {
            val (ready, details) = readinessChecker()
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadyResponse(
                    ready = ready,
                    service = serviceName,
                    details = details
                )
            )
        }
    }
}
