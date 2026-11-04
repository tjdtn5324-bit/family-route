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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 상태 기반 화면 전환(내비게이션 라이브러리 없음). */
@Composable
fun AppRoot(state: TripUiState, mapRenderer: MapRenderer, onAction: (UiAction) -> Unit) {
    BackHandler(enabled = state.dev != null || state.screen != Screen.MAIN) { onAction(UiAction.Back) }
    Box(Modifier.fillMaxSize().background(FrColors.Bg)) {
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
