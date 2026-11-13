package ai.familyroute.demo.data

import ai.familyroute.demo.domain.VehicleState
import kotlinx.coroutines.flow.StateFlow

/** 독립 시뮬레이션 엔진용 차량 상태 공급원. 실제 앱의 Pleos SDK 연결은 phone 패키지에서 관리한다. */
interface VehicleDataSource {
    val state: StateFlow<VehicleState>
}
