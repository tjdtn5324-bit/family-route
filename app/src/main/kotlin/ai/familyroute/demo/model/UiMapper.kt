package ai.familyroute.demo.model

import ai.familyroute.demo.data.SimControl
import ai.familyroute.demo.domain.EngineState
import ai.familyroute.demo.domain.Place
import ai.familyroute.demo.domain.ProposalRecord
import ai.familyroute.demo.domain.ProposalStatus
import ai.familyroute.demo.domain.Rules
import ai.familyroute.demo.domain.Screen
import ai.familyroute.demo.domain.TripEngine
import ai.familyroute.demo.domain.TripMath
import ai.familyroute.demo.domain.VoteChoice
import ai.familyroute.demo.map.MapMarker
import ai.familyroute.demo.map.MapPreview
import ai.familyroute.demo.map.MapUiState
import ai.familyroute.demo.route.Geo
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 엔진 상태 → 화면 상태. 순수 함수라 단위 테스트할 수 있다. */
/** 엔진 밖의 정보: 시뮬레이터 조작 상태, 가족 폰 연결 */
data class UiExtras(
    val sim: SimControl = SimControl(20, paused = false, hold = false),
    val phoneUrl: String = "",
    val phoneConnected: Int = 0,
    val scriptFired: Int = 0,
    val scriptTotal: Int = 0,
)

object UiMapper {

    fun map(s: EngineState, x: UiExtras = UiExtras()): TripUiState {
        val stops = mainStops(s)
        return TripUiState(
            screen = s.screen,
            driving = s.isDriving,
            parked = s.isParked,
            turn = turnGuide(s),
            connectedMembers = FamilyMember.entries.size,
            roadName = s.route.roadAt(s.progressM),
            speedKph = s.vehicle.speedKph.roundToInt(),
            destinationName = s.scenario.destinationName,
            eta = TripMath.eta(s),
            remainingMinutes = TripMath.remainingMinutes(s),
            remainingMeters = (TripMath.remainingKm(s) * 1000).roundToInt(),
            budget = budget(s.accepted),
            stops = stops.map { it.second },
            members = FamilyMember.entries.map { memberStatus(s, it) },
            pendingProposals = s.undecided.size,
            map = mapState(s, stops, preview = null),
            notice = s.notice?.let { NoticeUi(it.member, it.text) },
            suggest = suggest(s, stops),
            decide = if (s.screen == Screen.DECIDE) decide(s, x) else null,
            timeline = timeline(s),
            dev = if (s.devPanelOpen) dev(s, x) else null,
        )
    }

    private fun turnGuide(s: EngineState): TurnGuide {
        val next = s.route.turns.firstOrNull { it.distM > s.progressM }
        if (next == null || next.distM - s.progressM > 30_000) {
            val left = (s.route.length - s.progressM).roundToInt()
            return TurnGuide(left, TurnDirection.STRAIGHT, "${s.scenario.destinationName} 도착")
        }
        return TurnGuide(
            (next.distM - s.progressM).roundToInt(),
            if (next.right) TurnDirection.RIGHT else TurnDirection.LEFT,
            next.text,
        )
    }

    private fun mapState(s: EngineState, stops: List<Pair<ProposalRecord, StopUi>>, preview: MapPreview?) = MapUiState(
        route = s.route,
        progressM = s.progressM,
        speedMps = s.vehicle.speedKph / 3.6,
        markers = stops.mapNotNull { (p, ui) -> markerOf(p, ui) },
        destination = s.route.points.last().pt,
        preview = preview,
    )

    // ---------- 메인 ----------

    private fun budget(accepted: List<ProposalRecord>): BudgetUi {
        val order = accepted.sortedBy { it.createdAtMs }.map { it.member }.distinct()
        return BudgetUi(
            totalMinutes = Rules.BUDGET_MIN,
            usage = order.map { m -> BudgetUsage(m, accepted.filter { it.member == m }.sumOf { it.place.detourMin }) },
        )
    }

    private fun isPassed(s: EngineState, p: Place) = s.isPassed(p)

