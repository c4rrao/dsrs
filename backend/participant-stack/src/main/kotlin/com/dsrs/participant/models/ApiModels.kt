package com.dsrs.participant.models

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val party: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val party: String
)

@Serializable
data class PartyResponse(
    val party: String
)

@Serializable
data class RegisterResponse(
    val message: String,
    val status: String,
    val party: String
)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val party: String,
    val status: String,
    val createdAt: String
)

@Serializable
data class AddressDto(
    val postalCode: String? = null,
    val street: String? = null,
    val city: String? = null,
    val country: String? = null,
    val additionalInfo: String? = null
)

@Serializable
data class ContributionDataDto(
    val propertyAddress: AddressDto? = null,
    val salePrice: Double? = null,
    val saleDate: String? = null,
    val propertyType: String? = null,
    val squareMeters: Double? = null,
    val numberOfRooms: Int? = null,
    val yearBuilt: Int? = null,
    val energyRating: String? = null,
    val tags: List<String>? = null,
    val additionalInfo: String? = null
)

@Serializable
data class CreateContributionRequest(
    val transactionId: String,
    val contributionData: ContributionDataDto,
    val roleInTransaction: String
)

@Serializable
data class ContributionRequestDto(
    val contractId: String,
    val requestId: String,
    val contributor: String,
    val transactionId: String,
    val contributionData: ContributionDataDto,
    val requestDate: String
)

@Serializable
data class ContributionDto(
    val contractId: String,
    val contributionId: String,
    val transactionId: String,
    val contributionData: ContributionDataDto,
    val trustScore: Double,
    val contributors: List<String>,
    val originalSubmissionDate: String
)

@Serializable
data class RangeDto<T>(
    val from: T? = null,
    val to: T? = null
)

@Serializable
data class QueryParamsDto(
    val level: Int = 1,
    val trustScore: RangeDto<Double>? = null,
    val propertyAddress: List<AddressDto>? = null,
    val salePrice: RangeDto<Double>? = null,
    val saleDate: RangeDto<String>? = null,
    val propertyTypes: List<String>? = null,
    val squareMeters: RangeDto<Double>? = null,
    val numberOfRooms: List<Int>? = null,
    val yearBuilt: RangeDto<Int>? = null,
    val energyRating: List<String>? = null,
    val tags: List<String>? = null
)

@Serializable
data class CreateQueryRequest(
    val params: QueryParamsDto
)

@Serializable
data class QueryRequestDto(
    val contractId: String,
    val requestId: String,
    val consumer: String,
    val params: QueryParamsDto,
    val requestDate: String
)

@Serializable
data class QueryProposalDto(
    val contractId: String,
    val proposalId: String,
    val consumer: String,
    val escrow: String?,
    val params: QueryParamsDto,
    val tokenCost: Long,
    val proposalDate: String,
    val escrowAccepted: Boolean
)

@Serializable
data class AcceptProposalRequest(
    val holdingContractId: String
)

@Serializable
data class FundedQueryDto(
    val contractId: String,
    val queryId: String,
    val proposalId: String,
    val consumer: String,
    val escrow: String,
    val tokenCost: Long,
    val fundedDate: String
)

@Serializable
data class QueryResultDto(
    val contractId: String,
    val resultId: String,
    // the auditor's pricing check is keyed by proposalId and its reward check by queryId
    val queryId: String,
    val proposalId: String,
    val consumer: String,
    val params: QueryParamsDto,
    val tokenCost: Long,
    val completionDate: String,
    // pinned at pricing, so it predates completionDate; the auditor replays the query at this instant
    val executionTimestamp: String,
    val results: List<InsightDto>
)

@Serializable
data class InsightDto(
    val level: Int,
    val transactionCount: Int,
    val avgSalePrice: Double? = null,
    val avgTrustScore: Double? = null,
    val avgYearBuilt: Double? = null,
    val avgSquareMeters: Double? = null,
    val avgNumberOfRooms: Double? = null,
    val medianEnergyRating: String? = null,
    val details: DetailedMetricsDto? = null
)

