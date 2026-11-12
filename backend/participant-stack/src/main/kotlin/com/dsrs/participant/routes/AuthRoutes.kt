package com.dsrs.participant.routes

// Registration and login work but are bypassed in the dev setup: testing/client.py signs JWTs
// with the dev secret for the parties allocated at bootstrap. JWT verification is always on.

import com.dsrs.common.auth.JwtService
import com.dsrs.common.auth.PasswordHasher
import com.dsrs.participant.db.UserRepository
import com.dsrs.participant.db.UserStatus
import com.dsrs.participant.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(
    userRepository: UserRepository,
    jwtService: JwtService
) {
    route("/api/auth") {

        post("/register") {
            val request = call.receive<RegisterRequest>()

            if (request.username.isBlank() || request.password.isBlank() || request.party.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username, password, and party are required"))
                return@post
            }

            if (request.password.length < 8) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be at least 8 characters"))
                return@post
            }

            if (!request.party.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid party name format. Use only alphanumeric characters, underscores, and hyphens."))
                return@post
            }

            val passwordHash = PasswordHasher.hash(request.password)
            val user = userRepository.createUser(request.username, passwordHash, request.party, UserStatus.PENDING)

            if (user == null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Username already exists"))
                return@post
            }

            call.respond(HttpStatusCode.Created, RegisterResponse(
                message = "Registration submitted. Awaiting admin approval.",
                status = "pending",
                party = request.party
            ))
        }

        post("/login") {
            val request = call.receive<LoginRequest>()

            val user = userRepository.findByUsername(request.username)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
                return@post
            }

            if (!PasswordHasher.verify(request.password, user.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
                return@post
            }

            when (user.status) {
                UserStatus.PENDING -> {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Account pending approval"))
                    return@post
                }
                UserStatus.APPROVED -> { /* do nothing - login is just a way to get a token */ }
            }

            val token = jwtService.generateToken(user.id, user.party)

            call.respond(HttpStatusCode.OK, AuthResponse(token, user.party))
        }

        authenticate("auth-jwt") {
            get("/party") {
                val principal = call.principal<JWTPrincipal>()!!
                val party = principal.payload.getClaim("party").asString()

                call.respond(HttpStatusCode.OK, PartyResponse(party))
            }
        }
    }
}

fun JWTPrincipal.getParty(): String =
    payload.getClaim("party").asString()
        ?: throw IllegalStateException("Party claim not found in token")
