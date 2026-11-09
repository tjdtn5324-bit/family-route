package ai.familyroute.demo.domain

import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.model.eulReul
import ai.familyroute.demo.model.eunNeun
import ai.familyroute.demo.route.ActiveRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 주행·가족 제안 상태의 유일한 주인. UI는 [state]를 읽고 아래 함수로 이벤트만 보낸다.
 * 시간은 밖에서 [tick]으로 넣어 준다(테스트에서 시계를 마음대로 돌릴 수 있게).
 */
class TripEngine(private val scenario: Scenario, nowMs: Long, initialVehicle: VehicleState) {
    private val _state = MutableStateFlow(DemoStart.state(scenario, nowMs, initialVehicle))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private inline fun mutate(block: (EngineState) -> EngineState) {
        _state.value = block(_state.value)
    }

    fun reset(nowMs: Long, vehicle: VehicleState) {
        _state.value = DemoStart.state(scenario, nowMs, vehicle)
    }

    // ---------- 입력: 시간·차량·가족 ----------

    fun tick(nowMs: Long) = mutate { s0 ->
        var s = s0.copy(nowMs = nowMs)
        s.suggest?.let { if (nowMs - it.shownAtMs >= Rules.SUGGEST_TIMEOUT_MS) s = resolveSuggest(s, ProposalStatus.NEXT_STOP) }
        s.notice?.let { if (nowMs >= it.untilMs) s = s.copy(notice = null) }
        autoPresent(s)
    }

    fun onVehicle(v: VehicleState) = mutate { s0 ->
        val still = v.gear == Gear.P && v.speedKph == 0.0
        var s = s0.copy(
            vehicle = v,
            stillSinceMs = if (still) (s0.stillSinceMs ?: s0.nowMs) else null,
            parkedSinceSimMs = if (v.gear == Gear.P) (s0.parkedSinceSimMs ?: v.timestampMs) else null,
        )
        s.currentStop?.let { here -> if (here.place.id !in s.visits) s = s.copy(visits = s.visits + (here.place.id to v.timestampMs)) }
        // 다시 출발하면 정차 화면은 고른 대로 반영하고 닫는다
        if (s.isDriving && s.screen == Screen.DECIDE) s = syncRoute(commitDecide(s))
        autoPresent(s)
    }

    fun onFamilyEvent(e: FamilyEvent) = mutate { s ->
        val result = when (e) {
            is FamilyEvent.Proposal -> onProposal(s, e)
            is FamilyEvent.Vote -> onVote(s, e)
            is FamilyEvent.UrgentRequest -> onUrgent(s, e)
        }
        autoPresent(syncRoute(result))
    }

    // ---------- 입력: 운전자 화면 ----------

    /** 하단 "가족 제안 보기". 정차 중이면 비교 화면, 주행 중이면 오버레이 하나 */
    fun openProposals() = mutate { s ->
        val waiting = s.undecided
        when {
            waiting.isEmpty() -> s.withNotice("대기 중인 가족 제안이 없어요", null)
            s.isParked -> s.copy(screen = Screen.DECIDE, suggest = null, decideChoices = emptyMap())
            else -> {
                val next = waiting.sortedBy { priority(it.status) }.first()
                present(s.copy(screen = Screen.MAIN), next.id)
            }
        }
    }

    /** 오버레이 첫 버튼: 예산 안이면 경유 추가, 아니면 추천한 대로(귀갓길/도착 후) */
    fun suggestPrimary() = mutate { s ->
        val p = s.suggest?.let { s.proposal(it.proposalId) } ?: return@mutate s
        syncRoute(resolveSuggest(s, recommendedOutcome(p, s.budgetLeftMin, s.isUnreachable(p.place))))
    }

    fun suggestDefer() = mutate { s -> if (s.suggest == null) s else resolveSuggest(s, ProposalStatus.NEXT_STOP) }

    fun suggestSkip() = mutate { s -> if (s.suggest == null) s else resolveSuggest(s, ProposalStatus.SKIPPED) }

    fun openTimeline() = mutate { s -> s.copy(screen = Screen.TIMELINE) }

