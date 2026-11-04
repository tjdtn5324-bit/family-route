package ai.familyroute.demo.ui

import ai.familyroute.demo.data.DevPanelFamilySource
import ai.familyroute.demo.data.ManualVehicleSource
import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.Gear
import ai.familyroute.demo.domain.Rules
import ai.familyroute.demo.domain.TripEngine
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.model.UiMapper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    data class DevGear(val gear: Gear) : UiAction
    data object DevJumpToNextStop : UiAction
    data class DevInject(val event: FamilyEvent) : UiAction
    data object DevReset : UiAction
}

/** 수동 주입: 엔진·공급원을 여기서 만들어 연결한다(DI 프레임워크 없음). */
class TripViewModel : ViewModel() {
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val vehicle = ManualVehicleSource(clock)
    private val devFamily = DevPanelFamilySource()
    private val engine = TripEngine(clock(), vehicle.state.value)

    val ui: StateFlow<TripUiState> = engine.state
        .map(UiMapper::map)
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiMapper.map(engine.state.value))

    init {
        viewModelScope.launch { vehicle.state.collect(engine::onVehicle) }
        viewModelScope.launch { devFamily.events.collect(engine::onFamilyEvent) }
        viewModelScope.launch {
            while (true) {
                engine.tick(clock())
                delay(100)
            }
        }
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
            is UiAction.DevGear -> vehicle.setGear(a.gear)
            UiAction.DevJumpToNextStop -> {
                // 임시: 다음 확정 경유지 위치로 옮긴다. Phase 2에서는 "직전"으로 옮겨 시뮬레이터가 들어가게 한다.
                val s = engine.state.value
                val next = s.accepted.firstOrNull { it.place.routeKm > s.progressKm + Rules.AT_STOP_KM }
                next?.let { vehicle.jumpTo(it.place.routeKm * 1000) }
            }
            is UiAction.DevInject -> devFamily.inject(a.event)
            UiAction.DevReset -> {
                vehicle.reset()
                engine.reset(clock(), vehicle.state.value)
            }
        }
    }
}
