package com.dsrs.escrow.streaming

import com.dsrs.common.ledger.LedgerClient
import com.dsrs.daml.query.QueryProposal
import com.dsrs.escrow.config.EscrowConfig
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// The escrow accepts every proposal it is a candidate for and that no other escrow has signed yet.
class ProposalHandler(
    private val config: EscrowConfig,
    private val ledgerClient: LedgerClient
) {
    fun shouldAccept(proposal: QueryProposal): Boolean {
        if (!config.autoAcceptProposals) {
            logger.debug { "Auto-accept disabled, skipping" }
            return false
        }

        val possibleEscrows = proposal.possibleEscrows
        val signerEscrow = proposal.signerEscrow

        val isCandidate = config.escrowParty in possibleEscrows

        val notYetSigned = signerEscrow.isEmpty

        logger.debug {
            "Proposal check: isCandidate=$isCandidate, notYetSigned=$notYetSigned, " +
            "possibleEscrows=$possibleEscrows, signerEscrow=$signerEscrow"
        }

        return isCandidate && notYetSigned
    }

    fun acceptProposal(contractId: String, proposalId: String): Boolean {
        return try {
            logger.info { "Accepting escrow role for proposal: $proposalId (contract: $contractId)" }

            val cid = QueryProposal.ContractId(contractId)
            val update = cid.exerciseAcceptEscrowRole(config.escrowParty)
            ledgerClient.submitUpdate(config.escrowParty, update)

            logger.info { "Successfully accepted escrow role for proposal: $proposalId" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to accept escrow role for proposal: $proposalId" }
            false
        }
    }

    fun processProposal(contractId: String, proposal: QueryProposal): Boolean {
        val proposalId = proposal.proposalId

        if (!shouldAccept(proposal)) {
            logger.debug { "Skipping proposal $proposalId - not eligible or already signed" }
            return false
        }

        return acceptProposal(contractId, proposalId)
    }
}
