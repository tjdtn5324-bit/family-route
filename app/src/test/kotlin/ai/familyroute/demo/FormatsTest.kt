package ai.familyroute.demo

import ai.familyroute.demo.model.formatClock
import ai.familyroute.demo.model.formatDistance
import ai.familyroute.demo.model.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import ai.familyroute.demo.model.eulReul
import ai.familyroute.demo.model.eunNeun
import java.time.LocalTime

class FormatsTest {
    @Test fun distance() {
        assertEquals("900m", formatDistance(900))
        assertEquals("1.2km", formatDistance(1200))
        assertEquals("2km", formatDistance(1990))
        assertEquals("132km", formatDistance(132_000))
    }

    @Test fun duration() {
        assertEquals("48분", formatDuration(48))
        assertEquals("1시간 48분", formatDuration(108))
        assertEquals("2시간", formatDuration(120))
    }

    @Test fun clock() {
        assertEquals("14:52", formatClock(LocalTime.of(14, 52)))
        assertEquals("09:05", formatClock(LocalTime.of(9, 5)))
    }
}

class KoreanTest {
    @Test fun particles() {
        org.junit.Assert.assertEquals("횡성 휴게소를", "횡성 휴게소".eulReul())
        org.junit.Assert.assertEquals("한우 식당을", "한우 식당".eulReul())
        org.junit.Assert.assertEquals("두물머리 카페는", "두물머리 카페".eunNeun())
        org.junit.Assert.assertEquals("서연이", ai.familyroute.demo.model.FamilyMember.SEOYEON.subject)
        org.junit.Assert.assertEquals("엄마가", ai.familyroute.demo.model.FamilyMember.MOM.subject)
    }
}
