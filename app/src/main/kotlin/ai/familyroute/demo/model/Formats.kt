package ai.familyroute.demo.model

import java.time.LocalTime

/** 900m, 1.2km, 132km */
fun formatDistance(meters: Int): String = when {
    meters < 1000 -> "${(meters / 10) * 10}m"
    meters < 10_000 -> {
        val tenths = (meters + 50) / 100
        if (tenths % 10 == 0) "${tenths / 10}km" else "${tenths / 10}.${tenths % 10}km"
    }
    else -> "${(meters + 500) / 1000}km"
}

/** 48분, 1시간 48분, 2시간 */
fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}분"
        m == 0 -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}

/** 14:52 */
fun formatClock(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/** ① ② ③ … */
fun circled(n: Int): String = if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "$n."
