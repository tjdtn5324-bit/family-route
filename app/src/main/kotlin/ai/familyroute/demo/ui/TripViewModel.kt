package ai.familyroute.demo.ui

import ai.familyroute.demo.data.DevPanelFamilySource
import ai.familyroute.demo.data.PhoneFamilySource
import ai.familyroute.demo.data.ScriptedFamilySource
import ai.familyroute.demo.data.SimulatedVehicleSource
import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.FamilyJson
import ai.familyroute.demo.domain.ProposalStatus
import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.domain.Scenario
import ai.familyroute.demo.domain.TripEngine
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.model.UiExtras
import ai.familyroute.demo.model.UiMapper
import ai.familyroute.demo.model.formatClock
import ai.familyroute.demo.model.formatDistance
import ai.familyroute.demo.model.formatDuration
import ai.familyroute.demo.phone.PhoneLinkServer
import ai.familyroute.demo.phone.PhoneMessage
import ai.familyroute.demo.phone.PhoneNotices
import ai.familyroute.demo.route.ActiveRoute
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** 화면에서 보내는 이벤트 */
sealed interface UiAction {
    data object OpenProposals : UiAction
    data object SuggestPrimary : UiAction
    data object SuggestDefer : UiAction
    data object SuggestSkip : UiAction
    data object OpenTimeline : UiAction
    data object Back : UiAction
    data class DecideToggle(val id: String) : UiAction
    data object DecideDepart : UiAction
    data class DevPanel(val open: Boolean) : UiAction
    data class DevHold(val hold: Boolean) : UiAction
    data class DevSpeed(val multiplier: Int) : UiAction
    data class DevPause(val paused: Boolean) : UiAction
    data object DevJumpToNextStop : UiAction
    data class DevInject(val event: FamilyEvent) : UiAction
    data object DevReset : UiAction
}

@Serializable
private data class PhoneStop(val memberId: String, val name: String, val detail: String, val status: String)

@Serializable
private data class PhoneBudget(val memberId: String, val minutes: Int)

@Serializable
private data class PhonePending(
    val id: String,
    val memberId: String,
    val name: String,
    val message: String?,
    val votes: Map<String, String>,
    val yes: Int,
    val need: Int,
    val detourMin: Int,
)

@Serializable
private data class PhoneCandidate(val id: String, val name: String, val category: String, val detourMin: Int, val dwellMin: Int, val kmAhead: Int)

/** 가족 폰 화면이 받는 요약 */
@Serializable
private data class PhoneSnapshot(
    val destination: String,
    val eta: String,
    val remaining: String,
    val distance: String,
    val budgetUsed: Int,
    val budgetTotal: Int,
    val budget: List<PhoneBudget>,
    val stops: List<PhoneStop>,
    val pending: List<PhonePending>,
    val candidates: List<PhoneCandidate>,
    val recent: List<PhoneMessage>,
)

/** 수동 주입: 엔진·시뮬레이터·가족 이벤트 공급원·폰 연결을 여기서 만들어 잇는다(DI 프레임워크 없음). */
class TripViewModel(app: Application) : AndroidViewModel(app) {
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    val scenario: Scenario = Scenario.parse(app.assets.open("scenario.json").bufferedReader().use { it.readText() })
    private fun startRoute() = ActiveRoute.build(scenario, scenario.initialAccepted.map { it.placeId }.toSet())

    private val sim = SimulatedVehicleSource(startRoute(), scenario.startClockMs, viewModelScope)
    private val engine = TripEngine(scenario, clock(), sim.state.value)
    private val devFamily = DevPanelFamilySource()
    private val script = ScriptedFamilySource(scenario.script)
    private val phoneFamily = PhoneFamilySource()
    private val scriptFired = MutableStateFlow(0)

    private val recent = ArrayDeque<PhoneMessage>()
    private val phoneCode = (1..6).map { "ABCDEFGHJKMNPQRSTUVWXYZ23456789"[Random.nextInt(31)] }.joinToString("")
    private val phone = PhoneLinkServer(
        assets = app.assets,
        scope = viewModelScope,
        code = phoneCode,
        onEvent = phoneFamily::inject,
        snapshot = { snapshotJson() },
    )
    private val phoneUrl: String
        get() = "http://${phone.localAddress() ?: "기기IP"}:${phone.port}/?k=$phoneCode"

    val ui: StateFlow<TripUiState> = combine(engine.state, sim.control, phone.connected, scriptFired) { s, c, n, fired ->
        UiMapper.map(s, UiExtras(c, phoneUrl, n, fired, scenario.script.size))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiMapper.map(engine.state.value))

