package ai.familyroute.demo

import ai.familyroute.demo.data.ScriptedFamilySource
import ai.familyroute.demo.domain.Gear
import ai.familyroute.demo.domain.TripMath
import ai.familyroute.demo.phone.PhoneNotices
import ai.familyroute.demo.route.ActiveRoute
import ai.familyroute.demo.route.TripSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SimulatorTest {
    private val scenario = TestScenario.scenario
    private val route = ActiveRoute.build(scenario, setOf("cafe_dumulmeori"))

    @Test fun distanceToCoordinate() {
        assertTrue(route.length / 1000 in 180.0..200.0)
        for (i in listOf(0, 10, 300, route.points.size - 2)) {
            assertEquals(0.0, route.pointAt(route.cum[i]).dist(route.points[i].pt), 1e-6)
            val mid = route.pointAt((route.cum[i] + route.cum[i + 1]) / 2)
            val expected = (route.points[i].pt + route.points[i + 1].pt) * 0.5
            assertEquals(0.0, mid.dist(expected), 1e-6)
        }
        // 기본 경로 간격은 2km 이하
        assertTrue(scenario.base.zipWithNext().all { (a, b) -> a.pt.dist(b.pt) <= 2000 })
    }

    @Test fun accelerationStaysWithinLimits() {
        val sim = TripSimulator(route, scenario.startClockMs)
        var last = sim.speedMps
        repeat(20_000) {
            sim.step(0.2)
            val dv = sim.speedMps - last
            // 경유지 도착 순간(멈춤 처리)은 제외
            if (sim.gear == Gear.D) assertTrue("dv=$dv", abs(dv) <= 2.5 * 0.2 + 1e-6)
            last = sim.speedMps
        }
    }

    @Test fun stopsAtWaypointThenRestarts() {
        val sim = TripSimulator(route, scenario.startClockMs)
        val stop = route.stops.first()
        while (sim.gear == Gear.D) sim.step(0.2)
        assertEquals(stop.distM, sim.distM, 0.6)
        assertEquals(0.0, sim.speedMps, 0.0)
        sim.step(stop.dwellMin * 60.0 - 5)
        assertEquals(Gear.P, sim.gear)
        sim.step(10.0)
        assertEquals(Gear.D, sim.gear)
        assertTrue(sim.distM > stop.distM)
    }

    @Test fun etaRemainingNeverIncreasesWhileFollowingPlan() {
        val d = Drive()
        var last = TripMath.remainingSeconds(d.s)
        repeat(400) {
            d.run(1000, mult = 20.0)
            val now = TripMath.remainingSeconds(d.s)
            assertTrue("$now > $last", now <= last + 1.0)
            last = now
        }
    }

    @Test fun routeRebuildKeepsCarPosition() {
        val withHanu = ActiveRoute.build(scenario, setOf("cafe_dumulmeori", "hanu_hoengseong"))
        val at = route.cum[400] + 37.0
        val mapped = withHanu.remap(route, at)
        assertEquals(0.0, withHanu.pointAt(mapped).dist(route.pointAt(at)), 0.5)
        assertTrue(withHanu.length > route.length)
        assertTrue(route.turns.isNotEmpty())
    }

    @Test fun scriptedTripPlaysHandsFreeToTheEnd() {
        val d = Drive()
        val script = ScriptedFamilySource(scenario.script)
        script.direct = { d.engine.onFamilyEvent(it) }
        val messages = mutableListOf<String>()
        var old = d.s
        var guard = 0
        while (!d.sim.finished && guard++ < 20_000) {
            d.run(1000, mult = 60.0) {
                script.check(d.s.route.baseKmAt(d.s.progressM))
                PhoneNotices.diff(old, d.s).forEach { messages += it.text }
                old = d.s
            }
        }
        assertTrue(d.sim.finished)
        assertEquals(scenario.script.size, script.firedCount)
        // 급한 요청 → 홍천 휴게소 확정·방문, 다른 제안은 응답이 없어 다음 정차 때로
        assertTrue("rest_hongcheon" in d.s.visits)
        assertTrue("cafe_dumulmeori" in d.s.visits)
        assertTrue(messages.any { it.contains("다음 정차 때") })
        assertTrue(messages.last().contains("도착"))
    }
}
