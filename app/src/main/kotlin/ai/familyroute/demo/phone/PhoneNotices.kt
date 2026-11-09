package ai.familyroute.demo.phone

import ai.familyroute.demo.domain.DecidedBy
import ai.familyroute.demo.domain.EngineState
import ai.familyroute.demo.domain.ProposalStatus
import ai.familyroute.demo.domain.TripMath
import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.model.eulReul
import ai.familyroute.demo.model.eunNeun
import ai.familyroute.demo.model.formatClock
import kotlinx.serialization.Serializable

@Serializable
data class PhoneMessage(val time: String, val memberId: String?, val text: String)

/** 상태 변화에서 가족 폰에 보낼 문장을 만든다(운전자 결정·급한 요청·도착). */
object PhoneNotices {
    fun diff(old: EngineState, new: EngineState): List<PhoneMessage> {
        val time = formatClock(TripMath.now(new))
        val out = mutableListOf<PhoneMessage>()
        val before = old.proposals.associateBy { it.id }
        for (p in new.proposals) {
            val prev = before[p.id]
            if (prev?.status == p.status) continue
            val place = p.place.name
            val byFamily = p.decidedBy == DecidedBy.FAMILY_MAJORITY
            val text = when {
                p.urgent != null && prev == null -> "${p.member.displayName} ${p.urgent.label} 요청으로 ${place.eulReul()} 경로에 넣었어요"
                prev == null && p.status == ProposalStatus.VOTING -> "${p.member.displayName} 제안 · ${place} · 찬성하면 과반수에서 경로에 들어가요"
                p.status == ProposalStatus.ACCEPTED && byFamily -> "가족 과반수 찬성으로 ${place.eulReul()} 경로에 넣었어요 · 우회 +${p.place.detourMin}분"
                p.status == ProposalStatus.ACCEPTED -> "아빠가 ${place.eulReul()} 코스에 넣었어요 · 우회 +${p.place.detourMin}분"
                p.status == ProposalStatus.NEXT_STOP -> "${place.eunNeun()} 다음 정차 때 함께 정해요"
                p.status == ProposalStatus.RETURN_TRIP -> "${place.eunNeun()} 돌아오는 길에 들러요"
                p.status == ProposalStatus.AFTER_ARRIVAL -> "${place.eunNeun()} 도착 후 일정에 넣었어요"
                p.status == ProposalStatus.SKIPPED && prev != null -> "이번에는 ${place.eulReul()} 넘겼어요"
                p.status == ProposalStatus.DRIVER_PENDING -> "${place} · 모두 투표했어요 · 아빠 확인을 기다려요"
                else -> null
            } ?: continue
            out += PhoneMessage(time, p.member.id, text)
        }
        val ask = new.suggest
        if (ask != null && ask.reminder && old.suggest?.proposalId != ask.proposalId) {
            new.proposal(ask.proposalId)?.let { out += PhoneMessage(time, it.member.id, "곧 ${it.place.name} 근처예요 · 아빠에게 한 번 더 물어볼게요") }
        }
        for ((placeId, _) in new.visits - old.visits.keys) {
            val name = new.scenario.place(placeId)?.name ?: continue
            out += PhoneMessage(time, null, "$name 도착 · 잠깐 쉬어 가요")
        }
        if (new.arrived && !old.arrived) out += PhoneMessage(time, null, "${new.scenario.destinationName} 도착!")
        return out
    }

    fun memberName(id: String?) = id?.let { FamilyMember.byId(it)?.displayName }
}
