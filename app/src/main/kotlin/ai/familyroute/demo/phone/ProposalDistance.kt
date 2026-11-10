package ai.familyroute.demo.phone

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 서버의 경로 미리보기와 같은 서울 시연 출발점. 실제 차량 위치가 아니다. */
private const val DEMO_LATITUDE = 37.5133
private const val DEMO_LONGITUDE = 127.1002

fun demoStraightLineDistance(latitude: Double, longitude: Double): String? {
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    val lat1 = Math.toRadians(DEMO_LATITUDE)
    val lat2 = Math.toRadians(latitude)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(longitude - DEMO_LONGITUDE)
    val a = (sin(deltaLat / 2) * sin(deltaLat / 2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)).coerceIn(0.0, 1.0)
    val meters = 2 * 6371000 * asin(sqrt(a))
    return if (meters < 1000) "약 ${meters.roundToInt()}m" else "약 ${(meters / 1000).roundToInt()}km"
}
