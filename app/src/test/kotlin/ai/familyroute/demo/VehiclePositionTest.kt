package ai.familyroute.demo

import ai.familyroute.demo.phone.VehiclePosition
import ai.familyroute.demo.phone.Place
import org.junit.Assert.*
import org.junit.Test

class VehiclePositionTest {
    private val now = 1_800_000_000_000L
    @Test fun freshVehicleLocationIsUsable() {
        assertTrue(VehiclePosition(35.1152, 129.0415, now - 1000).isUsable(now))
    }
    @Test fun missingStaleOrInvalidLocationsAreNotUsedAsVehicleOrigins() {
        listOf(
            VehiclePosition(35.0, 129.0, 0),
            VehiclePosition(35.0, 129.0, now - 300_001),
            VehiclePosition(35.0, 129.0, now + 20_000),
            VehiclePosition(91.0, 129.0, now),
            VehiclePosition(35.0, Double.NaN, now),
            VehiclePosition(0.0, 0.0, now)
        ).forEach { assertFalse(it.isUsable(now)) }
    }
    @Test fun recommendationDistanceIsStraightLineAndNotRouteLength() {
        val position = VehiclePosition(37.5665,126.978,now)
        assertEquals(0.0,position.distanceKmTo(Place("현재 위치","",37.5665,126.978)),0.001)
        assertTrue(position.distanceKmTo(Place("부산역","",35.1152,129.0415)) in 320.0..330.0)
    }
}
