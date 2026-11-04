package ai.familyroute.demo.map

import ai.familyroute.demo.model.FamilyMember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 지도 그리기를 감추는 경계. 지금은 Compose Canvas 구현만 있고,
 * 나중에 MapLibre 같은 실제 지도로 바꿀 때 이 인터페이스만 새로 구현한다.
 */
interface MapRenderer {
    @Composable
    fun Render(state: MapUiState, modifier: Modifier)
}

/** 지도에 필요한 상태. Phase 2에서 카메라·경로·차량 위치가 추가된다. */
data class MapUiState(
    val markers: List<MapMarker>,
    /** 주행 중 제안 미리보기: 이 가족의 제안 경유지로 가는 길을 점선으로 */
    val preview: FamilyMember? = null,
)

/** 경유지 표시. [confirmed]가 아니면 점선 테두리로 그린다. */
data class MapMarker(
    val member: FamilyMember,
    val confirmed: Boolean,
)
