package ai.familyroute.demo.domain

enum class Gear { P, D }

/** 독립 시뮬레이션 엔진의 차량 상태. 실제 앱의 SDK 위치·문 상태와 AAOS 기어/UX 제한은 별도로 수신한다. */
data class VehicleState(
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
    val speedKph: Double,
    val gear: Gear,
    val routeProgressM: Double,
    val timestampMs: Long,
)
