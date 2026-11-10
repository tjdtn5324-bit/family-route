package ai.familyroute.demo.map

import ai.familyroute.demo.domain.Scenario
import ai.familyroute.demo.route.Pt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class DecorKind { RIVER, MAJOR, MINOR }

/** 바깥 사각형(가려진 것은 안 그리려고) */
data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun intersects(o: Bounds) = minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
}

class DecorLine(val kind: DecorKind, val pts: List<Pt>) {
    val bounds = Bounds(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
}

/**
 * 경로 주변 배경(가상의 강·도로망). 시드 고정이라 매번 같다. 실제 지도 데이터가 아니다.
 */
object WorldDecor {
    fun generate(scenario: Scenario): List<DecorLine> {
        val rnd = Random(scenario.seed)
        val base = scenario.base.map { it.pt }
        val cum = DoubleArray(base.size).also { for (i in 1 until base.size) it[i] = it[i - 1] + base[i - 1].dist(base[i]) }
        val total = cum.last()
        fun at(d: Double): Pair<Pt, Pt> {
            val i = cum.indexOfFirst { it >= d }.let { if (it <= 0) 1 else it }.coerceAtMost(base.size - 1)
            val a = base[i - 1]
            val b = base[i]
            val len = a.dist(b).coerceAtLeast(1.0)
            val t = ((d - cum[i - 1]) / len).coerceIn(0.0, 1.0)
            val dir = Pt((b.x - a.x) / len, (b.y - a.y) / len)
            return Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) to dir
        }
        val out = mutableListOf<DecorLine>()

        // 강: 경로를 따라 한쪽으로 떨어져 굽이치는 선(서울~양평 구간, 횡성 근처)
        listOf(Triple(0.0, 34_000.0, 1100.0), Triple(86_000.0, 104_000.0, -900.0), Triple(150_000.0, 162_000.0, 800.0))
            .forEach { (from, to, side) ->
                val pts = mutableListOf<Pt>()
                var d = from
                val phase = rnd.nextDouble(0.0, 6.28)
                while (d <= to) {
                    val (p, dir) = at(d)
                    val n = Pt(-dir.y, dir.x)
                    val off = side + 420 * sin(d / 2600.0 + phase)
                    pts += p + n * off
                    d += 250.0
                }
                out += DecorLine(DecorKind.RIVER, pts)
            }

        // 경로를 가로지르는 길
        var d = 600.0
        while (d < total) {
            val (p, dir) = at(d)
            val ang = rnd.nextDouble(-0.5, 0.5)
            val n = Pt(-dir.y, dir.x)
            val v = Pt(n.x * cos(ang) - n.y * sin(ang), n.x * sin(ang) + n.y * cos(ang))
            val l1 = rnd.nextDouble(1200.0, 5000.0)
            val l2 = rnd.nextDouble(1200.0, 5000.0)
            val bend = rnd.nextDouble(-0.25, 0.25)
            val mid1 = p + v * (l1 * 0.5) + dir * (l1 * bend)
            out += DecorLine(
                if (rnd.nextDouble() < 0.3) DecorKind.MAJOR else DecorKind.MINOR,
                listOf(p + v * (-l2), p, mid1, p + v * l1 + dir * (l1 * bend * 1.6)),
            )
            d += rnd.nextDouble(900.0, 2200.0)
        }

        // 경로와 나란한 옆길
        d = 2000.0
        while (d < total) {
            val len = rnd.nextDouble(2500.0, 7000.0)
            val side = (if (rnd.nextBoolean()) 1 else -1) * rnd.nextDouble(450.0, 1400.0)
            val pts = mutableListOf<Pt>()
            var k = d
            while (k <= (d + len).coerceAtMost(total)) {
                val (p, dir) = at(k)
                pts += p + Pt(-dir.y, dir.x) * (side + 120 * sin(k / 900.0))
                k += 300.0
            }
            if (pts.size > 1) out += DecorLine(DecorKind.MINOR, pts)
            d += rnd.nextDouble(4000.0, 9000.0)
        }

        // 출발·도착 도시 격자
        listOf(0.0 to 9000.0, total - 6000.0 to total).forEach { (from, to) ->
            val (c, dir) = at((from + to) / 2)
            val n = Pt(-dir.y, dir.x)
            for (k in -6..6) {
                val off = k * 650.0 + rnd.nextDouble(-120.0, 120.0)
                out += DecorLine(DecorKind.MINOR, listOf(c + n * off + dir * -5000.0, c + n * off + dir * 5000.0))
                out += DecorLine(DecorKind.MINOR, listOf(c + dir * off + n * -4500.0, c + dir * off + n * 4500.0))
            }
        }
        return out
    }
}
