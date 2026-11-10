package ai.familyroute.demo

import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.Scenario
import ai.familyroute.demo.domain.TripEngine
import ai.familyroute.demo.domain.VoteChoice
import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.route.ActiveRoute
import ai.familyroute.demo.route.TripSimulator
import java.io.File

object TestScenario {
    val scenario: Scenario by lazy { Scenario.parse(File("src/main/assets/scenario.json").readText()) }
}

/** 엔진 + 시뮬레이터를 실제 앱처럼 잇는 테스트 도우미. 시간은 손으로 돌린다. */
class Drive {
    val scenario = TestScenario.scenario
    var now = 1_000_000L
    val sim = TripSimulator(ActiveRoute.build(scenario, scenario.initialAccepted.map { it.placeId }.toSet()), scenario.startClockMs)
    val engine = TripEngine(scenario, now, sim.vehicleState())
    val s get() = engine.state.value

    /** 실제 시간 [realMs] 동안, 배속 [mult]로 */
    fun run(realMs: Long, mult: Double = 20.0, onTick: () -> Unit = {}) {
        var left = realMs
        while (left > 0) {
            val dt = minOf(100L, left)
            now += dt
            sim.setRoute(s.route)
            sim.step(dt / 1000.0 * mult)
            engine.onVehicle(sim.vehicleState())
            engine.tick(now)
            onTick()
            left -= dt
        }
    }

    fun propose(m: FamilyMember, placeId: String) = engine.onFamilyEvent(FamilyEvent.Proposal(m.id, placeId))
    fun vote(m: FamilyMember, id: String = "@last", yes: Boolean = true) =
        engine.onFamilyEvent(FamilyEvent.Vote(m.id, id, if (yes) VoteChoice.YES else VoteChoice.NO))
}
