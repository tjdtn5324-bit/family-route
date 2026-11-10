package ai.familyroute.demo.model

import ai.familyroute.demo.domain.Gear
import ai.familyroute.demo.domain.Screen
import ai.familyroute.demo.map.MapUiState
import java.time.LocalTime

/** 화면이 읽는 상태. TripEngine 상태에서 [UiMapper]가 만든다. */
data class TripUiState(
    val screen: Screen,
    val driving: Boolean,
    val parked: Boolean,
    val turn: TurnGuide?,
    val connectedMembers: Int,
    val roadName: String,
    val speedKph: Int,
    val destinationName: String,
    val eta: LocalTime,
    val remainingMinutes: Int,
    val remainingMeters: Int,
    val budget: BudgetUi,
    val stops: List<StopUi>,
    val members: List<MemberStatusUi>,
    val pendingProposals: Int,
    val map: MapUiState,
    val notice: NoticeUi?,
    val suggest: SuggestUi?,
    val decide: DecideUi?,
    val timeline: TimelineUi,
    val dev: DevUi?,
)

enum class TurnDirection { LEFT, RIGHT, STRAIGHT }

data class TurnGuide(val distanceM: Int, val direction: TurnDirection, val text: String)

/** 가족 여유시간 예산. 우회 시간만 차감한다. */
data class BudgetUi(val totalMinutes: Int, val usage: List<BudgetUsage>) {
    val usedMinutes: Int get() = usage.sumOf { it.minutes }
    val remainingMinutes: Int get() = (totalMinutes - usedMinutes).coerceAtLeast(0)
}

data class BudgetUsage(val member: FamilyMember, val minutes: Int)

enum class StopStatus { CONFIRMED, PENDING, AFTER_ARRIVAL, RETURN_TRIP }

data class StopUi(val member: FamilyMember, val placeName: String, val detail: String, val status: StopStatus)

/** 하단 가족 바의 한 줄 상태. [emphasized]면 가족 색으로 표시. */
data class MemberStatusUi(val member: FamilyMember, val status: String, val emphasized: Boolean)

data class NoticeUi(val member: FamilyMember?, val text: String)

data class SuggestUi(
    val proposalId: String,
    val member: FamilyMember,
    val title: String,
    val votesLine: String,
    val placeName: String,
    val placeInfo: String,
    val tiles: List<TileUi>,
    val message: String?,
    val primaryLabel: String,
    val secondsLeft: Int,
    val fractionLeft: Float,
    val map: MapUiState,
)

data class TileUi(val value: String, val label: String, val warn: Boolean = false)

data class DecideUi(
    val stopName: String,
    val cards: List<DecideCardUi>,
    val hiddenCount: Int,
    val summary: DecideSummaryUi,
)

data class DecideCardUi(
    val id: String,
    val member: FamilyMember,
    val placeName: String,
    val info: String,
    val detourText: String,
    val arrivalText: String,
    val votes: List<VoteChipUi>,
    val banner: BannerUi?,
    val actionLabel: String,
    val selected: Boolean,
)

data class VoteChipUi(val member: FamilyMember, val label: String, val agreed: Boolean)

data class BannerUi(val text: String, val warn: Boolean)

data class DecideSummaryUi(
    val newEta: LocalTime,
    val originalEta: LocalTime,
    val deltaMinutes: Int,
    val budget: BudgetUi,
    val budgetText: String,
    val order: List<String>,
    /** 가족 폰 연결 안내(연결된 폰이 없으면 연결 방법) */
    val phoneNote: String,
)

data class TimelineUi(
    val subtitle: String,
    val nodes: List<TimelineNodeUi>,
    /** "지금" 위치. 0 = 출발 노드, 1 = 도착 노드 */
    val nowFraction: Float,
    val timeRows: List<MemberTimeRowUi>,
    val budgetText: String,
    val tokenRows: List<TokenRowUi>,
)

enum class TimelineNodeKind { START, STOP, END }

data class TimelineNodeUi(
    val kind: TimelineNodeKind,
    val member: FamilyMember?,
    val title: String,
    val subtitle: String,
)

data class MemberTimeRowUi(val member: FamilyMember, val minutes: Int, val fraction: Float)

/** 가족 제안 기록 한 줄(제안 횟수 제한은 없다) */
data class TokenRowUi(val member: FamilyMember, val proposed: Int, val added: Int, val text: String)

/** 개발자 패널. 데모 녹화 때는 열지 않는다. */
data class DevUi(
    val gear: Gear,
    val multiplier: Int,
    val paused: Boolean,
    val hold: Boolean,
    val phoneUrl: String,
    val phoneConnected: Int,
    val lines: List<String>,
    val proposalOptions: List<DevOption>,
    val voteTargetLabel: String?,
    val voteTargetId: String?,
    val voters: List<FamilyMember>,
)

data class DevOption(val member: FamilyMember, val placeId: String, val placeName: String, val message: String?)
