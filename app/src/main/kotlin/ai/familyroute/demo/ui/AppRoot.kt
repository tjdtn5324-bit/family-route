package ai.familyroute.demo.ui

import ai.familyroute.demo.domain.Screen
import ai.familyroute.demo.map.MapRenderer
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.ui.decide.DecideScreen
import ai.familyroute.demo.ui.dev.DevPanel
import ai.familyroute.demo.ui.main.MainScreen
import ai.familyroute.demo.ui.suggest.SuggestOverlay
import ai.familyroute.demo.ui.timeline.TimelineScreen
import ai.familyroute.demo.ui.theme.FrColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.familyroute.demo.ui.theme.frText

/** 상태 기반 화면 전환(내비게이션 라이브러리 없음). */
@Composable
fun AppRoot(state: TripUiState, mapRenderer: MapRenderer, onAction: (UiAction) -> Unit) {
    BackHandler(enabled = state.dev != null || state.screen != Screen.MAIN) { onAction(UiAction.Back) }
    Column(Modifier.fillMaxSize().background(FrColors.Bg)) {
        BasicText(
            "시연 모드 · 지도·주행·도착 시간은 가상 데이터 · 실제 길 안내 아님",
            style = frText(14, FontWeight.Bold, FrColors.TextSoft),
            modifier = Modifier.fillMaxWidth().background(FrColors.Panel2).padding(horizontal = 20.dp, vertical = 6.dp),
        )
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (state.screen) {
            Screen.MAIN -> {
                val suggest = state.suggest
                if (suggest != null) {
                    SuggestOverlay(state, suggest, mapRenderer, onAction)
                } else {
                    MainScreen(state, mapRenderer, onAction)
                }
            }
            Screen.DECIDE -> state.decide?.let { DecideScreen(it, onAction) }
            Screen.TIMELINE -> TimelineScreen(state.timeline, onAction)
        }
        state.dev?.let { DevPanel(it, onAction) }
    }
    }
}