    /** 들를 곳 목록: 앞으로 들를 확정 경유지 → 대기 제안 → 도착 후·귀갓길 */
    private fun mainStops(s: EngineState): List<Pair<ProposalRecord, StopUi>> {
        val upcoming = s.accepted.filterNot { isPassed(s, it.place) }.map { p ->
            val detail = if (p.urgent != null) {
                "${p.member.displayName} 요청 · ${p.urgent.label} · +${p.place.detourMin}분"
            } else {
                val atStop = s.currentStop?.id == p.id
                val stop = s.stopOf(p.place.id)
                val minutes = stop?.let { TripMath.minutesBetween(TripMath.now(s), TripMath.arrivalAt(s, it)).coerceAtLeast(0) }
                val whenText = when {
                    atStop -> "지금"
                    minutes == null -> "경로 준비 중"
                    else -> "${minutes}분 후"
                }
                "${p.member.displayName} 제안 · $whenText · +${p.place.detourMin}분"
            }
            p to StopUi(p.member, p.place.name, detail, StopStatus.CONFIRMED)
        }
        val waiting = s.undecided.map { p ->
            p to StopUi(p.member, p.place.name, "${p.member.displayName} 제안 · +${p.place.detourMin}분 · ${waitingText(p)}", StopStatus.PENDING)
        }
        val later = s.proposals.filter { it.status == ProposalStatus.AFTER_ARRIVAL || it.status == ProposalStatus.RETURN_TRIP }.map { p ->
            val after = p.status == ProposalStatus.AFTER_ARRIVAL
            p to StopUi(
                p.member, p.place.name,
                "${p.member.displayName} 제안 · ${if (after) "도착 후 일정" else "돌아오는 길에"}",
                if (after) StopStatus.AFTER_ARRIVAL else StopStatus.RETURN_TRIP,
            )
        }
        return upcoming + waiting + later
    }

    private fun waitingText(p: ProposalRecord): String = when {
        p.votes.size < p.voters.size && p.status != ProposalStatus.NEXT_STOP -> "가족 투표 중"
        p.status == ProposalStatus.NEXT_STOP -> "다음 정차 때 결정"
        else -> "운전자 확인 대기"
    }

    private fun markerOf(p: ProposalRecord, ui: StopUi): MapMarker? = when (ui.status) {
        StopStatus.CONFIRMED -> MapMarker(p.member, confirmed = true, pt = p.place.pt)
        StopStatus.PENDING -> MapMarker(p.member, confirmed = false, pt = p.place.pt)
        else -> null
    }

    private fun memberStatus(s: EngineState, m: FamilyMember): MemberStatusUi {
        if (m.isDriver) return MemberStatusUi(m, if (s.isDriving) "운전 중" else "정차 중", false)
        val pending = s.undecided.filter { it.member == m }
        if (pending.isNotEmpty()) {
            val voting = pending.any { it.votes.size < it.voters.size && it.status != ProposalStatus.NEXT_STOP }
            val tail = when {
                voting -> "투표 중"
                pending.any { it.status == ProposalStatus.DRIVER_PENDING } -> "확인 대기"
                else -> "정차 때 결정"
            }
            return MemberStatusUi(m, "제안 ${pending.size} · $tail", true)
        }
        val latest = s.proposals.filter { it.member == m }.maxByOrNull { it.createdAtMs }
            ?: return MemberStatusUi(m, "함께 가는 중", false)
        return when {
            latest.urgent != null -> MemberStatusUi(m, "${latest.urgent.label} 요청 → ${latest.place.shortName}", true)
            latest.status == ProposalStatus.ACCEPTED -> MemberStatusUi(m, "제안 반영됨", false)
            latest.status == ProposalStatus.AFTER_ARRIVAL -> MemberStatusUi(m, "도착 후 일정", false)
            latest.status == ProposalStatus.RETURN_TRIP -> MemberStatusUi(m, "귀갓길에 들러요", false)
            else -> MemberStatusUi(m, "제안 넘김", false)
        }
    }

    // ---------- 주행 중 제안 ----------

