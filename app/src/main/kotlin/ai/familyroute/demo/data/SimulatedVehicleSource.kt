package ai.familyroute.demo.data

import ai.familyroute.demo.domain.VehicleState
import ai.familyroute.demo.route.ActiveRoute
import ai.familyroute.demo.route.TripSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SimControl(val multiplier: Int, val paused: Boolean, val hold: Boolean)

/** 시뮬레이터를 10Hz로 돌려 VehicleState를 발행한다. 기본 배속 ×20. */
class SimulatedVehicleSource(
    route: ActiveRoute,
    startClockMs: Long,
    private val scope: CoroutineScope,
) : VehicleDataSource {
    private val sim = TripSimulator(route, startClockMs)
    private val _state = MutableStateFlow(sim.vehicleState())
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val _control = MutableStateFlow(SimControl(multiplier = 20, paused = false, hold = false))
    val control: StateFlow<SimControl> = _control.asStateFlow()

    fun start() {
        scope.launch {
            while (isActive) {
                val c = _control.value
                if (!c.paused) sim.step(TICK_S * c.multiplier)
                _state.value = sim.vehicleState()
                delay((TICK_S * 1000).toLong())
            }
        }
    }

    fun setRoute(route: ActiveRoute) {
        if (route !== sim.route) sim.setRoute(route)
    }

    fun setMultiplier(m: Int) = _control.value.let { _control.value = it.copy(multiplier = m) }
    fun setPaused(p: Boolean) = _control.value.let { _control.value = it.copy(paused = p) }

    fun setHold(h: Boolean) {
        sim.hold = h
        _control.value = _control.value.copy(hold = h)
    }

    fun jumpBeforeNextStop() {
        sim.jumpBeforeNextStop()
        _state.value = sim.vehicleState()
    }

    fun reset(route: ActiveRoute) {
        sim.reset(route)
        _control.value = _control.value.copy(hold = false)
        _state.value = sim.vehicleState()
    }

    companion object {
        const val TICK_S = 0.1
    }
}
