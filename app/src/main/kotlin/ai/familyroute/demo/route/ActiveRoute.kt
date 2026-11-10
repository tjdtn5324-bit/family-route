package ai.familyroute.demo.route

import ai.familyroute.demo.domain.Place
import ai.familyroute.demo.domain.Scenario
import kotlin.math.abs

/** 경로 위 한 점. [key]는 경로를 다시 만들 때 같은 위치를 찾는 데 쓴다. */
data class RoutePoint(
    val pt: Pt,
    val road: String,
    val limitKph: Int,
    /** 기본 경로 인덱스(우회 중이면 이탈 지점 인덱스) */
    val baseIndex: Int,
    val key: String,
)

data class RouteStop(val placeId: String, val distM: Double, val dwellMin: Int)

data class TurnPoint(val distM: Double, val angleDeg: Double, val text: String) {
    val right: Boolean get() = angleDeg > 0
}

/**
 * 지금 따라가는 경로 = 기본 경로 + 확정 경유지의 우회 폴리라인.
 * 거리(m)로 위치·방향·도로·제한속도·예상 소요 시간을 얻는다.
 */
class ActiveRoute(
    val points: List<RoutePoint>,
    val stops: List<RouteStop>,
    val routedPlaceIds: Set<String>,
    private val baseCumM: DoubleArray,
    turnTexts: List<Pair<Pt, String>>,
) {
    val cum: DoubleArray = DoubleArray(points.size).also {
        for (i in 1 until points.size) it[i] = it[i - 1] + points[i - 1].pt.dist(points[i].pt)
    }
    val length: Double get() = cum.last()

    /** 예상 소요 시간 누적(초). 구간 제한속도의 90%로 달린다고 본다. */
    private val cumTimeS: DoubleArray = DoubleArray(points.size).also {
        for (i in 1 until points.size) {
            val v = expectedMps(points[i - 1].limitKph)
            it[i] = it[i - 1] + (cum[i] - cum[i - 1]) / v
        }
    }

    val turns: List<TurnPoint> = findTurns(turnTexts)

    private val keyIndex: Map<String, Int> = points.withIndex().associate { it.value.key to it.index }

    /** d가 속한 구간의 시작 인덱스 */
    fun indexAt(d: Double): Int {
        if (d <= 0) return 0
        if (d >= length) return points.size - 2
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        return lo
    }

    fun pointAt(d: Double): Pt {
        val i = indexAt(d)
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) ((d - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        val a = points[i].pt
        val b = points[i + 1].pt
        return Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    /** 앞뒤 [look]m를 이은 방향이라 꼭짓점에서 튀지 않는다 */
    fun headingAt(d: Double, look: Double = 40.0): Double =
        Geo.bearing(pointAt((d - look).coerceAtLeast(0.0)), pointAt((d + look).coerceAtMost(length)))

    fun roadAt(d: Double) = points[indexAt(d)].road
    fun limitAt(d: Double) = points[indexAt(d)].limitKph

    /** 기본 경로 기준 거리(대본 발생 위치를 정할 때) */
    fun baseKmAt(d: Double): Double {
        val i = indexAt(d)
        val p = points[i]
        if (!p.key.startsWith("b:")) return baseCumM[p.baseIndex] / 1000.0
        val next = points[i + 1]
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) (d - cum[i]) / seg else 0.0
        val b0 = baseCumM[p.baseIndex]
        val b1 = if (next.key.startsWith("b:")) baseCumM[next.baseIndex] else b0
        return (b0 + (b1 - b0) * t) / 1000.0
    }

    fun travelSeconds(from: Double, to: Double): Double {
        if (to <= from) return 0.0
        return timeAt(to) - timeAt(from)
    }

    private fun timeAt(d: Double): Double {
        val i = indexAt(d)
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) ((d - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        return cumTimeS[i] + (cumTimeS[i + 1] - cumTimeS[i]) * t
    }

    /** 다른 경로 위의 같은 위치를 이 경로의 거리로 옮긴다 */
    fun remap(old: ActiveRoute, d: Double): Double {
        val i = old.indexAt(d)
        val idx = keyIndex[old.points[i].key]
        if (idx != null && idx < points.size - 1) {
            val oldSeg = old.cum[i + 1] - old.cum[i]
            val frac = if (oldSeg > 0) (d - old.cum[i]) / oldSeg else 0.0
            return (cum[idx] + frac * (cum[idx + 1] - cum[idx])).coerceIn(0.0, length)
        }
        // 못 찾으면 가장 가까운 점
        val p = old.pointAt(d)
        val near = points.indices.minBy { points[it].pt.dist(p) }
        return cum[near]
    }

    private fun findTurns(texts: List<Pair<Pt, String>>): List<TurnPoint> {
        val window = 250.0
        val threshold = 28.0
        val candidates = mutableListOf<Pair<Double, Double>>()
        var d = window
        while (d < length - window) {
            val a = Geo.bearing(pointAt(d - window), pointAt(d))
            val b = Geo.bearing(pointAt(d), pointAt(d + window))
            val diff = Geo.angleDiff(a, b)
            if (abs(diff) >= threshold) candidates += d to diff
            d += 50.0
        }
        // 이어진 후보는 각도가 가장 큰 한 점으로
        val turns = mutableListOf<TurnPoint>()
        var group = mutableListOf<Pair<Double, Double>>()
        fun flush() {
            if (group.isEmpty()) return
            val best = group.maxBy { abs(it.second) }
            if (turns.isEmpty() || best.first - turns.last().distM > 400) {
                turns += TurnPoint(best.first, best.second, textFor(best.first, best.second, texts))
            }
            group = mutableListOf()
        }
        for (c in candidates) {
            if (group.isNotEmpty() && c.first - group.last().first > 120) flush()
            group += c
        }
        flush()
        // 시나리오에 문구가 있는 분기점은 자동 감지에 걸리지 않아도(완만하게 꺾여도) 안내한다
        for ((pt, text) in texts) {
            val near = points.indices.minBy { points[it].pt.dist(pt) }
            if (points[near].pt.dist(pt) > 300) continue
            val at = cum[near]
            if (turns.any { abs(it.distM - at) < 600 }) continue
            val a = Geo.angleDiff(Geo.bearing(pointAt(at - 400), pointAt(at)), Geo.bearing(pointAt(at), pointAt(at + 400)))
            if (abs(a) >= 20) turns += TurnPoint(at, a, text)
        }
        return turns.sortedBy { it.distM }
    }

    private fun textFor(d: Double, angle: Double, texts: List<Pair<Pt, String>>): String {
        val p = pointAt(d)
        texts.minByOrNull { it.first.dist(p) }?.let { if (it.first.dist(p) < 2500) return it.second }
        val side = if (angle > 0) "우측" else "좌측"
        val ahead = roadAt((d + 300).coerceAtMost(length))
        return "$ahead 방면 $side"
    }

    companion object {
        fun expectedMps(limitKph: Int) = limitKph * 0.9 / 3.6

        /** 기본 경로에 [placeIds]의 우회 폴리라인을 끼워 넣는다(이탈 지점 순, 겹치면 뒤의 것은 뺌) */
        fun build(scenario: Scenario, placeIds: Set<String>): ActiveRoute {
            val base = scenario.base
            val baseCum = DoubleArray(base.size).also {
                for (i in 1 until base.size) it[i] = it[i - 1] + base[i - 1].pt.dist(base[i].pt)
            }
            val detours: List<Place> = scenario.places
                .filter { it.id in placeIds }
                .sortedBy { it.detour.exitIndex }
                .fold(mutableListOf()) { acc, p ->
                    if (acc.isEmpty() || p.detour.exitIndex > acc.last().detour.mergeIndex) acc += p
                    acc
                }
            val pts = mutableListOf<RoutePoint>()
            val stopIdx = mutableListOf<Pair<Place, Int>>()
            var i = 0
            var next = 0
            while (i < base.size) {
                val b = base[i]
                pts += RoutePoint(b.pt, b.road, b.limitKph, i, "b:$i")
                val d = detours.getOrNull(next)
                if (d != null && i == d.detour.exitIndex) {
                    d.detour.via.forEachIndexed { j, v ->
                        if (j == d.detour.stopIndex) stopIdx += d to pts.size
                        pts += RoutePoint(v, b.road, 50, i, "d:${d.id}:$j")
                    }
                    i = d.detour.mergeIndex
                    next++
                    continue
                }
                i++
            }
            val tmp = ActiveRoute(pts, emptyList(), emptySet(), baseCum, emptyList())
            val stops = stopIdx.map { (p, idx) -> RouteStop(p.id, tmp.cum[idx], p.dwellMin) }
            return ActiveRoute(pts, stops, detours.map { it.id }.toSet(), baseCum, scenario.turnTexts)
        }
    }
}
