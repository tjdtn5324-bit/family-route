package ai.familyroute.demo.domain

import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.model.eulReul
import ai.familyroute.demo.model.eunNeun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 주행·가족 제안 상태의 유일한 주인. UI는 [state]를 읽고 아래 함수로 이벤트만 보낸다.
 * 시간은 밖에서 [tick]으로 넣어 준다(테스트에서 시계를 마음대로 돌릴 수 있게).
 */
class TripEngine(nowMs: Long, initialVehicle: VehicleState = DemoStart.vehicle(nowMs)) {
    private val _state = MutableStateFlow(DemoStart.state(nowMs, initialVehicle))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private inline fun mutate(block: (EngineState) -> EngineState) {
        _state.value = block(_state.value)
    }

    fun reset(nowMs: Long, vehicle: VehicleState = DemoStart.vehicle(nowMs)) {
        _state.value = DemoStart.state(nowMs, vehicle)
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
        var s = s0.copy(vehicle = v, stillSinceMs = if (still) (s0.stillSinceMs ?: s0.nowMs) else null)
        // 다시 출발하면 정차 화면은 고른 대로 반영하고 닫는다
        if (s.isDriving && s.screen == Screen.DECIDE) s = commitDecide(s)
        autoPresent(s)
    }

    fun onFamilyEvent(e: FamilyEvent) = mutate { s ->
        val result = when (e) {
            is FamilyEvent.Proposal -> onProposal(s, e)
            is FamilyEvent.Vote -> onVote(s, e)
            is FamilyEvent.UrgentRequest -> onUrgent(s, e)
        }
        autoPresent(result)
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
        resolveSuggest(s, recommendedOutcome(p, s.budgetLeftMin))
    }

    fun suggestDefer() = mutate { s -> if (s.suggest == null) s else resolveSuggest(s, ProposalStatus.NEXT_STOP) }

    fun suggestSkip() = mutate { s -> if (s.suggest == null) s else resolveSuggest(s, ProposalStatus.SKIPPED) }

    fun openTimeline() = mutate { s -> s.copy(screen = Screen.TIMELINE) }

    fun backToMain() = mutate { s ->
        when (s.screen) {
            Screen.DECIDE -> commitDecide(s)
            else -> s.copy(screen = Screen.MAIN)
        }
    }

    /** 정차 화면 카드 버튼: 고르기/취소 */
    fun decideToggle(id: String) = mutate { s ->
        val p = s.proposal(id) ?: return@mutate s
        if (id in s.decideChoices) {
            s.copy(decideChoices = s.decideChoices - id)
        } else {
            s.copy(decideChoices = s.decideChoices + (id to recommendedOutcome(p, decideBudgetLeft(s, exclude = id))))
        }
    }

    fun decideDepart() = mutate { s -> commitDecide(s) }

    fun setDevPanel(open: Boolean) = mutate { s -> s.copy(devPanelOpen = open) }

    // ---------- 규칙 ----------

