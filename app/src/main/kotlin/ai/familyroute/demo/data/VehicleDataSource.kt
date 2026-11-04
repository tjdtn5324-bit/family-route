package ai.familyroute.demo.data

import ai.familyroute.demo.domain.DemoStart
import ai.familyroute.demo.domain.Gear
import ai.familyroute.demo.domain.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 차량 상태 공급원. SimulatedVehicleSource(Phase 2), PleosVehicleSource(Phase 5)가 구현한다. */
interface VehicleDataSource {
    val state: StateFlow<VehicleState>
}

/**
 * 임시: 개발자 패널에서 손으로 기어·위치를 바꾸는 공급원.
 * Phase 2에서 SimulatedVehicleSource로 바뀐다.
 */
class ManualVehicleSource(private val clock: () -> Long) : VehicleDataSource {
    private val _state = MutableStateFlow(DemoStart.vehicle(clock()))
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    fun setGear(gear: Gear) {
        _state.value = _state.value.copy(
            gear = gear,
            speedKph = if (gear == Gear.D) 80.0 else 0.0,
            timestampMs = clock(),
        )
    }

    fun jumpTo(progressM: Double) {
        _state.value = _state.value.copy(routeProgressM = progressM, timestampMs = clock())
    }

    fun reset() {
        _state.value = DemoStart.vehicle(clock())
    }
}