    init {
        viewModelScope.launch {
            sim.state.collect { v ->
                engine.onVehicle(v)
                script.check(engine.state.value.route.baseKmAt(v.routeProgressM))
                scriptFired.value = script.firedCount
            }
        }
        viewModelScope.launch { merge(devFamily.events, script.events, phoneFamily.events).collect(engine::onFamilyEvent) }
        // 확정 경유지가 바뀌면 엔진이 만든 새 경로(우회 포함)를 시뮬레이터가 따라간다
        viewModelScope.launch { engine.state.map { it.route }.distinctUntilChanged().collect(sim::setRoute) }
        viewModelScope.launch {
            var old = engine.state.value
            engine.state.collect { new ->
                val msgs = PhoneNotices.diff(old, new)
                msgs.forEach { m ->
                    recent.addFirst(m)
                    while (recent.size > 20) recent.removeLast()
                    phone.broadcast("msg", FamilyJson.encodeToString(PhoneMessage.serializer(), m))
                }
                if (msgs.isNotEmpty() || old.proposals != new.proposals) phone.broadcast("state", snapshotJson())
                old = new
            }
        }
        viewModelScope.launch {
            while (true) {
                phone.broadcast("state", snapshotJson())
                delay(2_000)
            }
        }
        viewModelScope.launch {
            while (true) {
                engine.tick(clock())
                delay(100)
            }
        }
        sim.start()
        phone.start()
    }

    private fun snapshotJson(): String {
        val u = ui.value
        val s = engine.state.value
        val majority = FamilyMember.entries.size / 2 + 1
        val taken = s.proposals.filter { it.status != ProposalStatus.SKIPPED && it.status != ProposalStatus.RETURN_TRIP }.map { it.place.id }.toSet()
        val snap = PhoneSnapshot(
            destination = u.destinationName,
            eta = formatClock(u.eta),
            remaining = formatDuration(u.remainingMinutes),
            distance = formatDistance(u.remainingMeters),
            budgetUsed = u.budget.usedMinutes,
            budgetTotal = u.budget.totalMinutes,
            budget = u.budget.usage.map { PhoneBudget(it.member.id, it.minutes) },
            stops = u.stops.map { PhoneStop(it.member.id, it.placeName, it.detail, it.status.name) },
            pending = s.undecided.map { p ->
                PhonePending(
                    p.id, p.member.id, p.place.name, p.message,
                    p.votes.mapKeys { it.key.id }.mapValues { it.value.name },
                    p.yesCount, majority, p.place.detourMin,
                )
            },
            candidates = scenario.places
                .filter { it.id !in taken && (it.nearDestination || !s.isUnreachable(it)) && s.kmTo(it) > -1 }
                .sortedBy { s.kmTo(it) }
                .map { PhoneCandidate(it.id, it.name, it.category, it.detourMin, it.dwellMin, s.kmTo(it).toInt().coerceAtLeast(0)) },
            recent = recent.toList(),
        )
        return FamilyJson.encodeToString(PhoneSnapshot.serializer(), snap)
    }

    fun onAction(a: UiAction) {
        when (a) {
            UiAction.OpenProposals -> engine.openProposals()
            UiAction.SuggestPrimary -> engine.suggestPrimary()
            UiAction.SuggestDefer -> engine.suggestDefer()
            UiAction.SuggestSkip -> engine.suggestSkip()
            UiAction.OpenTimeline -> engine.openTimeline()
            UiAction.Back -> if (engine.state.value.devPanelOpen) engine.setDevPanel(false) else engine.backToMain()
            is UiAction.DecideToggle -> engine.decideToggle(a.id)
            UiAction.DecideDepart -> engine.decideDepart()
            is UiAction.DevPanel -> engine.setDevPanel(a.open)
            is UiAction.DevHold -> sim.setHold(a.hold)
            is UiAction.DevSpeed -> sim.setMultiplier(a.multiplier)
            is UiAction.DevPause -> sim.setPaused(a.paused)
            UiAction.DevJumpToNextStop -> sim.jumpBeforeNextStop()
            is UiAction.DevInject -> devFamily.inject(a.event)
            UiAction.DevReset -> {
                sim.reset(startRoute())
                engine.reset(clock(), sim.state.value)
                script.reset()
                scriptFired.value = 0
                recent.clear()
            }
        }
    }

    override fun onCleared() {
        phone.stop()
        phone.close()
    }
}
