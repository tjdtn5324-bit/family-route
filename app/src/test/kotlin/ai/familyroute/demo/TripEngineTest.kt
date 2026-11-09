package ai.familyroute.demo

import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.FamilyJson
import ai.familyroute.demo.domain.ProposalStatus
import ai.familyroute.demo.domain.Rules
import ai.familyroute.demo.domain.Screen
import ai.familyroute.demo.domain.TripMath
import ai.familyroute.demo.domain.UrgentType
import ai.familyroute.demo.domain.VoteChoice
import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.model.UiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {
    private val d = Drive()
    private val s get() = d.s

    @Test fun startsWithAgreedCafe() {
        assertEquals(8, s.budgetUsedMin)
        assertEquals(listOf("cafe_dumulmeori"), s.route.stops.map { it.placeId })
        assertEquals("두물머리 카페", UiMapper.map(s).stops.first().placeName)
    }

    @Test fun proposalShowsOverlayWhileVotingContinues() {
        d.run(500)
        d.propose(FamilyMember.SEOYEON, "hanu_hoengseong")
        val id = s.proposals.last().id
        assertEquals(ProposalStatus.VOTING, s.proposal(id)!!.status)
        d.vote(FamilyMember.MOM)
        assertEquals(ProposalStatus.VOTING, s.proposal(id)!!.status)
        assertEquals(id, s.suggest?.proposalId)
    }

    @Test fun noAnswerFor10SecondsDefersToNextStop() {
        d.run(500)
        d.propose(FamilyMember.SEOYEON, "hanu_hoengseong"); d.vote(FamilyMember.MOM)
        d.run(Rules.SUGGEST_TIMEOUT_MS - 200)
        assertNotNull(s.suggest)
        d.run(300)
        assertNull(s.suggest)
        assertEquals(ProposalStatus.NEXT_STOP, s.proposals.last().status)
    }

    @Test fun acceptReplacesRouteWithDetourAndAddsDwell() {
        d.run(500)
        val before = TripMath.remainingSeconds(s)
        d.propose(FamilyMember.SEOYEON, "hanu_hoengseong"); d.vote(FamilyMember.MOM)
        d.engine.suggestPrimary()
        assertEquals(ProposalStatus.ACCEPTED, s.proposals.last().status)
        assertEquals(20, s.budgetUsedMin)
        assertTrue("hanu_hoengseong" in s.route.stops.map { it.placeId })
        assertTrue(TripMath.remainingSeconds(s) - before >= 50 * 60)
    }

    @Test fun overBudgetRecommendsReturnTripAndNearDestinationAfterArrival() {
        d.run(500)
        d.propose(FamilyMember.MOM, "sheep_daegwallyeong"); d.vote(FamilyMember.SEOYEON)
        d.engine.suggestPrimary()
        assertEquals(ProposalStatus.RETURN_TRIP, s.proposals.last().status)
        d.propose(FamilyMember.MINJUN, "market_gangneung"); d.vote(FamilyMember.SEOYEON)
        d.run(200)
        d.engine.suggestPrimary()
        assertEquals(ProposalStatus.AFTER_ARRIVAL, s.proposals.last().status)
        assertEquals(8, s.budgetUsedMin)
    }

    @Test fun familyMemberCanMakeMultipleProposals() {
        d.propose(FamilyMember.SEOYEON, "hanu_hoengseong")
        d.propose(FamilyMember.SEOYEON, "rest_pyeongchang")
        val count = s.proposals.size
        d.propose(FamilyMember.SEOYEON, "market_gangneung")
        assertEquals(count + 1, s.proposals.size)
    }

    @Test fun urgentRequestAutoConfirmsNearestRestArea() {
        d.run(500)
        d.engine.onFamilyEvent(FamilyEvent.UrgentRequest(FamilyMember.MINJUN.id, UrgentType.TOILET))
        val added = s.proposals.last()
        assertEquals(ProposalStatus.ACCEPTED, added.status)
        assertEquals("rest_hongcheon", added.place.id)
        assertNull(s.suggest)
        assertTrue("rest_hongcheon" in s.route.stops.map { it.placeId })
    }

    @Test fun onlyOneOverlayAtATime() {
        d.run(500)
        d.propose(FamilyMember.SEOYEON, "hanu_hoengseong"); d.vote(FamilyMember.MOM)
        val first = s.proposals.last().id
        d.propose(FamilyMember.MOM, "sheep_daegwallyeong"); d.vote(FamilyMember.SEOYEON)
        val second = s.proposals.last().id
        assertEquals(first, s.suggest?.proposalId)
        d.engine.suggestDefer()
        d.run(100)
        assertEquals(second, s.suggest?.proposalId)
    }

    @Test fun decideOpensOnlyWhenParkedAndClosesOnDeparture() {
        d.run(500)
        d.propose(FamilyMember.SEOYEON, "hanu_hoengseong"); d.vote(FamilyMember.MOM)
        d.engine.suggestDefer()
        d.sim.hold = true
        d.run(4000) // 멈추고 기어 P
        assertFalse(s.isDriving)
        d.run(Rules.PARK_CONFIRM_MS)
        assertTrue(s.isParked)
        d.engine.openProposals()
        assertEquals(Screen.DECIDE, s.screen)
        d.engine.decideToggle(s.proposals.last().id)
        d.sim.hold = false
        d.run(500)
        assertEquals(Screen.MAIN, s.screen)
        assertEquals(ProposalStatus.ACCEPTED, s.proposals.last().status)
    }

    @Test fun passedExitTurnsAcceptIntoReturnTrip() {
        val exitKm = d.scenario.place("cafe_dumulmeori")!!.routeKm
        // 카페는 이미 확정. 경로상 첫 경유지인 홍천 휴게소 이탈 지점을 지난 뒤 들르자고 하면 귀갓길
        val hong = d.scenario.place("rest_hongcheon")!!
        while (s.route.baseKmAt(s.progressM) < hong.routeKm + 2) d.run(1000, mult = 60.0)
        assertTrue(s.progressM / 1000 > exitKm)
        d.propose(FamilyMember.SEOYEON, "rest_hongcheon"); d.vote(FamilyMember.MOM)
        d.engine.suggestPrimary()
        assertEquals(ProposalStatus.RETURN_TRIP, s.proposals.last().status)
    }

    @Test fun timelineMinutesMatchBudget() {
        d.engine.onFamilyEvent(FamilyEvent.UrgentRequest(FamilyMember.MINJUN.id, UrgentType.TOILET))
        val ui = UiMapper.map(s)
        assertEquals(ui.budget.usedMinutes, ui.timeline.timeRows.sumOf { it.minutes })
    }

    @Test fun familyEventsSerializeForPhone() {
        val e: FamilyEvent = FamilyEvent.UrgentRequest("minjun", UrgentType.TOILET)
        val text = FamilyJson.encodeToString(FamilyEvent.serializer(), e)
        assertEquals(e, FamilyJson.decodeFromString(FamilyEvent.serializer(), text))
        val v: FamilyEvent = FamilyEvent.Vote("mom", "p3", VoteChoice.YES)
        assertEquals(v, FamilyJson.decodeFromString(FamilyEvent.serializer(), FamilyJson.encodeToString(FamilyEvent.serializer(), v)))
    }
}

class ReminderTest {
    @Test fun deferredProposalIsAskedOnceMoreWhenNear() {
        val d = Drive()
        d.run(500)
        d.propose(FamilyMember.SEOYEON, "hanu_hoengseong"); d.vote(FamilyMember.MOM)
        d.engine.suggestDefer()
        val id = d.s.proposals.last().id
        val hanu = d.scenario.place("hanu_hoengseong")!!
        var asked = 0
        var lastSuggest: String? = null
        while (d.s.kmTo(hanu) > 1.0) {
            d.run(500, mult = 60.0) {
                val cur = d.s.suggest
                if (cur != null && cur.reminder && cur.proposalId != lastSuggest) asked++
                lastSuggest = cur?.proposalId
            }
        }
        org.junit.Assert.assertEquals(1, asked)
        org.junit.Assert.assertTrue(d.s.proposal(id)!!.reminded)
        org.junit.Assert.assertEquals(ai.familyroute.demo.domain.ProposalStatus.NEXT_STOP, d.s.proposal(id)!!.status)
    }
}
