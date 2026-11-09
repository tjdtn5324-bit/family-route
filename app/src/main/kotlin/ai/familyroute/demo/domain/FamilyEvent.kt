package ai.familyroute.demo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 가족이 보내는 이벤트. 나중에 동승자 폰을 붙이면 이 JSON을 그대로 네트워크로 받는다.
 * 그래서 가족·장소는 객체가 아니라 id 문자열로 가진다.
 */
@Serializable
sealed interface FamilyEvent {
    @Serializable
    @SerialName("proposal")
    data class Proposal(val memberId: String, val placeId: String, val message: String? = null) : FamilyEvent

    @Serializable
    @SerialName("vote")
    data class Vote(val memberId: String, val proposalId: String, val choice: VoteChoice) : FamilyEvent

    @Serializable
    @SerialName("urgent")
    data class UrgentRequest(val memberId: String, val type: UrgentType) : FamilyEvent
}

@Serializable
enum class VoteChoice { YES, NO }

@Serializable
enum class UrgentType(val label: String) {
    TOILET("화장실"),
    MOTION_SICKNESS("멀미"),
}
