package ai.familyroute.demo

import ai.familyroute.demo.route.ActiveRoute
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTurnsTest {
    @Test fun everyNamedJunctionGetsTurnGuidance() {
        val r = ActiveRoute.build(TestScenario.scenario, setOf("cafe_dumulmeori"))
        val texts = r.turns.map { it.text }
        TestScenario.scenario.turnTexts.forEach { (_, text) -> assertTrue("$text 없음: $texts", text in texts) }
        assertTrue(r.turns.zipWithNext().all { (a, b) -> b.distM > a.distM })
    }
}
