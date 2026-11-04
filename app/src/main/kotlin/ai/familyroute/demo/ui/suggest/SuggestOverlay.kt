package ai.familyroute.demo.ui.suggest

import ai.familyroute.demo.map.MapRenderer
import ai.familyroute.demo.model.SuggestUi
import ai.familyroute.demo.model.TileUi
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.ui.UiAction
import ai.familyroute.demo.ui.common.Avatar
import ai.familyroute.demo.ui.common.ButtonStyle
import ai.familyroute.demo.ui.common.FrButton
import ai.familyroute.demo.ui.main.MapCaption
import ai.familyroute.demo.ui.main.TurnCard
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 시안 Suggest.dc.html: 우측 680dp 패널 */
private val SuggestPanelWidth = 680.dp

/**
 * 주행 중 가족 제안. 한 번에 하나만, 회전 안내 카드는 가리지 않는다.
 * 10초 안에 고르지 않으면 엔진이 "다음 정차 때"로 넘긴다.
 */
@Composable
fun SuggestOverlay(
    state: TripUiState,
    suggest: SuggestUi,
    mapRenderer: MapRenderer,
    onAction: (UiAction) -> Unit,
) {
    Row(Modifier.fillMaxSize().background(FrColors.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            mapRenderer.Render(suggest.map, Modifier.fillMaxSize())
            state.turn?.let { TurnCard(it, Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 24.dp)) }
            MapCaption(
                "점선 = 제안 경유지로 가는 길 미리보기",
                Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 20.dp),
            )
        }
        BoxWithConstraints(
            Modifier
                .width(SuggestPanelWidth)
                .fillMaxHeight()
                .background(FrColors.Panel)
                .drawBehind { drawLine(FrColors.Line, Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) },
        ) {
            val compact = maxHeight < 600.dp
            SuggestPanel(suggest, compact, onAction)
        }
    }
}

@Composable
private fun SuggestPanel(s: SuggestUi, compact: Boolean, onAction: (UiAction) -> Unit) {
    val primaryFocus = remember { FocusRequester() }
    // 새 제안이 뜨면 첫 버튼에 포커스: 스티어링 ◀ ▶ 로 바로 고를 수 있게
    LaunchedEffect(s.proposalId) { runCatching { primaryFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp, vertical = if (compact) 16.dp else 26.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(s.member, 60.dp, 24)
            Column {
                BasicText(s.title, style = frText(24, FontWeight.Bold))
                BasicText(s.votesLine, style = frText(16, color = FrColors.TextSub))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(s.placeName, style = frText(34, FontWeight.Black, tight = true))
            BasicText(s.placeInfo, style = frText(17, color = FrColors.TextSub))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            s.tiles.forEach { Tile(it, Modifier.weight(1f)) }
        }
        s.message?.let {
            BasicText(
                "“$it”",
                style = frText(18, color = Color(0xFFCFFAF3)),
                modifier = Modifier
                    .background(Color(0xFF12302E), RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f, fill = false))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FrButton(
                s.primaryLabel, { onAction(UiAction.SuggestPrimary) },
                Modifier.weight(1.5f), ButtonStyle.PRIMARY, height = 78.dp, fontSize = 24,
                focusRequester = primaryFocus,
            )
            FrButton("다음 정차 때", { onAction(UiAction.SuggestDefer) }, Modifier.weight(1.1f), ButtonStyle.SECONDARY, height = 78.dp)
            FrButton("넘기기", { onAction(UiAction.SuggestSkip) }, Modifier.weight(0.8f), ButtonStyle.OUTLINE, height = 78.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                drawRect(FrColors.Line)
                drawRect(FrColors.AccentSoft, size = Size(size.width * s.fractionLeft, size.height))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText("응답 없으면 ${s.secondsLeft}초 뒤 ‘다음 정차 때’로 넘어가요", style = frText(15, color = FrColors.TextSub))
                BasicText("핸들 ◀ ▶ 선택", style = frText(15, color = FrColors.TextSub))
            }
        }
    }
}

@Composable
private fun Tile(t: TileUi, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(FrColors.Panel2, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText(t.value, style = frText(30, FontWeight.Black, if (t.warn) FrColors.Warn else FrColors.Text, tight = true))
        BasicText(t.label, style = frText(15, color = FrColors.TextSub))
    }
}
