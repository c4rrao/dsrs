package com.dsrs.auditor.services

import java.math.BigDecimal
import kotlin.math.floor
import kotlin.math.ln

/**
 * The reward arithmetic of Reward.daml and Contribution.daml, written again rather than shared:
 * an auditor that runs the operator's arithmetic cannot find a fault in it.
 */
object RewardMathService {

    // Constants.daml
    const val OPERATOR_FEE_PERCENTAGE = 0.15
    const val ESCROW_FEE_PERCENTAGE = 0.01
    const val AUDITOR_FEE_PERCENTAGE = 0.02

    const val STANDARD_TRUST_COEFFICIENT = 1.0
    const val BROKER_TRUST_COEFFICIENT = 3.0
    const val NOTARY_TRUST_COEFFICIENT = 5.0
    const val TAX_AUTHORITY_TRUST_COEFFICIENT = 8.0
    const val REDUNDANCY_BONUS_COEFFICIENT = 0.1

    data class FeeSplit(
        val tokenCost: Long,
        val operatorFee: Long,
        val escrowFee: Long,
        val auditorFee: Long,
        val distributablePool: Long
    )

    // DistributePerTransaction, first half: every fee is floored on its own
    fun splitFees(tokenCost: Long): FeeSplit {
        val operatorFee = floor(OPERATOR_FEE_PERCENTAGE * tokenCost).toLong()
        val escrowFee = floor(ESCROW_FEE_PERCENTAGE * tokenCost).toLong()
        val auditorFee = floor(AUDITOR_FEE_PERCENTAGE * tokenCost).toLong()
        return FeeSplit(
            tokenCost = tokenCost,
            operatorFee = operatorFee,
            escrowFee = escrowFee,
            auditorFee = auditorFee,
            distributablePool = tokenCost - operatorFee - escrowFee - auditorFee
        )
    }

    /**
     * DistributePerTransaction, second half: one group per matched transaction, weighted by its
     * trustScore.total from the auditor's own snapshot. Zero amounts are dropped, as in Daml.
     */
    fun expectedGroupAmounts(
        distributablePool: Long,
        trustScoresByTransaction: Map<String, BigDecimal>
    ): Map<String, Long> {
        val totalTrustScore = trustScoresByTransaction.values.sumOf { it.toDouble() }
        if (totalTrustScore == 0.0) return emptyMap()

        return trustScoresByTransaction.mapValues { (_, trustScore) ->
            floor((trustScore.toDouble() / totalTrustScore) * distributablePool).toLong()
        }.filterValues { it > 0 }
    }

    data class Contributor(
        val party: String,
        val role: String,
        val reputation: BigDecimal,
        val disputeCoefficient: BigDecimal
    )

    private data class RoleScore(
        val roleScore: Double,
        val sumIndividualScores: Double,
        val contributorScores: List<Pair<String, Double>>
    )

    private fun roleCoefficient(role: String): Double = when (role.uppercase()) {
        "BUYER", "SELLER" -> STANDARD_TRUST_COEFFICIENT
        "BROKER" -> BROKER_TRUST_COEFFICIENT
        "NOTARY" -> NOTARY_TRUST_COEFFICIENT
        "TAXAUTHORITY" -> TAX_AUTHORITY_TRUST_COEFFICIENT
        else -> STANDARD_TRUST_COEFFICIENT
    }

    private fun computeRoleScore(contributors: List<Contributor>): RoleScore {
        if (contributors.isEmpty()) return RoleScore(0.0, 0.0, emptyList())

        // all members declare the same role, so the first one's coefficient applies
        val coefficient = roleCoefficient(contributors.first().role)
        val scores = contributors.map { it.party to it.reputation.toDouble() * coefficient }
        val total = scores.sumOf { it.second }
        val count = scores.size.toDouble()

        val average = total / count
        val redundancyBonus = 1.0 + REDUNDANCY_BONUS_COEFFICIENT * log2(count)

        return RoleScore(
            roleScore = average * redundancyBonus,
            sumIndividualScores = total,
            contributorScores = scores
        )
    }

    // computeTrustScoreTree followed by computeReward
    fun expectedContributorRewards(
        contributors: List<Contributor>,
        groupAmount: Long
    ): Map<String, Long> {
        if (contributors.isEmpty() || groupAmount <= 0) return emptyMap()

        val byRole = ROLES.map { role ->
            computeRoleScore(contributors.filter { it.role.equals(role, ignoreCase = true) })
        }
        val totalTrustScore = byRole.sumOf { it.roleScore }
        if (totalTrustScore == 0.0) return contributors.associate { it.party to 0L }

        val coefficientByParty = contributors.associate { it.party to it.disputeCoefficient.toDouble() }

        // steps 1-3 of computeReward; the internal reputation factor breaks the total
        val raw = byRole.flatMap { rs ->
            val roleReward = floor(rs.roleScore / totalTrustScore * groupAmount).toLong()
            rs.contributorScores.map { (party, score) ->
                val reward =
                    if (rs.sumIndividualScores == 0.0) 0L
                    else floor(
                        score / rs.sumIndividualScores * roleReward * (coefficientByParty[party] ?: 1.0)
                    ).toLong()
                party to reward
            }
        }

        // step 4: renormalize to the group amount
        val rawTotal = raw.sumOf { it.second }
        if (rawTotal == 0L) return raw.toMap()

        return raw.associate { (party, reward) ->
            party to floor(reward.toDouble() / rawTotal * groupAmount).toLong()
        }
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    private val ROLES = listOf("Buyer", "Seller", "Broker", "Notary", "TaxAuthority")
}
