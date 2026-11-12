package com.dsrs.auditor.services

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// If this drifts from Reward.daml, reward audits report mismatches against an honest operator.
class RewardMathServiceTest {

    @Test
    fun `fees are 15 percent operator, 1 percent escrow, 2 percent auditor`() {
        val split = RewardMathService.splitFees(1_000_000)

        assertEquals(150_000, split.operatorFee)
        assertEquals(10_000, split.escrowFee)
        assertEquals(20_000, split.auditorFee)
        assertEquals(820_000, split.distributablePool)
    }

    @Test
    fun `fee split never distributes more than was paid`() {
        listOf(1L, 7L, 999L, 1_234_567L, 10_000_000L).forEach { cost ->
            val split = RewardMathService.splitFees(cost)
            val accounted = split.operatorFee + split.escrowFee + split.auditorFee + split.distributablePool
            assertEquals(cost, accounted, "fees plus pool must account for the whole payment of $cost")
            assertTrue(split.distributablePool <= cost)
        }
    }

    @Test
    fun `pool is split across transactions in proportion to trust score`() {
        val amounts = RewardMathService.expectedGroupAmounts(
            distributablePool = 900,
            trustScoresByTransaction = mapOf(
                "TX-A" to BigDecimal("1.0"),
                "TX-B" to BigDecimal("2.0")
            )
        )

        assertEquals(300, amounts["TX-A"])
        assertEquals(600, amounts["TX-B"])
    }

    @Test
    fun `a transaction whose share rounds to zero gets no group, matching DAML`() {
        val amounts = RewardMathService.expectedGroupAmounts(
            distributablePool = 10,
            trustScoresByTransaction = mapOf(
                "TX-BIG" to BigDecimal("1000.0"),
                "TX-DUST" to BigDecimal("0.0001")
            )
        )

        // 1000/1000.0001 of 10 floors to 9: every share is floored on its own, and the leftover
        // stays in the escrow's holding
        assertEquals(9, amounts["TX-BIG"])
        assertTrue("TX-DUST" !in amounts, "DAML skips groups whose amount floors to 0")
    }

    @Test
    fun `zero total trust score distributes nothing rather than dividing by zero`() {
        val amounts = RewardMathService.expectedGroupAmounts(
            distributablePool = 1000,
            trustScoresByTransaction = mapOf("TX-A" to BigDecimal.ZERO)
        )
        assertTrue(amounts.isEmpty())
    }

    @Test
    fun `a sole contributor receives the whole group amount`() {
        val rewards = RewardMathService.expectedContributorRewards(
            contributors = listOf(contributor("Alice", "Buyer")),
            groupAmount = 100_000
        )

        assertEquals(100_000, rewards["Alice"])
    }

    @Test
    fun `roles are weighted, so a notary out-earns a buyer of equal reputation`() {
        val rewards = RewardMathService.expectedContributorRewards(
            contributors = listOf(
                contributor("Alice", "Buyer"),
                contributor("Nora", "Notary")
            ),
            groupAmount = 120_000
        )

        val alice = rewards.getValue("Alice")
        val nora = rewards.getValue("Nora")

        assertTrue(nora > alice, "notary ($nora) should out-earn buyer ($alice)")
        assertEquals(5.0, nora.toDouble() / alice.toDouble(), 0.05)
    }

    @Test
    fun `a dispute loser earns less than a winner in the same role`() {
        val rewards = RewardMathService.expectedContributorRewards(
            contributors = listOf(
                contributor("Winner", "Buyer", disputeCoefficient = "0.625"),
                contributor("Loser", "Buyer", disputeCoefficient = "0.375")
            ),
            groupAmount = 100_000
        )

        assertTrue(
            rewards.getValue("Winner") > rewards.getValue("Loser"),
            "dispute coefficients must survive normalisation"
        )
        // one winner of two from 0.5 ends at 0.625 against 0.375, a 5:3 split
        assertEquals(5.0 / 3.0, rewards.getValue("Winner").toDouble() / rewards.getValue("Loser").toDouble(), 0.05)
    }

    @Test
    fun `payouts never exceed the group amount they are split from`() {
        val rewards = RewardMathService.expectedContributorRewards(
            contributors = listOf(
                contributor("Alice", "Buyer"),
                contributor("Bob", "Seller"),
                contributor("Carla", "Broker"),
                contributor("Nora", "Notary"),
                contributor("Tom", "TaxAuthority")
            ),
            groupAmount = 1_000_000
        )

        val total = rewards.values.sum()
        assertTrue(total <= 1_000_000, "distributed $total from a 1,000,000 group")
        assertTrue(total >= 1_000_000 - rewards.size, "distributed $total, expected near 1,000,000")
    }

    @Test
    fun `an empty group pays nobody`() {
        assertTrue(RewardMathService.expectedContributorRewards(emptyList(), 1000).isEmpty())
        assertTrue(RewardMathService.expectedContributorRewards(listOf(contributor("A", "Buyer")), 0).isEmpty())
    }

    private fun contributor(
        party: String,
        role: String,
        reputation: String = "10.0",
        disputeCoefficient: String = "0.5"
    ) = RewardMathService.Contributor(
        party = party,
        role = role,
        reputation = BigDecimal(reputation),
        disputeCoefficient = BigDecimal(disputeCoefficient)
    )

    /**
     * The ledger fixes the weights but not which records are in the distribution. Padding it with
     * a record the query never matched keeps the total right and dilutes every genuine
     * contributor. The auditor derives the matched set from its own replay, so it computes the
     * undiluted amount and the per-party comparison shows the shortfall.
     */
    @Test
    fun `padding the distribution with an unmatched transaction underpays every genuine one`() {
        val pool = 900L
        val genuine = mapOf(
            "TX-A" to BigDecimal("1.0"),
            "TX-B" to BigDecimal("2.0")
        )
        val padded = genuine + ("TX-PADDED" to BigDecimal("3.0"))

        val owed = RewardMathService.expectedGroupAmounts(pool, genuine)
        val paid = RewardMathService.expectedGroupAmounts(pool, padded)

        assertEquals(pool, owed.values.sum())
        assertEquals(pool, paid.values.sum())

        assertTrue(paid.getValue("TX-A") < owed.getValue("TX-A"))
        assertTrue(paid.getValue("TX-B") < owed.getValue("TX-B"))
        assertEquals(300, owed["TX-A"])
        assertEquals(150, paid["TX-A"])

        // the padded record also shows up in the inclusion check
        assertEquals(450, paid["TX-PADDED"])
    }
}
