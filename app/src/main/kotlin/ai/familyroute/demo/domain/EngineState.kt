package ai.familyroute.demo.domain

import ai.familyroute.demo.model.FamilyMember

object Rules {
    const val BUDGET_MIN = 30
    const val TOKENS_PER_MEMBER = 2
    const val SUGGEST_TIMEOUT_MS = 10_000L
    const val PARK_CONFIRM_MS = 3_000L
    const val NOTICE_MS = 8_000L

    /** 경유지에 "도착했다"고 보는 거리 */
    const val AT_STOP_KM = 0.3
}

enum class Screen { MAIN, DECIDE, TIMELINE }

data class ActiveSuggest(val proposalId: String, val shownAtMs: Long)

data class Notice(val text: String, val member: FamilyMember?, val untilMs: Long)

/** TripEngine이 가진 전체 상태. 화면 상태는 여기서 계산해서 만든다. */
data class EngineState(
    val nowMs: Long,
    val vehicle: VehicleState,
    /** 기어 P + 속도 0이 된 시각 */
    val stillSinceMs: Long?,
    val proposals: List<ProposalRecord>,
    val tokens: Map<FamilyMember, Int>,
    val screen: Screen,
    val suggest: ActiveSuggest?,
    /** 정차 화면에서 고른 결과(제안 id → 결정). "이대로 출발"이나 출발 시 반영 */
    val decideChoices: Map<String, ProposalStatus>,
    val notice: Notice?,
    val devPanelOpen: Boolean,
    val nextSeq: Int,
) {
    val isDriving: Boolean get() = vehicle.gear == Gear.D || vehicle.speedKph > 0.0

    /** 기어 P이고 3초 이상 정지 */
    val isParked: Boolean
        get() = !isDriving && stillSinceMs != null && nowMs - stillSinceMs >= Rules.PARK_CONFIRM_MS

    val progressKm: Double get() = vehicle.routeProgressM / 1000.0

    val accepted: List<ProposalRecord>
        get() = proposals.filter { it.status == ProposalStatus.ACCEPTED }.sortedBy { it.place.routeKm }

    val undecided: List<ProposalRecord>
        get() = proposals.filter { it.status.undecided }.sortedBy { it.createdAtMs }

    /** 예산에서 쓴 시간 = 확정된 경유지의 우회 시간 합 */
    val budgetUsedMin: Int get() = accepted.sumOf { it.place.detourMin }

    val budgetLeftMin: Int get() = Rules.BUDGET_MIN - budgetUsedMin

    fun proposal(id: String): ProposalRecord? = proposals.firstOrNull { it.id == id }

    /** 지금 서 있는 경유지(정차 중이고 경유지 근처일 때) */
    val currentStop: ProposalRecord?
        get() = if (isDriving) null else accepted.firstOrNull { kotlin.math.abs(it.place.routeKm - progressKm) <= Rules.AT_STOP_KM }
}
