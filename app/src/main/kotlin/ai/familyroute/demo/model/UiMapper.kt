package ai.familyroute.demo.model

import ai.familyroute.demo.domain.DemoCatalog
import ai.familyroute.demo.domain.EngineState
import ai.familyroute.demo.domain.Place
import ai.familyroute.demo.domain.ProposalRecord
import ai.familyroute.demo.domain.ProposalStatus
import ai.familyroute.demo.domain.Rules
import ai.familyroute.demo.domain.Screen
import ai.familyroute.demo.domain.TripEngine
import ai.familyroute.demo.domain.TripPlan
import ai.familyroute.demo.domain.VoteChoice
import ai.familyroute.demo.map.MapMarker
import ai.familyroute.demo.map.MapUiState
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 엔진 상태 → 화면 상태. 순수 함수라 단위 테스트할 수 있다. */
object UiMapper {

    fun map(s: EngineState): TripUiState {
        val stops = mainStops(s)
        return TripUiState(
            screen = s.screen,
            driving = s.isDriving,
            parked = s.isParked,
            // 회전 안내·도로명은 Phase 2 시뮬레이터에서 계산한다
            turn = TurnGuide(1200, TurnDirection.RIGHT, "서울양양고속도로 방면 우측"),
            connectedMembers = FamilyMember.entries.size,
            roadName = "경춘로",
            speedKph = s.vehicle.speedKph.roundToInt(),
            destinationName = DemoCatalog.DESTINATION_NAME,
            eta = TripPlan.eta(s),
            remainingMinutes = TripPlan.remainingMinutes(s),
            remainingMeters = (TripPlan.remainingKm(s) * 1000).roundToInt(),
            budget = budget(s.accepted),
            stops = stops.map { it.second },
            members = FamilyMember.entries.map { memberStatus(s, it) },
            pendingProposals = s.undecided.size,
            map = MapUiState(markers = stops.mapNotNull { (p, ui) -> markerOf(p, ui) }),
            notice = s.notice?.let { NoticeUi(it.member, it.text) },
            suggest = suggest(s, stops),
            decide = if (s.screen == Screen.DECIDE) decide(s) else null,
            timeline = timeline(s),
            dev = if (s.devPanelOpen) dev(s) else null,
        )
    }

    // ---------- 메인 ----------

    private fun budget(accepted: List<ProposalRecord>): BudgetUi {
        val order = accepted.sortedBy { it.createdAtMs }.map { it.member }.distinct()
        return BudgetUi(
            totalMinutes = Rules.BUDGET_MIN,
            usage = order.map { m -> BudgetUsage(m, accepted.filter { it.member == m }.sumOf { it.place.detourMin }) },
        )
    }

    private fun isPassed(s: EngineState, p: Place) = p.routeKm < s.progressKm - Rules.AT_STOP_KM

