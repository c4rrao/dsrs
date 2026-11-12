package com.dsrs.participant

import com.dsrs.common.auth.JwtConfig
import com.dsrs.common.auth.JwtService
import com.dsrs.common.db.DatabaseConfig
import com.dsrs.common.db.DatabaseManager
import com.dsrs.common.ktor.installCommonPlugins
import com.dsrs.common.ledger.AdminClient
import com.dsrs.common.ledger.AdminConfig
import com.dsrs.common.ledger.LedgerClient
import com.dsrs.common.ledger.LedgerConfig
import com.dsrs.participant.db.UserRepository
import com.dsrs.participant.db.Users
import com.dsrs.participant.routes.*
import com.dsrs.participant.services.TokenMintAuthorityService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    logger.info { "Starting Participant Stack on port $port" }

    embeddedServer(Netty, port = port, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val operatorParty = System.getenv("OPERATOR_PARTY")
        ?: error("OPERATOR_PARTY environment variable must be set")
    val auditorParty = System.getenv("AUDITOR_PARTY")
        ?: error("AUDITOR_PARTY environment variable must be set")
    val adminApiKey = System.getenv("ADMIN_API_KEY")
        ?: error("ADMIN_API_KEY environment variable must be set")
    val operatorBaseUrl = System.getenv("OPERATOR_BASE_URL")
        ?: error("OPERATOR_BASE_URL environment variable must be set")
    val operatorAdminApiKey = System.getenv("OPERATOR_ADMIN_API_KEY")
        ?: error("OPERATOR_ADMIN_API_KEY environment variable must be set")

    val jwtConfig = JwtConfig()
    val jwtService = JwtService(jwtConfig)
    val dbConfig = DatabaseConfig()
    val ledgerConfig = LedgerConfig()
    val adminConfig = AdminConfig()

    val dbManager = DatabaseManager(dbConfig)
    dbManager.connect()
    dbManager.createTables(Users)

    val userRepository = UserRepository()

    // one client for every party; the acting party comes from each request's JWT
    val ledgerClient = LedgerClient(ledgerConfig)

    val adminClient = AdminClient(adminConfig)

    // Fetches the disclosed TokenMintAuthority from the operator, which a voter's submission needs
    val tokenMintAuthorityService = TokenMintAuthorityService(operatorBaseUrl, operatorAdminApiKey)

    installCommonPlugins()

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtConfig.realm
            verifier(
                com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtConfig.secret))
                    .withIssuer(jwtConfig.issuer)
                    .withAudience(jwtConfig.audience)
                    .build()
            )
            validate { credential ->
                if (credential.payload.subject != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
            }
        }

        bearer("admin-auth") {
            authenticate { tokenCredential ->
                if (tokenCredential.token == adminApiKey) {
                    UserIdPrincipal("admin")
                } else {
                    null
                }
            }
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "healthy", "service" to "participant-stack"))
        }

        authRoutes(userRepository, jwtService)

        adminRoutes(userRepository, adminClient)

        contributionRoutes(ledgerClient, operatorParty, auditorParty)

        queryRoutes(ledgerClient, operatorParty, auditorParty)

        holdingRoutes(ledgerClient)

        disputeRoutes(ledgerClient, tokenMintAuthorityService)

        rewardRoutes(ledgerClient)

        auditRoutes(ledgerClient, auditorParty)
    }
}
