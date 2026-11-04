package ai.familyroute.demo.ui.main

import ai.familyroute.demo.map.MapRenderer
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.ui.UiAction
import ai.familyroute.demo.ui.theme.FrColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 고정 치수(시안 px = dp). 나머지는 가중치로 나눈다. */
object MainDimens {
    val PanelWidth = 470.dp
    val BarHeight = 100.dp
}

/**
 * 주행 메인 화면. 지도는 남는 공간을 모두 차지하고(가중치),
 * 우측 패널 폭과 하단 가족 바 높이만 고정한다.
 */
@Composable
fun MainScreen(
    state: TripUiState,
    mapRenderer: MapRenderer,
    onAction: (UiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(FrColors.Bg)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                mapRenderer.Render(state.map, Modifier.fillMaxSize())
                MapOverlays(state)
            }
            RightPanel(
                state = state,
                onOpenTimeline = { onAction(UiAction.OpenTimeline) },
                modifier = Modifier.fillMaxHeight(),
            )
        }
        FamilyBar(
            state = state,
            onOpenProposals = { onAction(UiAction.OpenProposals) },
            onOpenDevPanel = { onAction(UiAction.DevPanel(true)) },
        )
    }
}