    private fun suggest(s: EngineState, stops: List<Pair<ProposalRecord, StopUi>>): SuggestUi? {
        val active = s.suggest ?: return null
        val p = s.proposal(active.proposalId) ?: return null
        val outcome = TripEngine.recommendedOutcome(p, s.budgetLeftMin, s.isUnreachable(p.place))
        val elapsed = (s.nowMs - active.shownAtMs).coerceAtLeast(0)
        val leftMs = (Rules.SUGGEST_TIMEOUT_MS - elapsed).coerceAtLeast(0)
        val eta = TripMath.eta(s)
        val tiles = listOf(
            TileUi("+${p.place.detourMin}분", "우회 시간"),
            if (outcome == ProposalStatus.ACCEPTED) {
                TileUi(formatClock(TripMath.eta(s, listOf(p.place))), "새 도착 · ${p.place.dwellMin}분 머묾 포함")
            } else {
                TileUi(formatClock(eta), "도착 시각 그대로")
            },
            when (outcome) {
                ProposalStatus.ACCEPTED -> TileUi("${s.budgetLeftMin - p.place.detourMin}분", "여유시간 남음", warn = true)
                ProposalStatus.RETURN_TRIP -> TileUi("${p.place.detourMin - s.budgetLeftMin}분 초과", "여유시간을 넘겨요", warn = true)
                else -> TileUi("${s.budgetLeftMin.coerceAtLeast(0)}분", "여유시간 그대로", warn = true)
            },
        )
        val off = if (p.place.offRouteKm > 0 && !p.place.nearDestination) " · 경로에서 ${p.place.offRouteKm}km 옆" else ""
        return SuggestUi(
            proposalId = p.id,
            member = p.member,
            title = if (active.reminder) "곧 ${p.place.name} 근처를 지나요" else "${p.member.subject} 들를 곳을 제안했어요",
            votesLine = if (active.reminder) {
                "${p.member.displayName} 제안 · 한 번 더 물어볼게요 · ${"%.1f".format(s.kmTo(p.place))}km 앞"
            } else {
                val need = FamilyMember.entries.size / 2 + 1 - p.yesCount
                (p.voters.map { "${it.displayName} ${voteLabel(p.votes[it])}" } + "과반수까지 ${need}표" + agoText(s.nowMs - p.createdAtMs)).joinToString(" · ")
            },
            placeName = p.place.name,
            placeInfo = p.place.category + off,
            tiles = tiles,
            message = p.message,
            primaryLabel = actionLabel(outcome, done = false, driving = true),
            secondsLeft = ceil(leftMs / 1000.0).toInt(),
            fractionLeft = leftMs.toFloat() / Rules.SUGGEST_TIMEOUT_MS,
            map = mapState(
                s,
                stops.filter { it.first.id != p.id },
                preview = MapPreview(p.member, p.place.detour.via, p.place.pt),
            ),
        )
    }

    private fun voteLabel(v: VoteChoice?) = when (v) {
        VoteChoice.YES -> "찬성"
        VoteChoice.NO -> "반대"
        null -> "아직"
    }

    private fun agoText(ms: Long): String {
        val min = ms / 60_000
        return if (min < 1) "방금" else "${min}분 전"
    }

    fun actionLabel(outcome: ProposalStatus, done: Boolean, driving: Boolean): String = when (outcome) {
        ProposalStatus.ACCEPTED -> if (driving) "경유 추가" else if (done) "코스에 넣음" else "코스에 넣기"
        ProposalStatus.RETURN_TRIP -> if (done) "귀갓길로 미룸" else "귀갓길로 미루기"
        ProposalStatus.AFTER_ARRIVAL -> if (done) "도착 후 일정에 넣음" else "도착 후 일정에 넣기"
        else -> ""
    }

    // ---------- 정차 중 비교 ----------

    private const val MAX_CARDS = 3