    /** 들를 곳 목록: 앞으로 들를 확정 경유지 → 대기 제안 → 도착 후·귀갓길 */
    private fun mainStops(s: EngineState): List<Pair<ProposalRecord, StopUi>> {
        val upcoming = s.accepted.filterNot { isPassed(s, it.place) }.map { p ->
            val detail = if (p.urgent != null) {
                "${p.member.displayName} 요청 · ${p.urgent.label} · +${p.place.detourMin}분"
            } else {
                val atStop = s.currentStop?.id == p.id
                val minutes = TripPlan.minutesBetween(TripPlan.now(s), TripPlan.arrivalAt(s, p.place)).coerceAtLeast(0)
                val whenText = if (atStop) "지금" else "${minutes}분 후"
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
        StopStatus.CONFIRMED -> MapMarker(p.member, confirmed = true)
        StopStatus.PENDING -> MapMarker(p.member, confirmed = false)
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
        val outcome = TripEngine.recommendedOutcome(p, s.budgetLeftMin)
        val elapsed = (s.nowMs - active.shownAtMs).coerceAtLeast(0)
        val leftMs = (Rules.SUGGEST_TIMEOUT_MS - elapsed).coerceAtLeast(0)
        val eta = TripPlan.eta(s)
        val tiles = listOf(
            TileUi("+${p.place.detourMin}분", "우회 시간"),
            if (outcome == ProposalStatus.ACCEPTED) {
                TileUi(formatClock(TripPlan.eta(s, listOf(p.place))), "새 도착 · ${p.place.dwellMin}분 머묾 포함")
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
            title = "${p.member.subject} 들를 곳을 제안했어요",
            votesLine = (p.voters.map { "${it.displayName} ${voteLabel(p.votes[it])}" } + agoText(s.nowMs - p.createdAtMs)).joinToString(" · "),
            placeName = p.place.name,
            placeInfo = p.place.category + off,
            tiles = tiles,
            message = p.message,
            primaryLabel = actionLabel(outcome, done = false, driving = true),
            secondsLeft = ceil(leftMs / 1000.0).toInt(),
            fractionLeft = leftMs.toFloat() / Rules.SUGGEST_TIMEOUT_MS,
            map = MapUiState(
                markers = stops.filter { it.first.id != p.id }.mapNotNull { (r, ui) -> markerOf(r, ui) },
                preview = p.member,
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

    private fun decide(s: EngineState): DecideUi {
        val waiting = s.undecided
        val chosenAccepts = s.decideChoices.filterValues { it == ProposalStatus.ACCEPTED }.keys
            .mapNotNull { s.proposal(it) }
        val cards = waiting.take(MAX_CARDS).map { p ->
            val chosen = s.decideChoices[p.id]
            val othersDetour = chosenAccepts.filter { it.id != p.id }.sumOf { it.place.detourMin }
            val left = s.budgetLeftMin - othersDetour
            val outcome = chosen ?: TripEngine.recommendedOutcome(p, left)
            val banner = when {
                p.place.nearDestination -> BannerUi("목적지 근처라 도착 후 일정으로 넣을 수 있어요", warn = false)
                p.place.detourMin > left -> BannerUi("여유시간 ${Rules.BUDGET_MIN}분을 넘겨요 · 돌아오는 길 추천", warn = true)
                else -> null
            }
            DecideCardUi(
                id = p.id,
                member = p.member,
                placeName = p.place.name,
                info = p.place.category,
                detourText = "+${p.place.detourMin}분",
                arrivalText = if (p.place.nearDestination) "변화 없음" else formatClock(TripPlan.eta(s, listOf(p.place))),
                votes = p.voters.map { v ->
                    val vote = p.votes[v]
                    VoteChipUi(v, "${v.initial} ${voteLabel(vote)}", agreed = vote == VoteChoice.YES)
                },
                banner = banner,
                actionLabel = actionLabel(outcome, done = chosen != null, driving = false),
                selected = chosen != null,
            )
        }

        val eta = TripPlan.eta(s)
        val newEta = TripPlan.eta(s, chosenAccepts.map { it.place })
        val plannedAccepted = (s.accepted + chosenAccepts.map { it.copy(status = ProposalStatus.ACCEPTED) })
        val budget = budget(plannedAccepted)
        val current = s.currentStop
        val order = buildList {
            current?.let { add("${it.place.name} (지금)") }
            plannedAccepted
                .filter { it.id != current?.id && !isPassed(s, it.place) }
                .sortedBy { it.place.routeKm }
                .forEach { add("${it.place.name} · ${it.place.dwellMin}분 머묾") }
            add("${DemoCatalog.DESTINATION_NAME} 도착")
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
                deltaMinutes = TripPlan.minutesBetween(eta, newEta),
                budget = budget,
                budgetText = "여유시간 ${Rules.BUDGET_MIN}분 중 ${budget.usedMinutes}분 사용 · ${(Rules.BUDGET_MIN - budget.usedMinutes).coerceAtLeast(0)}분 남음",
                order = order,
            ),
        )
    }

    // ---------- 타임라인 ----------

    private fun timeline(s: EngineState): TimelineUi {
        val eta = TripPlan.eta(s)
        val stops = s.accepted
        val nodes = buildList {
            add(TimelineNodeUi(TimelineNodeKind.START, null, DemoCatalog.ORIGIN_NAME, formatClock(TripPlan.START)))
            stops.forEach { p ->
                val label = when {
                    isPassed(s, p.place) -> "다녀옴"
                    p.urgent != null -> p.urgent.label
                    else -> p.place.purpose
                }
                add(
                    TimelineNodeUi(
                        TimelineNodeKind.STOP, p.member, p.place.name,
                        "${formatClock(TripPlan.arrivalAt(s, p.place))} · ${p.place.dwellMin}분 · $label",
                    ),
                )
            }
            add(TimelineNodeUi(TimelineNodeKind.END, null, DemoCatalog.DESTINATION_NAME, formatClock(eta)))
        }
        val kms = listOf(0.0) + stops.map { it.place.routeKm } + DemoCatalog.TOTAL_KM
        val segments = kms.size - 1
        val progress = s.progressKm.coerceIn(0.0, DemoCatalog.TOTAL_KM)
        val i = (0 until segments).firstOrNull { progress <= kms[it + 1] } ?: (segments - 1)
        val span = (kms[i + 1] - kms[i]).takeIf { it > 0 } ?: 1.0
        val nowFraction = ((i + (progress - kms[i]) / span) / segments).toFloat().coerceIn(0f, 1f)

        val usage = budget(stops)
        val used = usage.usage.associate { it.member to it.minutes }
        val rowOrder = usage.usage.map { it.member } + FamilyMember.passengers.filter { it !in used }
        return TimelineUi(
            subtitle = "${DemoCatalog.ORIGIN_SHORT} → ${DemoCatalog.DESTINATION_SHORT} · 도착 ${formatClock(eta)}",
            nodes = nodes,
            nowFraction = nowFraction,
            timeRows = rowOrder.map { m ->
                val min = used[m] ?: 0
                MemberTimeRowUi(m, min, (min.toFloat() / Rules.BUDGET_MIN).coerceIn(0f, 1f))
            },
            budgetText = "여유시간 ${Rules.BUDGET_MIN}분 중 ${usage.remainingMinutes}분 남음",
            tokenRows = listOf(FamilyMember.MOM, FamilyMember.MINJUN, FamilyMember.SEOYEON).map { m ->
                val left = s.tokens[m] ?: 0
                val urgentNote = if (s.proposals.any { it.member == m && it.urgent != null }) " · 급한 요청은 따로 셈" else ""
                val text = when (left) {
                    0 -> "모두 사용"
                    Rules.TOKENS_PER_MEMBER -> "${left}회"
                    else -> "${left}회 남음"
                } + urgentNote
                TokenRowUi(m, left, Rules.TOKENS_PER_MEMBER, text)
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

    private fun dev(s: EngineState): DevUi {
        val target = s.undecided.lastOrNull()
        val lines = buildList {
            add("기어 ${s.vehicle.gear} · ${s.vehicle.speedKph.roundToInt()}km/h · ${if (s.isParked) "정차 확인됨" else if (s.isDriving) "주행 중" else "정차 확인 중(3초)"}")
            add("위치 ${"%.1f".format(s.progressKm)}km / ${DemoCatalog.TOTAL_KM.roundToInt()}km · 시계 ${formatClock(TripPlan.now(s))} · 도착 ${formatClock(TripPlan.eta(s))}")
            add("화면 ${s.screen} · 오버레이 ${s.suggest?.proposalId ?: "없음"}")
            add("예산 ${s.budgetUsedMin}/${Rules.BUDGET_MIN}분 · 제안권 " + FamilyMember.passengers.joinToString(" · ") { "${it.displayName} ${s.tokens[it] ?: 0}" })
            s.proposals.forEach { p ->
                val votes = p.votes.entries.joinToString(" ") { "${it.key.initial}:${voteLabel(it.value)}" }
                add("${p.id} ${p.member.displayName} ${p.place.name} — ${p.status}${if (p.urgent != null) " (급한 요청)" else ""} $votes")
            }
        }
        return DevUi(
            gear = s.vehicle.gear,
            lines = lines,
            proposalOptions = devOptions,
            voteTargetLabel = target?.let { "${it.id} ${it.member.displayName} · ${it.place.name}" },
            voteTargetId = target?.id,
            voters = target?.voters ?: emptyList(),
        )
    }
}