    fun backToMain() = mutate { s ->
        when (s.screen) {
            Screen.DECIDE -> syncRoute(commitDecide(s))
            else -> s.copy(screen = Screen.MAIN)
        }
    }

    /** 정차 화면 카드 버튼: 고르기/취소 */
    fun decideToggle(id: String) = mutate { s ->
        val p = s.proposal(id) ?: return@mutate s
        if (id in s.decideChoices) {
            s.copy(decideChoices = s.decideChoices - id)
        } else {
            s.copy(decideChoices = s.decideChoices + (id to recommendedOutcome(p, decideBudgetLeft(s, exclude = id), s.isUnreachable(p.place))))
        }
    }

    fun decideDepart() = mutate { s -> syncRoute(commitDecide(s)) }

    fun setDevPanel(open: Boolean) = mutate { s -> s.copy(devPanelOpen = open) }

    // ---------- 규칙 ----------

    /**
     * 확정 경유지가 바뀌면 경로를 다시 만든다(우회 폴리라인 교체).
     * 이탈 지점을 이미 지난 곳은 경로에 넣을 수 없어 귀갓길로 돌린다.
     */
    private fun syncRoute(s0: EngineState): EngineState {
        var s = s0
        val wanted = s.accepted.map { it.place.id }.toSet()
        if (wanted == s.routeRequest) return s
        for (r in s.accepted) {
            if (r.place.id !in s.routeRequest && s.isUnreachable(r.place)) {
                s = s.replace(r.copy(status = ProposalStatus.RETURN_TRIP))
                    .withNotice("${r.place.name} 입구를 지나서 돌아오는 길에 들러요", r.member)
            }
        }
        val ids = s.accepted.map { it.place.id }.toSet()
        return s.copy(route = ActiveRoute.build(scenario, ids), routeRequest = ids)
    }

    private fun onProposal(s: EngineState, e: FamilyEvent.Proposal): EngineState {
        val member = FamilyMember.byId(e.memberId) ?: return s
        if (member.isDriver) return s
        val place = scenario.place(e.placeId) ?: return s
        if (s.proposals.any { it.place.id == place.id && it.status != ProposalStatus.SKIPPED && it.status != ProposalStatus.RETURN_TRIP }) {
            return s.withNotice("${place.name.eunNeun()} 이미 들어온 제안이에요", member)
        }
        val created = ProposalRecord(
            id = "p${s.nextSeq}",
            member = member,
            place = place,
            message = e.message,
            status = ProposalStatus.PROPOSED,
            votes = emptyMap(),
            createdAtMs = s.nowMs,
        )
        // 들어오자마자 가족 투표를 시작하고 운전자 화면에도 바로 보인다(횟수 제한 없음)
        val voting = created.copy(status = ProposalStatus.VOTING)
        return s.copy(
            proposals = s.proposals + voting,
            nextSeq = s.nextSeq + 1,
            lastProposalId = voting.id,
        )
    }

    private fun onVote(s: EngineState, e: FamilyEvent.Vote): EngineState {
        val voter = FamilyMember.byId(e.memberId) ?: return s
        // 대본은 엔진이 붙인 id를 모르므로 "@last" = 가장 최근 제안
        val id = if (e.proposalId == "@last") s.lastProposalId ?: return s else e.proposalId
        val p = s.proposal(id) ?: return s
        if (!p.status.undecided || voter !in p.voters) return s
        return applyMajority(s.replace(p.copy(votes = p.votes + (voter to e.choice))), id)
    }