    private fun decide(s: EngineState, x: UiExtras): DecideUi {
        val waiting = s.undecided
        val chosenAccepts = s.decideChoices.filterValues { it == ProposalStatus.ACCEPTED }.keys
            .mapNotNull { s.proposal(it) }
        val cards = waiting.take(MAX_CARDS).map { p ->
            val chosen = s.decideChoices[p.id]
            val othersDetour = chosenAccepts.filter { it.id != p.id }.sumOf { it.place.detourMin }
            val left = s.budgetLeftMin - othersDetour
            val unreachable = s.isUnreachable(p.place)
            val outcome = chosen ?: TripEngine.recommendedOutcome(p, left, unreachable)
            val banner = when {
                p.place.nearDestination -> BannerUi("목적지 근처라 도착 후 일정으로 넣을 수 있어요", warn = false)
                unreachable -> BannerUi("이미 지나온 곳이라 돌아오는 길에 들러요", warn = true)
                p.place.detourMin > left -> BannerUi("여유시간 ${Rules.BUDGET_MIN}분을 넘겨요 · 돌아오는 길 추천", warn = true)
                else -> null
            }
            DecideCardUi(
                id = p.id,
                member = p.member,
                placeName = p.place.name,
                info = p.place.category,
                detourText = "+${p.place.detourMin}분",
                arrivalText = if (p.place.nearDestination) "변화 없음" else formatClock(TripMath.eta(s, listOf(p.place))),
                votes = p.voters.map { v ->
                    val vote = p.votes[v]
                    VoteChipUi(v, "${v.initial} ${voteLabel(vote)}", agreed = vote == VoteChoice.YES)
                },
                banner = banner,
                actionLabel = actionLabel(outcome, done = chosen != null, driving = false),
                selected = chosen != null,
            )
        }

        val eta = TripMath.eta(s)
        val newEta = TripMath.eta(s, chosenAccepts.map { it.place })
        val plannedAccepted = (s.accepted + chosenAccepts.map { it.copy(status = ProposalStatus.ACCEPTED) })
        val budget = budget(plannedAccepted)
        val current = s.currentStop
        val order = buildList {
            current?.let { add("${it.place.name} (지금)") }
            plannedAccepted
                .filter { it.id != current?.id && !isPassed(s, it.place) }
                .sortedBy { s.stopOf(it.place.id)?.distM ?: (it.place.routeKm * 1000) }
                .forEach { add("${it.place.name} · ${it.place.dwellMin}분 머묾") }
            add("${s.scenario.destinationName} 도착")
            val afterIds = s.decideChoices.filterValues { it == ProposalStatus.AFTER_ARRIVAL }.keys
            (s.proposals.filter { it.status == ProposalStatus.AFTER_ARRIVAL } + afterIds.mapNotNull { s.proposal(it) })
                .forEach { add("${it.place.name} · 도착 후") }
        }.mapIndexed { i, line -> "${circled(i + 1)} $line" }

        return DecideUi(
            stopName = current?.place?.name ?: "길가",
            cards = cards,
            hiddenCount = (waiting.size - MAX_CARDS).coerceAtLeast(0),
            summary = DecideSummaryUi(
                newEta = newEta,
                originalEta = eta,
                deltaMinutes = TripMath.minutesBetween(eta, newEta),
                budget = budget,
                budgetText = "여유시간 ${Rules.BUDGET_MIN}분 중 ${budget.usedMinutes}분 사용 · ${(Rules.BUDGET_MIN - budget.usedMinutes).coerceAtLeast(0)}분 남음",
                order = order,
                phoneNote = if (x.phoneConnected > 0) "결정 결과는 가족 폰 ${x.phoneConnected}대에 바로 알려져요" else "가족 폰을 연결하면 결정 결과가 바로 알려져요",
            ),
        )
    }

    // ---------- 타임라인 ----------

