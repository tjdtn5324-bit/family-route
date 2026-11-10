package ai.familyroute.demo.data

import ai.familyroute.demo.domain.VehicleState
import kotlinx.coroutines.flow.StateFlow

/** 차량 상태 공급원. SimulatedVehicleSource(Phase 2), PleosVehicleSource(Phase 5)가 구현한다. */
interface VehicleDataSource {
    val state: StateFlow<VehicleState>
}
