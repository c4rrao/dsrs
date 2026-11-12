package com.dsrs.common.types

import java.math.BigDecimal

// Display copies of Constants.daml. The ledger enforces its own values.
object Constants {
    val OPERATOR_FEE_PERCENTAGE: BigDecimal = BigDecimal("0.15")
    val ESCROW_FEE_PERCENTAGE: BigDecimal = BigDecimal("0.01")
    val AUDITOR_FEE_PERCENTAGE: BigDecimal = BigDecimal("0.02")

    val STANDARD_TRUST_SCORE_COEFFICIENT: BigDecimal = BigDecimal("1.0")
    val BROKER_TRUST_SCORE_COEFFICIENT: BigDecimal = BigDecimal("3.0")
    val NOTARY_TRUST_SCORE_COEFFICIENT: BigDecimal = BigDecimal("5.0")
    val TAX_AUTHORITY_TRUST_SCORE_COEFFICIENT: BigDecimal = BigDecimal("8.0")

    val REDUNDANCY_BONUS_COEFFICIENT: BigDecimal = BigDecimal("0.1")

    /**
     * Mirrors `_minTransactionsForPrivacy_`, which the ledger enforces in ProposeCost and
     * CompleteQuery. This copy lets the operator refuse before quoting, or refund, instead of
     * hitting a raw Daml assertion. Lowering it alone would make every such query fail on-ledger.
     */
    const val MIN_TRANSACTIONS_FOR_PRIVACY: Int = 3

    // 1 EUR = 1,000,000 tokens
    const val TOKENS_PER_EUR: Long = 1_000_000L

    fun tokenToEur(amount: Long): BigDecimal =
        BigDecimal(amount).divide(BigDecimal(TOKENS_PER_EUR))

    fun eurToToken(amount: BigDecimal): Long =
        amount.multiply(BigDecimal(TOKENS_PER_EUR)).toLong()
}