    private fun timeline(s: EngineState): TimelineUi {
        val eta = TripMath.eta(s)
        val stops = s.accepted.filter { s.stopOf(it.place.id) != null }
        val nodes = buildList {
            add(TimelineNodeUi(TimelineNodeKind.START, null, s.scenario.originName, formatClock(s.scenario.start)))
            stops.forEach { p ->
                val stop = s.stopOf(p.place.id)!!
                val passed = isPassed(s, p.place)
                val label = when {
                    passed -> "다녀옴"
                    s.currentStop?.id == p.id -> "지금"
                    p.urgent != null -> p.urgent.label
                    else -> p.place.purpose
                }
                val at = s.visits[p.place.id]?.let { TripMath.clock(it) } ?: TripMath.arrivalAt(s, stop)
                add(TimelineNodeUi(TimelineNodeKind.STOP, p.member, p.place.name, "${formatClock(at)} · ${p.place.dwellMin}분 · $label"))
            }
            add(TimelineNodeUi(TimelineNodeKind.END, null, s.scenario.destinationName, formatClock(eta)))
        }
        val ds = listOf(0.0) + stops.map { s.stopOf(it.place.id)!!.distM } + s.route.length
        val segments = ds.size - 1
        val progress = s.progressM.coerceIn(0.0, s.route.length)
        val i = (0 until segments).firstOrNull { progress <= ds[it + 1] } ?: (segments - 1)
        val span = (ds[i + 1] - ds[i]).takeIf { it > 0 } ?: 1.0
        val nowFraction = ((i + (progress - ds[i]) / span) / segments).toFloat().coerceIn(0f, 1f)

        val usage = budget(s.accepted)
        val used = usage.usage.associate { it.member to it.minutes }
        val rowOrder = usage.usage.map { it.member } + FamilyMember.passengers.filter { it !in used }
        return TimelineUi(
            subtitle = "${s.scenario.originShort} → ${s.scenario.destinationShort} · 도착 ${formatClock(eta)}",
            nodes = nodes,
            nowFraction = nowFraction,
            timeRows = rowOrder.map { m ->
                val min = used[m] ?: 0
                MemberTimeRowUi(m, min, (min.toFloat() / Rules.BUDGET_MIN).coerceIn(0f, 1f))
            },
            budgetText = "여유시간 ${Rules.BUDGET_MIN}분 중 ${usage.remainingMinutes}분 남음",
            tokenRows = FamilyMember.passengers.map { m ->
                val mine = s.proposals.filter { it.member == m && it.urgent == null && it.decidedBy != ai.familyroute.demo.domain.DecidedBy.PLAN }
                val added = mine.count { it.status == ProposalStatus.ACCEPTED }
                val urgent = s.proposals.count { it.member == m && it.urgent != null }
                val text = buildString {
                    append("제안 ${mine.size}번 · 경로 반영 ${added}번")
                    if (urgent > 0) append(" · 급한 요청 ${urgent}번")
                }
                TokenRowUi(m, mine.size, added, text)
            },
        )
    }

    // ---------- 개발자 패널 ----------

    private val devOptions = listOf(
        DevOption(FamilyMember.SEOYEON, "hanu_hoengseong", "횡성 한우 식당", "배고파! 점심 여기서 먹자"),
        DevOption(FamilyMember.MOM, "sheep_daegwallyeong", "대관령 양떼목장", null),
        DevOption(FamilyMember.MINJUN, "market_gangneung", "강릉 중앙시장", null),
        DevOption(FamilyMember.SEOYEON, "cafe_dumulmeori", "두물머리 카페", null),
    )

    private fun dev(s: EngineState, x: UiExtras): DevUi {
        val target = s.undecided.lastOrNull()
        val lines = buildList {
            add("기어 ${s.vehicle.gear} · ${s.vehicle.speedKph.roundToInt()}km/h · ${if (s.isParked) "정차 확인됨" else if (s.isDriving) "주행 중" else "정차 확인 중(3초)"} · ${s.route.roadAt(s.progressM)}")
            add("위치 ${"%.1f".format(s.progressM / 1000)} / ${"%.1f".format(s.route.length / 1000)}km · 시계 ${formatClock(TripMath.now(s))} · 도착 ${formatClock(TripMath.eta(s))} · 대본 ${x.scriptFired}/${x.scriptTotal}")
            add("화면 ${s.screen} · 오버레이 ${s.suggest?.proposalId ?: "없음"}")
            add("예산 ${s.budgetUsedMin}/${Rules.BUDGET_MIN}분 · 과반수 기준 ${FamilyMember.entries.size / 2 + 1}명 찬성")
            s.proposals.forEach { p ->
                val votes = p.votes.entries.joinToString(" ") { "${it.key.initial}:${voteLabel(it.value)}" }
                add("${p.id} ${p.member.displayName} ${p.place.name} — ${p.status}${if (p.urgent != null) " (급한 요청)" else ""} $votes")
            }
        }
        return DevUi(
            gear = s.vehicle.gear,
            multiplier = x.sim.multiplier,
            paused = x.sim.paused,
            hold = x.sim.hold,
            phoneUrl = x.phoneUrl,
            phoneConnected = x.phoneConnected,
            lines = lines,
            proposalOptions = devOptions,
            voteTargetLabel = target?.let { "${it.id} ${it.member.displayName} · ${it.place.name}" },
            voteTargetId = target?.id,
            voters = target?.voters ?: emptyList(),
        )
    }
}
