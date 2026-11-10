package ai.familyroute.demo.route

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 평면 좌표(미터). 화면처럼 x는 동쪽, y는 **남쪽**이 +다(북쪽이 화면 위).
 * 위경도 ↔ 평면 변환은 tools/gen_scenario.py와 같은 기준(위도 37.6, 경도 127.0)을 쓴다.
 */
data class Pt(val x: Double, val y: Double) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun times(k: Double) = Pt(x * k, y * k)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

object Geo {
    private const val LAT0 = 37.6
    private const val LON0 = 127.0
    private val kx = cos(Math.toRadians(LAT0)) * 111_320.0
    private const val KY = 110_574.0

    fun toPt(lat: Double, lon: Double) = Pt((lon - LON0) * kx, -(lat - LAT0) * KY)
    fun lat(p: Pt) = LAT0 - p.y / KY
    fun lon(p: Pt) = LON0 + p.x / kx

    /** 북쪽 기준 시계 방향 각도(도) */
    fun bearing(from: Pt, to: Pt): Double {
        val deg = Math.toDegrees(atan2(to.x - from.x, -(to.y - from.y)))
        return (deg + 360.0) % 360.0
    }

    /** -180 ~ 180 */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }
}
