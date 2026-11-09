package ai.familyroute.demo.domain

import ai.familyroute.demo.route.RouteStop
import java.time.LocalTime

/**
 * 시각 계산(시뮬레이션 시계 기준, 출발 10:30).
 * 도착 시각 = 지금 + 남은 경로 예상 주행 시간 + 남은 경유지 체류 시간.
 */
object TripMath {
    fun clock(ms: Long): LocalTime = LocalTime.ofSecondOfDay(((ms / 1000) % 86_400).coerceAtLeast(0))

    fun now(s: EngineState): LocalTime = clock(s.vehicle.timestampMs)

    /** 지금 서 있는 경유지에서 더 머물 시간(초) */
    private fun currentDwellLeftS(s: EngineState): Double {
        val here = s.currentStop ?: return 0.0
        val since = s.parkedSinceSimMs ?: return here.place.dwellMin * 60.0
        return (here.place.dwellMin * 60.0 - (s.vehicle.timestampMs - since) / 1000.0).coerceAtLeast(0.0)
    }

    private fun stopsAhead(s: EngineState, until: Double = Double.MAX_VALUE): List<RouteStop> =
        s.route.stops.filter { it.distM > s.progressM + Rules.AT_STOP_M && it.distM < until }

    fun remainingSeconds(s: EngineState): Double {
        if (s.arrived) return 0.0
        return s.route.travelSeconds(s.progressM, s.route.length) +
            stopsAhead(s).sumOf { it.dwellMin * 60.0 } +
            currentDwellLeftS(s)
    }

    /** [extra] 장소를 더 들르면(우회 + 체류) 바뀌는 도착 시각 */
    fun eta(s: EngineState, extra: List<Place> = emptyList()): LocalTime {
        val extraS = extra.sumOf { (it.detourMin + it.dwellMin) * 60.0 }
        return clock(s.vehicle.timestampMs + ((remainingSeconds(s) + extraS) * 1000).toLong())
    }

    fun arrivalAt(s: EngineState, stop: RouteStop): LocalTime {
        val drive = s.route.travelSeconds(s.progressM, stop.distM)
        val dwell = stopsAhead(s, until = stop.distM).sumOf { it.dwellMin * 60.0 } + currentDwellLeftS(s)
        return clock(s.vehicle.timestampMs + ((drive + dwell) * 1000).toLong())
    }

    fun minutesBetween(a: LocalTime, b: LocalTime): Int {
        var d = b.toSecondOfDay() - a.toSecondOfDay()
        if (d < -12 * 3600) d += 86_400
        return d / 60
    }

    fun remainingMinutes(s: EngineState): Int = (remainingSeconds(s) / 60).toInt()

    fun remainingKm(s: EngineState): Double = ((s.route.length - s.progressM) / 1000).coerceAtLeast(0.0)
}
