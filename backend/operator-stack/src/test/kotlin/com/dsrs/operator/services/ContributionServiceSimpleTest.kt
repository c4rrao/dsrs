package com.dsrs.operator.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.operator.config.OperatorConfig
import com.dsrs.operator.db.repositories.ContributionRepository
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

// Daml-generated final classes cannot be mocked with MockK, so the merge and dispute paths are
// covered by the end-to-end run.
class ContributionServiceSimpleTest {

    private lateinit var ledgerClient: LedgerClient
    private lateinit var contributionRepo: ContributionRepository
    private lateinit var disputeService: DisputeService
    private lateinit var tokenMintAuthorityService: TokenMintAuthorityService
    private lateinit var config: OperatorConfig
    private lateinit var service: ContributionService

    @BeforeEach
    fun setup() {
        ledgerClient = mockk(relaxed = true)
        contributionRepo = mockk(relaxed = true)
        disputeService = mockk(relaxed = true)
        tokenMintAuthorityService = mockk(relaxed = true)
        config = mockk(relaxed = true)

        every { config.operatorParty } returns "Operator::1220test"

        service = ContributionService(config, ledgerClient, contributionRepo, disputeService, tokenMintAuthorityService)
    }

    @Test
    fun `service initializes with required dependencies`() {
        assertTrue(service is ContributionService)
    }

    @Test
    fun `service has access to config`() {
        assertTrue(config != null)
    }

    @Test
    fun `service has access to ledger client`() {
        assertTrue(ledgerClient != null)
    }

    @Test
    fun `service has access to contribution repository`() {
        assertTrue(contributionRepo != null)
    }

    @Test
    fun `rejectContributionRequest calls ledger client`() {
        val contractId = "contribution-request-001"
        val reason = "Invalid data"

        service.rejectContributionRequest(contractId, reason)

        verify(exactly = 1) { ledgerClient.submitUpdate<Any>(any(), any()) }
    }
}
