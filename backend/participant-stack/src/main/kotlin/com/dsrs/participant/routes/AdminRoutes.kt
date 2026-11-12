package com.dsrs.participant.routes

// Unused in the dev setup, where registration is bypassed (see AuthRoutes.kt), but it is the
// onboarding path a real deployment would use.

import com.dsrs.common.ledger.AdminClient
import com.dsrs.participant.db.User
import com.dsrs.participant.db.UserRepository
import com.dsrs.participant.db.UserStatus
import com.dsrs.participant.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

fun Route.adminRoutes(
    userRepository: UserRepository,
    adminClient: AdminClient
) {
    authenticate("admin-auth") {
        route("/api/admin") {

            get("/users/pending") {
                val pendingUsers = userRepository.findByStatus(UserStatus.PENDING)
                val response = pendingUsers.map { it.toDto() }
                call.respond(HttpStatusCode.OK, response)
            }

            get("/users") {
                val allUsers = userRepository.findAll()
                call.respond(HttpStatusCode.OK, allUsers.map { it.toDto() })
            }

            post("/users/{id}/approve") {
                val userId = call.parameters["id"]
                if (userId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    return@post
                }

                val user = userRepository.findById(userId)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@post
                }

                if (user.status != UserStatus.PENDING) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("User is not in pending status"))
                    return@post
                }

                // user.party is only a hint at this point; store the qualified id Canton returns
                val allocatedParty = try {
                    adminClient.allocateOrFindParty(user.party, user.username)
                } catch (e: Exception) {
                    logger.error(e) { "Could not allocate party ${user.party} for ${user.username}" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to create party: ${e.message}")
                    )
                    return@post
                }

                val updated = userRepository.approveWithParty(userId, allocatedParty)
                if (!updated) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update user status"))
                    return@post
                }

                call.respond(HttpStatusCode.OK, SuccessResponse("User approved, party $allocatedParty created"))
            }

            post("/users/{id}/reject") {
                val userId = call.parameters["id"]
                if (userId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    return@post
                }

                val user = userRepository.findById(userId)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@post
                }

                val deleted = userRepository.deleteUser(userId)
                if (!deleted) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to delete user"))
                    return@post
                }

                call.respond(HttpStatusCode.OK, SuccessResponse("User rejected and removed"))
            }
        }
    }
}

private fun User.toDto(): UserDto = UserDto(
    id = id,
    username = username,
    party = party,
    status = status.name.lowercase(),
    createdAt = DateTimeFormatter.ISO_INSTANT.format(createdAt)
)