    private fun onProposal(s: EngineState, e: FamilyEvent.Proposal): EngineState {
        val member = FamilyMember.byId(e.memberId) ?: return s
        if (member.isDriver) return s
        val place = DemoCatalog.place(e.placeId) ?: return s
        if ((s.tokens[member] ?: 0) <= 0) return s.withNotice("${member.displayName} 제안권을 다 썼어요", member)
        if (s.proposals.any { it.place.id == place.id && it.status != ProposalStatus.SKIPPED }) {
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
        // 들어오자마자 가족 투표를 시작한다
        val voting = evaluateVotes(created.copy(status = ProposalStatus.VOTING))
        return s.copy(
            proposals = s.proposals + voting,
            tokens = s.tokens + (member to (s.tokens[member] ?: 0) - 1),
            nextSeq = s.nextSeq + 1,
        )
    }

    private fun onVote(s: EngineState, e: FamilyEvent.Vote): EngineState {
        val voter = FamilyMember.byId(e.memberId) ?: return s
        val p = s.proposal(e.proposalId) ?: return s
        if (!p.status.undecided || voter !in p.voters) return s
        val updated = evaluateVotes(p.copy(votes = p.votes + (voter to e.choice)))
        return s.replace(updated)
    }

    /** 급한 요청: 제안권을 쓰지 않고, 앞쪽 가장 가까운 휴게소를 운전자 확인 없이 확정 */
    private fun onUrgent(s: EngineState, e: FamilyEvent.UrgentRequest): EngineState {
        val member = FamilyMember.byId(e.memberId) ?: return s
        if (member.isDriver) return s
        val taken = s.accepted.map { it.place.id }.toSet()
        val rest = DemoCatalog.places
            .filter { it.kind == PlaceKind.REST_AREA && it.routeKm >= s.progressKm - Rules.AT_STOP_KM && it.id !in taken }
            .minByOrNull { it.routeKm }
        if (rest == null) {
            // 이미 앞쪽 휴게소가 확정돼 있으면 그곳을 알려 준다
            val already = s.accepted.firstOrNull { it.place.kind == PlaceKind.REST_AREA && it.place.routeKm >= s.progressKm }
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
        )
        return s.copy(proposals = s.proposals + record, nextSeq = s.nextSeq + 1)
            .withNotice("${member.displayName} 급한 요청 · ${e.type.label} → ${rest.name.eulReul()} 추가했어요", member)
    }

    /** 제안자 빼고 투표 대상의 절반 이상 찬성 → 운전자 확인 대기, 절반 넘게 반대 → 넘김 */
    private fun evaluateVotes(p: ProposalRecord): ProposalRecord {
        if (p.status != ProposalStatus.VOTING && p.status != ProposalStatus.PROPOSED) return p
        val n = p.voters.size
        val yes = p.votes.values.count { it == VoteChoice.YES }
        val no = p.votes.values.count { it == VoteChoice.NO }
        return when {
            n == 0 || (yes > 0 && yes * 2 >= n) -> p.copy(status = ProposalStatus.DRIVER_PENDING)
            no * 2 > n -> p.copy(status = ProposalStatus.SKIPPED)
            else -> p
        }
    }

    /** 주행 중이고 오버레이가 비어 있으면 운전자 확인 대기 제안을 하나 띄운다 */
    private fun autoPresent(s: EngineState): EngineState {
        if (s.suggest != null || !s.isDriving || s.devPanelOpen || s.screen == Screen.DECIDE) return s
        val next = s.undecided.firstOrNull { it.status == ProposalStatus.DRIVER_PENDING && !it.presented } ?: return s
        return present(s.copy(screen = Screen.MAIN), next.id)
    }

    private fun present(s: EngineState, id: String): EngineState {
        val p = s.proposal(id) ?: return s
        return s.replace(p.copy(presented = true)).copy(suggest = ActiveSuggest(id, s.nowMs))
    }

    private fun resolveSuggest(s: EngineState, outcome: ProposalStatus): EngineState {
        val p = s.suggest?.let { s.proposal(it.proposalId) } ?: return s.copy(suggest = null)
        return s.replace(p.copy(status = outcome)).copy(suggest = null)
    }

    private fun commitDecide(s: EngineState): EngineState {
        var n = s
        s.decideChoices.forEach { (id, outcome) -> n.proposal(id)?.let { n = n.replace(it.copy(status = outcome)) } }
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
        fun recommendedOutcome(p: ProposalRecord, budgetLeftMin: Int): ProposalStatus = when {
            p.place.nearDestination -> ProposalStatus.AFTER_ARRIVAL
            p.place.detourMin > budgetLeftMin -> ProposalStatus.RETURN_TRIP
            else -> ProposalStatus.ACCEPTED
        }
    }
}

private fun EngineState.replace(p: ProposalRecord) = copy(proposals = proposals.map { if (it.id == p.id) p else it })

private fun EngineState.withNotice(text: String, member: FamilyMember?) =
    copy(notice = Notice(text, member, nowMs + Rules.NOTICE_MS))

/**
 * 시작 상태. 시안 Main 화면의 순간을 재현한다:
 * 엄마 두물머리 카페(자동 확정), 민준 화장실 → 홍천 휴게소(급한 요청), 서연 횡성 한우 식당(투표 중).
 * Phase 3에서 ScriptedFamilySource가 주행 거리에 맞춰 이 이벤트들을 보내는 방식으로 바뀐다.
 */
object DemoStart {
    /** 두물머리 카페 23분 전 지점 */
    const val START_PROGRESS_M = 20_700.0

    fun vehicle(nowMs: Long) = VehicleState(
        lat = 37.586, lon = 127.212, headingDeg = 60.0,
        speedKph = 80.0, gear = Gear.D, routeProgressM = START_PROGRESS_M, timestampMs = nowMs,
    )

    fun state(nowMs: Long, vehicle: VehicleState): EngineState {
        val cafe = DemoCatalog.place("cafe_dumulmeori")!!
        val rest = DemoCatalog.place("rest_hongcheon")!!
        val hanu = DemoCatalog.place("hanu_hoengseong")!!
        val proposals = listOf(
            ProposalRecord("p1", FamilyMember.MOM, cafe, null, ProposalStatus.ACCEPTED, emptyMap(), nowMs, presented = true),
            ProposalRecord("p2", FamilyMember.MINJUN, rest, null, ProposalStatus.ACCEPTED, emptyMap(), nowMs, urgent = UrgentType.TOILET, presented = true),
            ProposalRecord("p3", FamilyMember.SEOYEON, hanu, "배고파! 점심 여기서 먹자", ProposalStatus.VOTING, emptyMap(), nowMs),
        )
        return EngineState(
            nowMs = nowMs,
            vehicle = vehicle,
            stillSinceMs = if (vehicle.gear == Gear.P && vehicle.speedKph == 0.0) nowMs else null,
            proposals = proposals,
            tokens = mapOf(FamilyMember.MOM to 1, FamilyMember.SEOYEON to 1, FamilyMember.MINJUN to Rules.TOKENS_PER_MEMBER),
            screen = Screen.MAIN,
            suggest = null,
            decideChoices = emptyMap(),
            notice = null,
            devPanelOpen = false,
            nextSeq = 4,
        )
    }
}
