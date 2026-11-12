package com.dsrs.auditor.db.repositories

import com.dsrs.auditor.db.ContributionSnapshotsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDateTime

data class ContributionSnapshotEntity(
    val id: Long = 0,
    val contractId: String,
    val contributionId: String,
    val transactionId: String,
    val state: String = "active",
    val contributorCount: Int,
    val isActive: Boolean,
    val totalTrustScore: BigDecimal,
    val contributorsDataJson: String,
    val contributionDataJson: String,
    val trustScoreJson: String,
    val disputeDatesJson: String,
    val version: Int,
    val validFrom: LocalDateTime,
    val validTo: LocalDateTime? = null,
    val archivedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

class ContributionSnapshotRepository {

    fun insert(snapshot: ContributionSnapshotEntity): Long = transaction {
        ContributionSnapshotsTable.update({
            (ContributionSnapshotsTable.contractId eq snapshot.contractId) and
            ContributionSnapshotsTable.validTo.isNull()
        }) {
            it[validTo] = snapshot.validFrom
        }

        ContributionSnapshotsTable.insertAndGetId {
            it[contractId] = snapshot.contractId
            it[contributionId] = snapshot.contributionId
            it[transactionId] = snapshot.transactionId
            it[state] = snapshot.state
            it[contributorCount] = snapshot.contributorCount
            it[isActive] = snapshot.isActive
            it[totalTrustScore] = snapshot.totalTrustScore
            it[contributorsDataJson] = snapshot.contributorsDataJson
            it[contributionDataJson] = snapshot.contributionDataJson
            it[trustScoreJson] = snapshot.trustScoreJson
            it[disputeDatesJson] = snapshot.disputeDatesJson
            it[version] = snapshot.version
            it[validFrom] = snapshot.validFrom
            it[validTo] = snapshot.validTo
            it[archivedAt] = snapshot.archivedAt
            it[createdAt] = snapshot.createdAt
        }.value
    }

    fun findCurrentByContractId(contractId: String): ContributionSnapshotEntity? = transaction {
        ContributionSnapshotsTable.select {
            (ContributionSnapshotsTable.contractId eq contractId) and
            ContributionSnapshotsTable.validTo.isNull()
        }
            .map { it.toEntity() }
            .firstOrNull()
    }

    fun findCurrentByTransactionId(transactionId: String): ContributionSnapshotEntity? = transaction {
        ContributionSnapshotsTable.select {
            (ContributionSnapshotsTable.transactionId eq transactionId) and
            ContributionSnapshotsTable.validTo.isNull()
        }
            .map { it.toEntity() }
            .firstOrNull()
    }

    fun findAtTime(transactionId: String, atTime: LocalDateTime): ContributionSnapshotEntity? = transaction {
        ContributionSnapshotsTable.select {
            (ContributionSnapshotsTable.transactionId eq transactionId) and
            (ContributionSnapshotsTable.validFrom lessEq atTime) and
            (ContributionSnapshotsTable.validTo.isNull() or
             (ContributionSnapshotsTable.validTo greater atTime))
        }
            .map { it.toEntity() }
            .firstOrNull()
    }

    fun findAllVersions(transactionId: String): List<ContributionSnapshotEntity> = transaction {
        ContributionSnapshotsTable.select { ContributionSnapshotsTable.transactionId eq transactionId }
            .orderBy(ContributionSnapshotsTable.version, SortOrder.ASC)
            .map { it.toEntity() }
    }

    fun findActiveInTimeRange(
        fromTime: LocalDateTime,
        toTime: LocalDateTime
    ): List<ContributionSnapshotEntity> = transaction {
        ContributionSnapshotsTable.select {
            (ContributionSnapshotsTable.isActive eq true) and
            (ContributionSnapshotsTable.validFrom lessEq toTime) and
            (ContributionSnapshotsTable.validTo.isNull() or
             (ContributionSnapshotsTable.validTo greater fromTime))
        }
            .map { it.toEntity() }
    }

    fun archive(contractId: String, archivedAt: LocalDateTime) = transaction {
        ContributionSnapshotsTable.update({
            (ContributionSnapshotsTable.contractId eq contractId) and
            ContributionSnapshotsTable.validTo.isNull()
        }) {
            it[validTo] = archivedAt
        }
    }

    fun updateState(contractId: String, newState: String) = transaction {
        ContributionSnapshotsTable.update({
            (ContributionSnapshotsTable.contractId eq contractId) and
            ContributionSnapshotsTable.validTo.isNull()
        }) {
            it[state] = newState
        }
    }

    fun archiveContribution(contractId: String, archivedTime: LocalDateTime) = transaction {
        ContributionSnapshotsTable.update({
            (ContributionSnapshotsTable.contractId eq contractId) and
            ContributionSnapshotsTable.validTo.isNull()
        }) {
            it[state] = "archived"
            it[archivedAt] = archivedTime
            it[validTo] = archivedTime
        }
    }

    /**
     * The contributions that were live and corroborated at snapshotTime. The replay takes both the
     * matched set and the corpus from this one call, like the operator's queryContributionsAtTime,
     * and both must apply the same corroboration filter or honest pricing would fail its audit.
     */
    fun findActiveAtTime(snapshotTime: LocalDateTime): List<ContributionSnapshotEntity> = transaction {
        ContributionSnapshotsTable.select {
            (ContributionSnapshotsTable.validFrom lessEq snapshotTime) and
            // isActive is written once per version, so unlike state it is safe on closed versions
            (ContributionSnapshotsTable.isActive eq true) and
            (
                // a closed version: the validity window decides; its state was overwritten later
                ((ContributionSnapshotsTable.validTo.isNotNull()) and
                 (ContributionSnapshotsTable.validTo greater snapshotTime))
                or
                // the current version: state is still accurate
                ((ContributionSnapshotsTable.validTo.isNull()) and
                 (ContributionSnapshotsTable.state eq "active"))
            )
        }
        .map { it.toEntity() }
    }

    private fun ResultRow.toEntity() = ContributionSnapshotEntity(
        id = this[ContributionSnapshotsTable.id].value,
        contractId = this[ContributionSnapshotsTable.contractId],
        contributionId = this[ContributionSnapshotsTable.contributionId],
        transactionId = this[ContributionSnapshotsTable.transactionId],
        state = this[ContributionSnapshotsTable.state],
        contributorCount = this[ContributionSnapshotsTable.contributorCount],
        isActive = this[ContributionSnapshotsTable.isActive],
        totalTrustScore = this[ContributionSnapshotsTable.totalTrustScore],
        contributorsDataJson = this[ContributionSnapshotsTable.contributorsDataJson],
        contributionDataJson = this[ContributionSnapshotsTable.contributionDataJson],
        trustScoreJson = this[ContributionSnapshotsTable.trustScoreJson],
        disputeDatesJson = this[ContributionSnapshotsTable.disputeDatesJson],
        version = this[ContributionSnapshotsTable.version],
        validFrom = this[ContributionSnapshotsTable.validFrom],
        validTo = this[ContributionSnapshotsTable.validTo],
        archivedAt = this[ContributionSnapshotsTable.archivedAt],
        createdAt = this[ContributionSnapshotsTable.createdAt]
    )
}
