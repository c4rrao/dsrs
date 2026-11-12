package com.dsrs.operator.db.repositories

import com.dsrs.operator.db.QueryProposals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * @param executionTimestamp when the contribution set was read, replayed into CompleteQuery so the
 *   QueryResult records that instant and not the moment the consumer paid
 * @param matchedTransactionIds what reward distribution pays. Transaction ids rather than contract
 *   ids, since a contribution's contract id changes when a dispute resolves.
 */
data class QueryProposalSnapshot(
    val proposalId: String,
    val insightsJson: String,
    val executionTimestamp: Instant,
    val resultCount: Int,
    val matchedTransactionIds: List<String>,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

class QueryProposalRepository(private val json: Json = Json { ignoreUnknownKeys = true }) {

    // upsert, so re-pricing the same proposal overwrites
    fun save(snapshot: QueryProposalSnapshot) = transaction {
        val executionLocal = LocalDateTime.ofInstant(snapshot.executionTimestamp, ZoneId.systemDefault())

        val updated = QueryProposals.update({ QueryProposals.proposalId eq snapshot.proposalId }) {
            it[insightsJson] = snapshot.insightsJson
            it[executionTimestamp] = executionLocal
            it[resultCount] = snapshot.resultCount
            it[matchedTransactionIdsJson] = json.encodeToString(snapshot.matchedTransactionIds)
        }

        if (updated == 0) {
            QueryProposals.insert {
                it[proposalId] = snapshot.proposalId
                it[insightsJson] = snapshot.insightsJson
                it[executionTimestamp] = executionLocal
                it[resultCount] = snapshot.resultCount
                it[matchedTransactionIdsJson] = json.encodeToString(snapshot.matchedTransactionIds)
                it[createdAt] = snapshot.createdAt
            }
        }
    }

    fun findByProposalId(proposalId: String): QueryProposalSnapshot? = transaction {
        QueryProposals.select { QueryProposals.proposalId eq proposalId }
            .map { it.toSnapshot() }
            .firstOrNull()
    }

    // Only for failed queries. After CompleteQuery the snapshot is kept, because distribution
    // still needs the matched set.
    fun delete(proposalId: String) = transaction {
        QueryProposals.deleteWhere { QueryProposals.proposalId eq proposalId }
    }

    private fun ResultRow.toSnapshot() = QueryProposalSnapshot(
        proposalId = this[QueryProposals.proposalId],
        insightsJson = this[QueryProposals.insightsJson],
        executionTimestamp = this[QueryProposals.executionTimestamp]
            .atZone(ZoneId.systemDefault())
            .toInstant(),
        resultCount = this[QueryProposals.resultCount],
        matchedTransactionIds = runCatching {
            json.decodeFromString<List<String>>(this[QueryProposals.matchedTransactionIdsJson])
        }.getOrDefault(emptyList()),
        createdAt = this[QueryProposals.createdAt]
    )
}
