package com.dsrs.operator.services

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.contribution.Dispute
import com.dsrs.operator.config.OperatorConfig

class DisputeService(
    private val config: OperatorConfig,
    private val ledgerClient: LedgerClient
) {
    fun getAllDisputes(): List<Pair<String, Dispute>> {
        val contracts = ledgerClient.queryContractsTyped(
            party = config.operatorParty,
            companion = Dispute.COMPANION
        )
        return contracts.map { it.id.contractId to it.data }
    }

    fun getDispute(contractId: String): Dispute? {
        val contract = ledgerClient.getContractTyped(
            party = config.operatorParty,
            contractId = contractId,
            companion = Dispute.COMPANION
        )
        return contract?.data
    }

    fun findDisputeByTransactionId(transactionId: String): Pair<String, Dispute>? {
        val disputes = getAllDisputes()
        return disputes.firstOrNull { (_, dispute) ->
            dispute.transactionId.unpack == transactionId
        }
    }

    fun getPendingDisputes(): List<Pair<String, Dispute>> {
        val contracts = ledgerClient.queryContractsTyped(
            party = config.operatorParty,
            companion = Dispute.COMPANION
        )
        return contracts.filter { !isUnanimous(it.data) }
            .map { it.id.contractId to it.data }
    }

    fun isUnanimous(dispute: Dispute): Boolean {
        val votes = dispute.votes.mapValues { it.value.toInt() }
        val contributorCount = dispute.contributorsData.size
        if (votes.isEmpty()) return false
        if (votes.size < contributorCount) return false
        return votes.values.distinct().size == 1
    }
}
