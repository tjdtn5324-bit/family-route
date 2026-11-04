package ai.familyroute.demo.domain

import java.time.LocalTime

/**
 * 시각 계산. Phase 2 시뮬레이터가 붙기 전까지는 평균 속도로 단순 계산한다.
 * 도착 시각 = 출발 + 주행 시간 + 확정 경유지의 (우회 + 체류) 합.
 */
object TripPlan {
    val START: LocalTime = LocalTime.of(10, 30)

    private fun driveMinAt(km: Double): Double = km / DemoCatalog.TOTAL_KM * DemoCatalog.DRIVE_MIN

    private fun stopCost(p: ProposalRecord): Int = p.place.detourMin + p.place.dwellMin

    /** 확정 경유지들 기준 목적지 도착 시각 */
    fun eta(s: EngineState, extra: List<Place> = emptyList()): LocalTime {
        val stops = s.accepted.sumOf { stopCost(it) } + extra.sumOf { it.detourMin + it.dwellMin }
        return START.plusMinutes((DemoCatalog.DRIVE_MIN + stops).toLong())
    }

    /** 지금 시각(시뮬레이션 시계) */
    fun now(s: EngineState): LocalTime {
        val passed = s.accepted.filter { it.place.routeKm < s.progressKm - Rules.AT_STOP_KM }.sumOf { stopCost(it) }
        return START.plusMinutes((driveMinAt(s.progressKm) + passed).toLong())
    }

    /** 경유지 도착 예정 시각 */
    fun arrivalAt(s: EngineState, place: Place): LocalTime {
        val before = s.accepted.filter { it.place.routeKm < place.routeKm }.sumOf { stopCost(it) }
        return START.plusMinutes((driveMinAt(place.routeKm) + before).toLong())
    }

    fun minutesBetween(a: LocalTime, b: LocalTime): Int = ((b.toSecondOfDay() - a.toSecondOfDay()) / 60)

    fun remainingMinutes(s: EngineState): Int = minutesBetween(now(s), eta(s)).coerceAtLeast(0)

    fun remainingKm(s: EngineState): Double = (DemoCatalog.TOTAL_KM - s.progressKm).coerceAtLeast(0.0)
}
