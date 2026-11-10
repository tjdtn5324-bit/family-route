package ai.familyroute.demo.map

import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.route.ActiveRoute
import ai.familyroute.demo.route.Pt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 지도 그리기를 감추는 경계. 지금은 Compose Canvas 구현(CanvasMapRenderer)만 있고,
 * 나중에 MapLibre 같은 실제 지도로 바꿀 때 이 인터페이스만 새로 구현한다.
 */
interface MapRenderer {
    @Composable
    fun Render(state: MapUiState, modifier: Modifier)
}

/** 지도에 필요한 상태. 좌표는 route.Geo 평면(미터). */
data class MapUiState(
    val route: ActiveRoute,
    val progressM: Double,
    val speedMps: Double,
    val markers: List<MapMarker>,
    val destination: Pt,
    /** 주행 중 제안 미리보기: 우회 경로를 가족 색 점선으로 */
    val preview: MapPreview? = null,
)

/** 경유지 표시. [confirmed]가 아니면 점선 테두리로 그린다. */
data class MapMarker(val member: FamilyMember, val confirmed: Boolean, val pt: Pt)

data class MapPreview(val member: FamilyMember, val path: List<Pt>, val pt: Pt)
