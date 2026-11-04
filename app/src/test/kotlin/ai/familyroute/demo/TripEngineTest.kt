package ai.familyroute.demo

import ai.familyroute.demo.domain.DemoStart
import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.Gear
import ai.familyroute.demo.domain.ProposalStatus
import ai.familyroute.demo.domain.Rules
import ai.familyroute.demo.domain.Screen
import ai.familyroute.demo.domain.TripEngine
import ai.familyroute.demo.domain.TripPlan
import ai.familyroute.demo.domain.UrgentType
import ai.familyroute.demo.domain.VoteChoice
import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.model.UiMapper
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {
    private var now = 1_000_000L
    private val engine = TripEngine(now)
    private val s get() = engine.state.value

    private fun advance(ms: Long) {
        now += ms
        engine.tick(now)
    }

    private fun vote(member: FamilyMember, id: String, yes: Boolean = true) =
        engine.onFamilyEvent(FamilyEvent.Vote(member.id, id, if (yes) VoteChoice.YES else VoteChoice.NO))

    private fun park() {
        engine.onVehicle(s.vehicle.copy(gear = Gear.P, speedKph = 0.0))
        advance(Rules.PARK_CONFIRM_MS)
    }

    @Test fun startMatchesDesign() {
        assertEquals(14, s.budgetUsedMin)
        assertEquals("14:52", ai.familyroute.demo.model.formatClock(TripPlan.eta(s)))
        val ui = UiMapper.map(s)
        assertEquals(1, ui.pendingProposals)
        assertEquals(listOf("두물머리 카페", "홍천 휴게소", "횡성 한우 식당"), ui.stops.map { it.placeName })
        assertEquals("엄마 제안 · 23분 후 · +8분", ui.stops[0].detail)
    }

    @Test fun voteMovesToDriverPendingAndShowsOverlayWhileDriving() {
        assertEquals(ProposalStatus.VOTING, s.proposal("p3")!!.status)
        vote(FamilyMember.MOM, "p3")
        assertEquals(ProposalStatus.DRIVER_PENDING, s.proposal("p3")!!.status)
        assertEquals("p3", s.suggest?.proposalId)
    }

    @Test fun noAnswerFor10SecondsDefersToNextStop() {
        vote(FamilyMember.MOM, "p3")
        advance(Rules.SUGGEST_TIMEOUT_MS - 100)
        assertNotNull(s.suggest)
        advance(100)
        assertNull(s.suggest)
        assertEquals(ProposalStatus.NEXT_STOP, s.proposal("p3")!!.status)
    }

    @Test fun acceptWithinBudgetAddsDetourAndDwellToEta() {
        val etaBefore = TripPlan.eta(s)
        vote(FamilyMember.MOM, "p3")
        engine.suggestPrimary()
        assertEquals(ProposalStatus.ACCEPTED, s.proposal("p3")!!.status)
        assertEquals(26, s.budgetUsedMin)
        assertEquals(12 + 50, TripPlan.minutesBetween(etaBefore, TripPlan.eta(s)))
    }

    @Test fun skipAndDeferDoNotTouchBudget() {
        vote(FamilyMember.MOM, "p3")
        engine.suggestSkip()
        assertEquals(ProposalStatus.SKIPPED, s.proposal("p3")!!.status)
        assertEquals(14, s.budgetUsedMin)
    }

    @Test fun overBudgetRecommendsReturnTripAndNearDestinationRecommendsAfterArrival() {
        engine.onFamilyEvent(FamilyEvent.Proposal(FamilyMember.MOM.id, "sheep_daegwallyeong"))
        val sheep = s.proposals.last()
        // 엄마 제안이니 투표 대상은 서연·민준
        vote(FamilyMember.SEOYEON, sheep.id)
        assertEquals(sheep.id, s.suggest?.proposalId)
        engine.suggestPrimary()
        assertEquals(ProposalStatus.RETURN_TRIP, s.proposal(sheep.id)!!.status)
        assertEquals(14, s.budgetUsedMin)

        engine.onFamilyEvent(FamilyEvent.Proposal(FamilyMember.MINJUN.id, "market_gangneung"))
        val market = s.proposals.last()
        vote(FamilyMember.SEOYEON, market.id)
        engine.suggestPrimary()
        assertEquals(ProposalStatus.AFTER_ARRIVAL, s.proposal(market.id)!!.status)
    }

    @Test fun twoTokensPerMember() {
        // 시작 상태에서 서연은 이미 1회 사용
        engine.onFamilyEvent(FamilyEvent.Proposal(FamilyMember.SEOYEON.id, "rest_pyeongchang"))
        val before = s.proposals.size
        assertEquals(0, s.tokens[FamilyMember.SEOYEON])
        engine.onFamilyEvent(FamilyEvent.Proposal(FamilyMember.SEOYEON.id, "market_gangneung"))
        assertEquals(before, s.proposals.size)
        assertNotNull(s.notice)
    }

    @Test fun urgentRequestAutoConfirmsNearestRestAreaWithoutToken() {
        val tokens = s.tokens[FamilyMember.SEOYEON]
        engine.onFamilyEvent(FamilyEvent.UrgentRequest(FamilyMember.SEOYEON.id, UrgentType.MOTION_SICKNESS))
        val added = s.proposals.last()
        assertEquals(ProposalStatus.ACCEPTED, added.status)
        // 홍천 휴게소는 이미 확정이라 그다음 휴게소
        assertEquals("rest_hoengseong", added.place.id)
        assertEquals(tokens, s.tokens[FamilyMember.SEOYEON])
        assertNull("급한 요청은 운전자 확인 오버레이를 띄우지 않는다", s.suggest)
        assertNotNull(s.notice)
    }

    @Test fun onlyOneOverlayAtATime() {
        engine.onFamilyEvent(FamilyEvent.Proposal(FamilyMember.MOM.id, "sheep_daegwallyeong"))
        val sheep = s.proposals.last().id
        vote(FamilyMember.MOM, "p3")
        vote(FamilyMember.SEOYEON, sheep)
        assertEquals("p3", s.suggest?.proposalId)
        engine.suggestDefer()
        advance(100)
        assertEquals(sheep, s.suggest?.proposalId)
    }

    @Test fun decideOpensOnlyAfterParkedThreeSecondsAndClosesOnDeparture() {
        engine.onVehicle(s.vehicle.copy(gear = Gear.P, speedKph = 0.0))
        advance(Rules.PARK_CONFIRM_MS - 100)
        assertFalse(s.isParked)
        engine.openProposals()
        // 3초 전에는 정차 화면이 아니라 오버레이
        assertEquals(Screen.MAIN, s.screen)
        engine.suggestDefer()
        advance(100)
        assertTrue(s.isParked)
        engine.openProposals()
        assertEquals(Screen.DECIDE, s.screen)

        engine.decideToggle("p3")
        assertEquals(ProposalStatus.ACCEPTED, s.decideChoices["p3"])
        // 다시 출발하면 고른 대로 반영하고 지도로
        engine.onVehicle(s.vehicle.copy(gear = Gear.D, speedKph = 30.0))
        assertEquals(Screen.MAIN, s.screen)
        assertEquals(ProposalStatus.ACCEPTED, s.proposal("p3")!!.status)
    }

    @Test fun decideSelectionsRespectSharedBudget() {
        engine.onFamilyEvent(FamilyEvent.Proposal(FamilyMember.MINJUN.id, "rest_pyeongchang"))
        val rest = s.proposals.last().id
        park()
        engine.openProposals()
        engine.decideToggle("p3") // +12 → 남은 4분
        engine.decideToggle(rest) // +5 > 4 → 귀갓길 추천
        assertEquals(ProposalStatus.RETURN_TRIP, s.decideChoices[rest])
        engine.decideDepart()
        assertEquals(26, s.budgetUsedMin)
    }

    @Test fun timelineMemberMinutesMatchBudget() {
        vote(FamilyMember.MOM, "p3")
        engine.suggestPrimary()
        val ui = UiMapper.map(s)
        assertEquals(ui.budget.usedMinutes, ui.timeline.timeRows.sumOf { it.minutes })
    }

    @Test fun familyEventsSerializeForPhoneLater() {
        val json = Json { classDiscriminator = "kind" }
        val e: FamilyEvent = FamilyEvent.Vote("mom", "p3", VoteChoice.YES)
        val text = json.encodeToString(FamilyEvent.serializer(), e)
        assertEquals(e, json.decodeFromString(FamilyEvent.serializer(), text))
    }

    @Test fun resetRestoresStart() {
        vote(FamilyMember.MOM, "p3")
        engine.suggestPrimary()
        engine.reset(now, DemoStart.vehicle(now))
        assertEquals(14, s.budgetUsedMin)
    }
}
