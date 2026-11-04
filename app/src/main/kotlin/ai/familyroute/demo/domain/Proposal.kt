package ai.familyroute.demo.domain

import ai.familyroute.demo.model.FamilyMember

/**
 * 제안 상태. PROPOSED → VOTING → DRIVER_PENDING → (결정).
 * 다음 정차 때(NEXT_STOP)는 아직 결정 전이라 정차 화면에서 다시 고른다.
 */
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
) {
    /** 제안자와 운전자를 뺀 투표 대상 */
    val voters: List<FamilyMember> get() = FamilyMember.passengers - member
}