@Serializable
data class DetailedMetricsDto(
    val priceRange: RangeDto<Double>? = null,
    val priceVariance: Double? = null,
    val medianPrice: Double? = null,
    val avgPricePerSqm: Double? = null,
    val pricePerSqmRange: RangeDto<Double>? = null,
    val yearBuiltRange: RangeDto<Int>? = null,
    val sqmRange: RangeDto<Double>? = null,
    val sqmVariance: Double? = null,
    val roomsRange: RangeDto<Int>? = null,
    val propertyTypeDistribution: List<DistributionEntryDto>? = null,
    val energyRatingDistribution: List<DistributionEntryDto>? = null,
    val cityDistribution: List<DistributionEntryDto>? = null,
    val saleDateRange: RangeDto<String>? = null,
    val topTags: List<DistributionEntryDto>? = null,
    val avgContributorsPerTx: Double? = null,
    val avgDisputesPerTx: Double? = null,
    val disputeRate: Double? = null
)

@Serializable
data class DistributionEntryDto(
    val key: String,
    val count: Int
)

@Serializable
data class HoldingDto(
    val contractId: String,
    val owner: String,
    val tokenType: String,
    val amount: Long,
    val locked: Boolean,
    val lockedFor: String? = null
)

@Serializable
data class TransferRequest(
    val holdingContractId: String,
    val recipient: String,
    val amount: Long? = null,  // null transfers everything
    val reference: String? = null
)

@Serializable
data class RewardGroupDto(
    val groupId: String,
    val queryId: String,
    val transactionId: String,
    val contributors: List<String>,
    val distributionDate: String,
    // false once DistributeInGroup has paid and archived the group
    val pending: Boolean
)

@Serializable
data class HoldingOfferDto(
    val contractId: String,
    val from: String,
    val to: String,
    val tokenType: String,
    val amount: Long,
    // a reward payout carries its groupId here, which is what a reward audit is keyed on
    val reference: String? = null
)

@Serializable
data class MergeRequest(
    val holdingContractId: String,
    val otherContractIds: List<String>
)

@Serializable
data class DisputeDto(
    val contractId: String,
    val disputeId: String,
    val transactionId: String,
    val reason: String,
    val contributors: List<String>,
    val votesCount: Int,
    val contributorsCount: Int
)

@Serializable
data class VoteRequest(
    val voteForDataIndex: Int
)

@Serializable
data class VoteResponse(
    val message: String,
    val contractId: String,
    val resolved: Boolean
)

@Serializable
data class ProposeDisputeDataRequest(
    val contributionData: ContributionDataDto
)

@Serializable
data class ProposeDisputeDataResponse(
    val message: String,
    val contractId: String,
    val resolved: Boolean,
    val deduplicated: Boolean,
    val optionCount: Int?      // null once resolved
)

@Serializable
data class DisputeDetailDto(
    val contractId: String,
    val disputeId: String,
    val transactionId: String,
    val reason: String,
    val contributors: List<String>,
    val votesCount: Int,
    val contributorsCount: Int,
    val allContributionOptions: List<ContributionDataDto>,
    val myVote: Int?,
    // The dispute resolves on votes, but winners are decided by initial positions, so both are
    // shown.
    val votesByParty: List<DisputeVoteDto> = emptyList()
)

@Serializable
data class DisputeVoteDto(
    val party: String,
    val votedFor: Int?,
    // only ProposeDisputeData moves it
    val initialPosition: Int?
)

@Serializable
data class AuditRequestParamsDto(
    val queryId: String? = null,
    val proposalId: String? = null,
    val rewardGroupId: String? = null,
    val transactionId: String? = null,
    val fromTime: String? = null,
    val toTime: String? = null
)

@Serializable
data class CreateAuditRequestDto(
    val requestType: String,
    val params: AuditRequestParamsDto
)

@Serializable
data class AuditRequestDto(
    val contractId: String,
    val requestId: String,
    val requester: String,
    val requestType: String,
    val params: AuditRequestParamsDto,
    val createdAt: String
)

@Serializable
data class AuditProposalDto(
    val contractId: String,
    val requestId: String,
    val requester: String,
    val requestType: String,
    val params: AuditRequestParamsDto,
    val estimatedCost: Long,
    val description: String,
    val proposedAt: String
)

@Serializable
data class FundAuditRequestDto(
    val holdingContractId: String
)

@Serializable
data class AuditResultDto(
    val contractId: String,
    val requestId: String,
    val requester: String,
    val verificationType: String,
    val expected: Int,
    val actual: Int,
    val matches: Boolean,
    val statisticsChecked: Boolean = false,
    // null when the statistics were not checked
    val statisticsAgree: Boolean? = null,
    val details: String,
    val tokensBurned: Long,
    val completedAt: String
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class SuccessResponse(
    val message: String,
    val contractId: String? = null
)
