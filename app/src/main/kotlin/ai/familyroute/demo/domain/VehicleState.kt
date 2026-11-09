package ai.familyroute.demo.domain

enum class Gear { P, D }

/** 차량 상태. 시뮬레이터(Phase 2)나 Pleos Vehicle SDK(Phase 5)가 채운다. */
data class VehicleState(
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
    val speedKph: Double,
    val gear: Gear,
    val routeProgressM: Double,
    val timestampMs: Long,
)
