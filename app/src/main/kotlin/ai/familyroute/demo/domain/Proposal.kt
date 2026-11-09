package ai.familyroute.demo.domain

import ai.familyroute.demo.model.FamilyMember

/**
 * 제안 상태. PROPOSED → VOTING → DRIVER_PENDING → (결정).
 * 다음 정차 때(NEXT_STOP)는 아직 결정 전이라 정차 화면에서 다시 고른다.
 */
/** 누가 확정했나 */
enum class DecidedBy { DRIVER, FAMILY_MAJORITY, URGENT, PLAN }

enum class ProposalStatus {
    PROPOSED, VOTING, DRIVER_PENDING,
    ACCEPTED, NEXT_STOP, RETURN_TRIP, AFTER_ARRIVAL, SKIPPED;

    /** 운전자가 아직 고르지 않은 상태 */
    val undecided: Boolean get() = this in setOf(PROPOSED, VOTING, DRIVER_PENDING, NEXT_STOP)
}

data class ProposalRecord(
    val id: String,
    val member: FamilyMember,
    val place: Place,
    val message: String?,
    val status: ProposalStatus,
    val votes: Map<FamilyMember, VoteChoice>,
    val createdAtMs: Long,
    /** 급한 요청이면 종류. 제안권을 쓰지 않고 바로 확정된다. */
    val urgent: UrgentType? = null,
    /** 주행 중 오버레이로 이미 한 번 보여줬는지 */
    val presented: Boolean = false,
    /** 장소에 가까워져 한 번 더 물어봤는지(한 번만) */
    val reminded: Boolean = false,
    val decidedBy: DecidedBy? = null,
) {
    /** 제안자와 운전자를 뺀 투표 대상 */
    val voters: List<FamilyMember> get() = FamilyMember.passengers - member

    /** 찬성 수(제안자 포함) */
    val yesCount: Int get() = 1 + votes.values.count { it == VoteChoice.YES }

    /** 가족(운전자 포함) 과반수 찬성 */
    val hasMajority: Boolean get() = yesCount * 2 > FamilyMember.entries.size
}