    /**
     * 가족 과반수(운전자 포함 4명 중 3명, 제안자 포함)가 찬성하면 운전자 확인 없이 확정한다.
     * 목적지 근처는 도착 후 일정, 이탈 지점을 지난 곳은 귀갓길. 예산을 넘어도 가족이 정했으니 넣고 알린다.
     */
    private fun applyMajority(s: EngineState, id: String): EngineState {
        val p = s.proposal(id) ?: return s
        if (!p.status.undecided) return s
        if (!p.hasMajority) {
            val allVoted = p.voters.all { it in p.votes }
            return if (allVoted && p.status == ProposalStatus.VOTING) s.replace(p.copy(status = ProposalStatus.DRIVER_PENDING)) else s
        }
        val outcome = when {
            p.place.nearDestination -> ProposalStatus.AFTER_ARRIVAL
            s.isUnreachable(p.place) -> ProposalStatus.RETURN_TRIP
            else -> ProposalStatus.ACCEPTED
        }
        val over = p.place.detourMin - s.budgetLeftMin
        val text = when (outcome) {
            ProposalStatus.ACCEPTED -> "가족 과반수 찬성 · ${p.place.name} 경로에 추가" + if (over > 0) " (여유시간 ${over}분 초과)" else ""
            ProposalStatus.AFTER_ARRIVAL -> "가족 과반수 찬성 · ${p.place.name} 도착 후 일정"
            else -> "가족 과반수 찬성 · ${p.place.name}은 돌아오는 길에"
        }
        val decided = s.replace(p.copy(status = outcome, decidedBy = DecidedBy.FAMILY_MAJORITY))
        val closed = if (decided.suggest?.proposalId == id) decided.copy(suggest = null) else decided
        return closed.copy(decideChoices = closed.decideChoices - id).withNotice(text, p.member)
    }

    /** 급한 요청: 제안권을 쓰지 않고, 앞쪽 가장 가까운 휴게소를 운전자 확인 없이 확정 */
    private fun onUrgent(s: EngineState, e: FamilyEvent.UrgentRequest): EngineState {
        val member = FamilyMember.byId(e.memberId) ?: return s
        if (member.isDriver) return s
        val taken = s.accepted.map { it.place.id }.toSet()
        val rest = scenario.places
            .filter { it.kind == PlaceKind.REST_AREA && it.id !in taken && it.detour.exitIndex > s.baseIndexNow }
            .minByOrNull { it.routeKm }
        if (rest == null) {
            // 이미 앞쪽 휴게소가 확정돼 있으면 그곳을 알려 준다
            val already = s.accepted.firstOrNull { it.place.kind == PlaceKind.REST_AREA && !s.isPassed(it.place) }
            val text = if (already != null) "${member.displayName} ${e.type.label} 요청 · ${already.place.name}에 들러요" else "${member.displayName} ${e.type.label} 요청 · 가까운 휴게소가 없어요"
            return s.withNotice(text, member)
        }
        val record = ProposalRecord(
            id = "p${s.nextSeq}",
            member = member,
            place = rest,
            message = null,
            status = ProposalStatus.ACCEPTED,
            votes = emptyMap(),
            createdAtMs = s.nowMs,
            urgent = e.type,
            presented = true,
            decidedBy = DecidedBy.URGENT,
        )
        return s.copy(proposals = s.proposals + record, nextSeq = s.nextSeq + 1)
            .withNotice("${member.displayName} 급한 요청 · ${e.type.label} → ${rest.name.eulReul()} 추가했어요", member)
    }

    /** 주행 중이고 오버레이가 비어 있으면 운전자 확인 대기 제안을 하나 띄운다 */
    private fun autoPresent(s: EngineState): EngineState {
        if (s.suggest != null || !s.isDriving || s.devPanelOpen || s.screen == Screen.DECIDE) return s
        val next = s.undecided.firstOrNull {
            (it.status == ProposalStatus.VOTING || it.status == ProposalStatus.DRIVER_PENDING) && !it.presented
        }
        if (next != null) return present(s.copy(screen = Screen.MAIN), next.id)
        // 미룬 제안 장소에 가까워지면 한 번 더 묻는다(들어갈 수 있을 때, 한 번만)
        val near = s.undecided.firstOrNull {
            it.status == ProposalStatus.NEXT_STOP && !it.reminded && !it.place.nearDestination &&
                !s.isUnreachable(it.place) && s.kmTo(it.place) in Rules.REMIND_UNTIL_KM..Rules.REMIND_FROM_KM
        } ?: return s
        return present(s.copy(screen = Screen.MAIN), near.id, reminder = true)
    }

