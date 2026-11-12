package com.dsrs.operator.services

import com.dsrs.common.ledger.ExerciseResult
import com.dsrs.common.ledger.LedgerClient
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ContributionRepository
import com.dsrs.operator.db.repositories.QueryProposalRepository
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

// processQueryRequest and processFundedQuery take Daml-generated final classes that MockK cannot
// mock; they are covered by the end-to-end run instead.
class QueryServiceTest {

    private lateinit var config: OperatorConfig
    private lateinit var ledgerClient: LedgerClient
    private lateinit var insightService: InsightService
    private lateinit var tokenMintAuthorityService: TokenMintAuthorityService
    private lateinit var queryProposalRepo: QueryProposalRepository
    private lateinit var contributionRepo: ContributionRepository
    private lateinit var service: QueryService

    @BeforeEach
    fun setup() {
        config = mockk(relaxed = true)
        ledgerClient = mockk(relaxed = true)
        insightService = mockk(relaxed = true)
        tokenMintAuthorityService = mockk(relaxed = true)
        queryProposalRepo = mockk(relaxed = true)
        contributionRepo = mockk(relaxed = true)

        every { config.operatorParty } returns "Operator::1220test"
        every { config.escrowParty } returns "Escrow::1220test"

        service = QueryService(
            config, ledgerClient, insightService, tokenMintAuthorityService,
            queryProposalRepo, contributionRepo, LedgerLock(java.time.Duration.ofMinutes(5))
        )
    }

    @Test
    fun `service initializes with required dependencies`() {
        assertTrue(service is QueryService)
    }

    @Test
    fun `rejectQueryRequest submits rejection to ledger`() {
        val contractId = "query-contract-003"
        val reason = "Insufficient data quality"

        val exerciseResult = mockk<ExerciseResult>(relaxed = true)
        every { ledgerClient.submitUpdate<Any>(any(), any()) } returns exerciseResult

        service.rejectQueryRequest(contractId, reason)

        verify(exactly = 1) { ledgerClient.submitUpdate<Any>(any(), any()) }
    }

    @Test
    fun `failFundedQuery submits failure to ledger`() {
        val contractId = "funded-query-002"
        val reason = "Processing error"

        val exerciseResult = mockk<ExerciseResult>(relaxed = true)
        every { ledgerClient.submitUpdate<Any>(any(), any()) } returns exerciseResult

        service.failFundedQuery(contractId, reason)

        verify(exactly = 1) { ledgerClient.submitUpdate<Any>(any(), any()) }
    }

    @Test
    fun `rejectQueryRequest handles different rejection reasons`() {
        val contractId = "query-contract-004"
        val reason = "Invalid parameters"

        val exerciseResult = mockk<ExerciseResult>(relaxed = true)
        every { ledgerClient.submitUpdate<Any>(any(), any()) } returns exerciseResult

        service.rejectQueryRequest(contractId, reason)

        verify(exactly = 1) { ledgerClient.submitUpdate<Any>(any(), any()) }
    }

    @Test
    fun `failFundedQuery handles different failure reasons`() {
        val contractId = "funded-query-005"
        val reason = "Timeout processing query"

        val exerciseResult = mockk<ExerciseResult>(relaxed = true)
        every { ledgerClient.submitUpdate<Any>(any(), any()) } returns exerciseResult

        service.failFundedQuery(contractId, reason)

        verify(exactly = 1) { ledgerClient.submitUpdate<Any>(any(), any()) }
    }
}
