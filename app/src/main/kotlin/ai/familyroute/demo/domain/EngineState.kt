package ai.familyroute.demo.domain

import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.route.ActiveRoute
import ai.familyroute.demo.route.RouteStop
import kotlin.math.abs

object Rules {
    const val BUDGET_MIN = 30
    const val SUGGEST_TIMEOUT_MS = 10_000L
    const val PARK_CONFIRM_MS = 3_000L
    const val NOTICE_MS = 8_000L

    /** 경유지에 "서 있다"고 보는 거리 */
    const val AT_STOP_M = 30.0

    /** 미룬 제안 장소에 이만큼(기본 경로 km) 가까워지면 한 번 더 묻는다 */
    const val REMIND_FROM_KM = 6.0
    const val REMIND_UNTIL_KM = 2.0
}

enum class Screen { MAIN, DECIDE, TIMELINE }

/** [reminder]면 "곧 근처를 지나요" 다시 묻기 */
data class ActiveSuggest(val proposalId: String, val shownAtMs: Long, val reminder: Boolean = false)

data class Notice(val text: String, val member: FamilyMember?, val untilMs: Long)

/** TripEngine이 가진 전체 상태. 화면 상태는 여기서 계산해서 만든다. */
data class EngineState(
    val scenario: Scenario,
    /** 지금 따라가는 경로(확정 경유지의 우회 포함) */
    val route: ActiveRoute,
    /** 경로에 넣어 달라고 한 경유지(겹쳐서 빠진 것 포함) */
    val routeRequest: Set<String>,
    /** 실제 시계(ms). 10초 자동 넘김·3초 정차 판정에 쓴다 */
    val nowMs: Long,
    val vehicle: VehicleState,
    /** 기어 P + 속도 0이 된 실제 시각 */
    val stillSinceMs: Long?,
    /** 기어 P가 된 시뮬레이션 시각(남은 체류 시간 계산) */
    val parkedSinceSimMs: Long?,
    val proposals: List<ProposalRecord>,
    val screen: Screen,
    val suggest: ActiveSuggest?,
    val decideChoices: Map<String, ProposalStatus>,
    val notice: Notice?,
    val devPanelOpen: Boolean,
    val nextSeq: Int,
    /** 경유지 도착 시각(시뮬레이션 ms) */
    val visits: Map<String, Long>,
    val lastProposalId: String?,
) {
    val isDriving: Boolean get() = vehicle.gear == Gear.D || vehicle.speedKph > 0.0

    /** 기어 P이고 3초 이상 정지 */
    val isParked: Boolean
        get() = !isDriving && stillSinceMs != null && nowMs - stillSinceMs >= Rules.PARK_CONFIRM_MS

    val progressM: Double get() = vehicle.routeProgressM

    /** 지금 위치의 기본 경로 인덱스(우회 중이면 이탈 지점) */
    val baseIndexNow: Int get() = route.points[route.indexAt(progressM)].baseIndex

    fun stopOf(placeId: String): RouteStop? = route.stops.firstOrNull { it.placeId == placeId }

    val accepted: List<ProposalRecord>
        get() = proposals.filter { it.status == ProposalStatus.ACCEPTED }
            .sortedBy { stopOf(it.place.id)?.distM ?: (it.place.routeKm * 1000) }

    val undecided: List<ProposalRecord>
        get() = proposals.filter { it.status.undecided }.sortedBy { it.createdAtMs }

    /** 예산에서 쓴 시간 = 확정된 경유지의 우회 시간 합 */
    val budgetUsedMin: Int get() = accepted.sumOf { it.place.detourMin }

    val budgetLeftMin: Int get() = Rules.BUDGET_MIN - budgetUsedMin

    fun proposal(id: String): ProposalRecord? = proposals.firstOrNull { it.id == id }

    fun isPassed(p: Place): Boolean {
        val stop = stopOf(p.id) ?: return p.detour.exitIndex < baseIndexNow
        return progressM > stop.distM + Rules.AT_STOP_M
    }

    /** 이탈 지점을 이미 지나 우회로 들어갈 수 없는 장소 */
    fun isUnreachable(p: Place): Boolean = stopOf(p.id) == null && p.detour.exitIndex <= baseIndexNow

    /** 지금 서 있는 경유지 */
    val currentStop: ProposalRecord?
        get() = if (isDriving) {
            null
        } else {
            accepted.firstOrNull { r -> stopOf(r.place.id)?.let { abs(it.distM - progressM) <= Rules.AT_STOP_M } == true }
        }

    val arrived: Boolean get() = progressM >= route.length - 1.0

    /** 장소까지 남은 기본 경로 거리(km). 지나쳤으면 음수 */
    fun kmTo(p: Place): Double = p.routeKm - route.baseKmAt(progressM)
}