    private fun present(s: EngineState, id: String, reminder: Boolean = false): EngineState {
        val p = s.proposal(id) ?: return s
        val marked = if (reminder) p.copy(presented = true, reminded = true) else p.copy(presented = true)
        return s.replace(marked).copy(suggest = ActiveSuggest(id, s.nowMs, reminder))
    }

    private fun resolveSuggest(s: EngineState, outcome: ProposalStatus): EngineState {
        val p = s.suggest?.let { s.proposal(it.proposalId) } ?: return s.copy(suggest = null)
        val by = if (outcome.undecided || outcome == ProposalStatus.SKIPPED) null else DecidedBy.DRIVER
        return s.replace(p.copy(status = outcome, decidedBy = by)).copy(suggest = null)
    }

    private fun commitDecide(s: EngineState): EngineState {
        var n = s
        s.decideChoices.forEach { (id, outcome) -> n.proposal(id)?.let { n = n.replace(it.copy(status = outcome, decidedBy = DecidedBy.DRIVER)) } }
        return n.copy(decideChoices = emptyMap(), screen = Screen.MAIN)
    }

    private fun decideBudgetLeft(s: EngineState, exclude: String): Int =
        s.budgetLeftMin - s.decideChoices
            .filter { (id, o) -> id != exclude && o == ProposalStatus.ACCEPTED }
            .keys.sumOf { s.proposal(it)?.place?.detourMin ?: 0 }

    private fun priority(status: ProposalStatus) = when (status) {
        ProposalStatus.DRIVER_PENDING -> 0
        ProposalStatus.NEXT_STOP -> 1
        else -> 2
    }

    companion object {
        /** 목적지 5km 안이면 도착 후 일정, 예산을 넘으면 귀갓길, 아니면 경유 추가 */
        fun recommendedOutcome(p: ProposalRecord, budgetLeftMin: Int, unreachable: Boolean = false): ProposalStatus = when {
            p.place.nearDestination -> ProposalStatus.AFTER_ARRIVAL
            unreachable -> ProposalStatus.RETURN_TRIP
            p.place.detourMin > budgetLeftMin -> ProposalStatus.RETURN_TRIP
            else -> ProposalStatus.ACCEPTED
        }
    }
}

private fun EngineState.replace(p: ProposalRecord) = copy(proposals = proposals.map { if (it.id == p.id) p else it })

private fun EngineState.withNotice(text: String, member: FamilyMember?) =
    copy(notice = Notice(text, member, nowMs + Rules.NOTICE_MS))

/**
 * 시작 상태: 출발 전에 가족이 합의한 경유지(시나리오 initialAccepted, 엄마 두물머리 카페)는 확정으로 시작한다.
 * 나머지 가족 이벤트는 ScriptedFamilySource가 주행 거리에 맞춰 보낸다.
 */
object DemoStart {
    fun state(scenario: Scenario, nowMs: Long, vehicle: VehicleState): EngineState {
        var seq = 1
        val proposals = scenario.initialAccepted.mapNotNull { init ->
            val m = FamilyMember.byId(init.memberId) ?: return@mapNotNull null
            val place = scenario.place(init.placeId) ?: return@mapNotNull null
            ProposalRecord("p${seq++}", m, place, null, ProposalStatus.ACCEPTED, emptyMap(), nowMs, presented = true, decidedBy = DecidedBy.PLAN)
        }
        val ids = proposals.map { it.place.id }.toSet()
        return EngineState(
            scenario = scenario,
            route = ActiveRoute.build(scenario, ids),
            routeRequest = ids,
            nowMs = nowMs,
            vehicle = vehicle,
            stillSinceMs = if (vehicle.gear == Gear.P && vehicle.speedKph == 0.0) nowMs else null,
            parkedSinceSimMs = null,
            proposals = proposals,
            screen = Screen.MAIN,
            suggest = null,
            decideChoices = emptyMap(),
            notice = null,
            devPanelOpen = false,
            nextSeq = seq,
            visits = emptyMap(),
            lastProposalId = null,
        )
    }
}
