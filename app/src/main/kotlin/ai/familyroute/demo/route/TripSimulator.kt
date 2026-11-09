package ai.familyroute.demo.route

import ai.familyroute.demo.domain.Gear
import ai.familyroute.demo.domain.VehicleState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 더미 주행 물리. 시간(초)을 넣으면 경로를 따라 움직인다. 순수 Kotlin이라 단위 테스트가 쉽다.
 * - 구간 제한속도까지 ±2.5 m/s²로 가감속
 * - 큰 회전 앞에서 감속, 경유지 앞에서 서서히 멈춤 → 기어 P → 체류 → 재출발
 */
class TripSimulator(route: ActiveRoute, private val startClockMs: Long) {
    var route: ActiveRoute = route
        private set
    var distM = 0.0
        private set
    var speedMps = 0.0
        private set
    var clockMs = startClockMs
        private set
    var gear = Gear.D
        private set
    var finished = false
        private set

    /** 운전자가 세운 상태(개발자 패널 "정차") */
    var hold = false

    private var dwellUntilMs: Long? = null
    private val visited = mutableSetOf<String>()

    fun setRoute(newRoute: ActiveRoute) {
        distM = newRoute.remap(route, distM)
        route = newRoute
    }

    fun reset(newRoute: ActiveRoute) {
        route = newRoute
        distM = 0.0
        speedMps = 0.0
        clockMs = startClockMs
        gear = Gear.D
        finished = false
        hold = false
        dwellUntilMs = null
        visited.clear()
    }

    /** 다음 경유지 [beforeM] 앞으로 옮긴다 */
    fun jumpBeforeNextStop(beforeM: Double = 300.0) {
        val stop = route.stops.firstOrNull { it.placeId !in visited && it.distM > distM + 1 } ?: return
        distM = (stop.distM - beforeM).coerceAtLeast(distM)
        speedMps = min(speedMps.coerceAtLeast(40 / 3.6), sqrt(2 * DECEL * beforeM * 0.8))
        dwellUntilMs = null
        gear = Gear.D
    }

    fun step(dtS: Double) {
        var left = dtS
        while (left > 1e-9) {
            val dt = min(SUBSTEP_S, left)
            tick(dt)
            left -= dt
        }
    }

    private fun tick(dt: Double) {
        clockMs += (dt * 1000).toLong()
        if (finished) return
        dwellUntilMs?.let { until ->
            if (clockMs < until) return
            dwellUntilMs = null
            if (!hold) gear = Gear.D
        }
        if (hold) {
            speedMps = max(0.0, speedMps - DECEL * dt)
            distM = min(route.length, distM + speedMps * dt)
            if (speedMps == 0.0) gear = Gear.P
            return
        }
        if (gear == Gear.P) gear = Gear.D

        val target = targetSpeed()
        speedMps = if (speedMps < target) min(target, speedMps + ACCEL * dt) else max(target, speedMps - DECEL * dt)
        distM += speedMps * dt

        val stop = nextStop()
        if (stop != null && distM >= stop.distM - ARRIVE_M) {
            distM = stop.distM
            speedMps = 0.0
            gear = Gear.P
            visited += stop.placeId
            dwellUntilMs = clockMs + stop.dwellMin * 60_000L
        }
        if (distM >= route.length - ARRIVE_M) {
            distM = route.length
            speedMps = 0.0
            gear = Gear.P
            finished = true
        }
    }

    private fun nextStop(): RouteStop? = route.stops.firstOrNull { it.placeId !in visited && it.distM >= distM - ARRIVE_M }

    /** 지금 낼 수 있는 속도: 제한속도, 앞쪽 회전·경유지·도착·제한속도 변화에서 멈추거나 줄일 수 있는 속도 중 가장 낮은 값 */
    private fun targetSpeed(): Double {
        var v = route.limitAt(distM) / 3.6
        fun brakeTo(vEnd: Double, at: Double) {
            val gap = at - distM
            if (gap < 0 || gap > LOOKAHEAD_M) return
            v = min(v, sqrt(vEnd * vEnd + 2 * DECEL * max(0.0, gap - 2.0)))
        }
        for (t in route.turns) {
            if (t.distM < distM - 30) continue
            if (t.distM > distM + LOOKAHEAD_M) break
            val a = abs(t.angleDeg)
            val vt = when {
                a > 70 -> 30.0
                a > 45 -> 45.0
                else -> 60.0
            } / 3.6
            if (t.distM < distM) v = min(v, vt) else brakeTo(vt, t.distM)
        }
        nextStop()?.let { brakeTo(0.0, it.distM) }
        brakeTo(0.0, route.length)
        // 제한속도가 낮아지는 곳
        var probe = distM
        while (probe < distM + LOOKAHEAD_M && probe < route.length) {
            val lim = route.limitAt(probe) / 3.6
            if (lim < v) brakeTo(lim, probe)
            probe += 200.0
        }
        // 멈출 곳 바로 앞에서 끝없이 느려지지 않게
        val stopGap = min(nextStop()?.distM ?: Double.MAX_VALUE, route.length) - distM
        if (stopGap > ARRIVE_M) v = max(v, CREEP_MPS)
        return v
    }

    fun vehicleState(): VehicleState {
        val p = route.pointAt(distM)
        return VehicleState(
            lat = Geo.lat(p),
            lon = Geo.lon(p),
            headingDeg = route.headingAt(distM),
            speedKph = speedMps * 3.6,
            gear = gear,
            routeProgressM = distM,
            timestampMs = clockMs,
        )
    }

    companion object {
        const val ACCEL = 2.5
        const val DECEL = 2.5
        const val SUBSTEP_S = 0.2
        const val LOOKAHEAD_M = 2000.0
        const val ARRIVE_M = 0.5
        const val CREEP_MPS = 1.5
    }
}
