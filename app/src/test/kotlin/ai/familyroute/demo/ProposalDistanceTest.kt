package ai.familyroute.demo

import ai.familyroute.demo.phone.demoStraightLineDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProposalDistanceTest {
    @Test fun distanceClearlyUsesDemoOrigin() {
        assertEquals("약 0m", demoStraightLineDistance(37.5133, 127.1002))
        assertEquals("약 318km", demoStraightLineDistance(35.115, 129.042))
    }
    @Test fun invalidCoordinatesAreNotPresentedAsDistance() {
        assertNull(demoStraightLineDistance(Double.NaN, 127.0))
        assertNull(demoStraightLineDistance(91.0, 127.0))
    }
}

