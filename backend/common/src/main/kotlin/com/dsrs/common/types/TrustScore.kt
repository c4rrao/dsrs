package com.dsrs.common.types

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class TrustScore(val value: String)

@Serializable
data class RoleTrustScore(
    val roleScore: String,
    val sumIndividualScores: String,
    val individualScores: List<Pair<String, String>>  // (party, score)
)

@Serializable
data class TrustScoreTree(
    val total: String,
    val buyer: RoleTrustScore,
    val seller: RoleTrustScore,
    val broker: RoleTrustScore,
    val notary: RoleTrustScore,
    val taxAuthority: RoleTrustScore
)
