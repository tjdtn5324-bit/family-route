package ai.familyroute.demo.ui.timeline

import ai.familyroute.demo.model.TimelineNodeKind
import ai.familyroute.demo.model.TimelineNodeUi
import ai.familyroute.demo.model.TimelineUi
import ai.familyroute.demo.ui.UiAction
import ai.familyroute.demo.ui.common.ButtonStyle
import ai.familyroute.demo.ui.common.FrButton
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

@Composable
fun TimelineScreen(t: TimelineUi, onAction: (UiAction) -> Unit) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(FrColors.Bg)) {
        val compact = maxHeight < 600.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = if (compact) 16.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BasicText("오늘의 가족 코스", style = frText(28, FontWeight.Black))
                BasicText(t.subtitle, style = frText(18, color = FrColors.TextSub))
                Spacer(Modifier.weight(1f))
                FrButton(
                    "지도로 보기", { onAction(UiAction.Back) },
                    style = ButtonStyle.OUTLINE, height = 52.dp, fontSize = 18, radius = 14.dp,
                    focusRequester = backFocus,
                )
            }
            Track(t, compact)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TimeCard(t, Modifier.weight(1f).fillMaxHeight())
                TokenCard(t, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** 출발~도착 가로 타임라인. 지나온 구간은 accent, "지금" 마커 */
@Composable
private fun Track(t: TimelineUi, compact: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (compact) 210.dp else 250.dp)) {
        val n = t.nodes.size
        val nodeW: Dp = min(220.dp, maxWidth / n)
        val half = nodeW / 2
        val trackY = 84.dp
        val usable = maxWidth - nodeW
        val nowX = half + usable * t.nowFraction

        // 1) 선 → 2) 노드 → 3) "지금" 마커 순서로 그려서 경유지에 서 있을 때도 마커가 보이게 한다
        Canvas(Modifier.fillMaxSize()) {
            val y = trackY.toPx() - 3.dp.toPx()
            val left = half.toPx()
            val w = usable.toPx()
            val r = CornerRadius(3.dp.toPx())
            drawRoundRect(FrColors.Line, Offset(left, y), Size(w, 6.dp.toPx()), r)
            drawRoundRect(FrColors.Accent, Offset(left, y), Size(w * t.nowFraction, 6.dp.toPx()), r)
        }
        Row(Modifier.fillMaxWidth().padding(top = trackY - 37.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            t.nodes.forEach { Node(it, nodeW) }
        }
        Canvas(Modifier.fillMaxSize()) {
            val cx = nowX.toPx()
            val tip = trackY.toPx() - 30.dp.toPx()
            val path = Path().apply {
                moveTo(cx - 12.dp.toPx(), tip - 16.dp.toPx())
                lineTo(cx + 12.dp.toPx(), tip - 16.dp.toPx())
                lineTo(cx, tip)
                close()
            }
            drawPath(path, FrColors.Text)
        }
        BasicText(
            "지금",
            style = frText(13, FontWeight.Bold, FrColors.OnAccent).copy(textAlign = TextAlign.Center),
            modifier = Modifier
                .offset(x = nowX - 22.dp, y = trackY - 70.dp)
                .width(44.dp)
                .background(FrColors.Accent, CircleShape)
                .padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun Node(node: TimelineNodeUi, width: Dp) {
    Column(
        Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (bg, label, fg, size) = when (node.kind) {
            TimelineNodeKind.START -> Quad(FrColors.Line, "출발", FrColors.TextSub, 15)
            TimelineNodeKind.END -> Quad(FrColors.Text, "도착", FrColors.Bg, 15)
            TimelineNodeKind.STOP -> Quad(node.member!!.color, node.member.initial, node.member.onColor, 24)
        }
        Box(
            Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(bg)
                .border(4.dp, FrColors.Bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = frText(size, FontWeight.Bold, fg))
        }
        BasicText(
            node.title, style = frText(19, FontWeight.Bold).copy(textAlign = TextAlign.Center),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        BasicText(node.subtitle, style = frText(15, color = FrColors.TextSub).copy(textAlign = TextAlign.Center))
    }
}

private data class Quad(val bg: androidx.compose.ui.graphics.Color, val label: String, val fg: androidx.compose.ui.graphics.Color, val size: Int)

@Composable
private fun TimeCard(t: TimelineUi, modifier: Modifier) {
    Column(
        modifier.background(FrColors.Panel, RoundedCornerShape(18.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("누구의 시간을 썼나요", style = frText(18, FontWeight.Bold))
        t.timeRows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(row.member.displayName, style = frText(16), modifier = Modifier.width(52.dp))
                Canvas(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp))) {
                    drawRect(FrColors.Line)
                    drawRect(row.member.color, size = Size(size.width * row.fraction, size.height))
                }
                BasicText("${row.minutes}분", style = frText(16).copy(textAlign = TextAlign.End), modifier = Modifier.width(52.dp))
            }
        }
        BasicText(t.budgetText, style = frText(15, color = FrColors.TextSub))
    }
}

@Composable
private fun TokenCard(t: TimelineUi, modifier: Modifier) {
    Column(
        modifier.background(FrColors.Panel, RoundedCornerShape(18.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText("가족 제안 기록", style = frText(18, FontWeight.Bold))
        t.tokenRows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText(row.member.displayName, style = frText(17), modifier = Modifier.width(52.dp))
                Box(
                    Modifier.size(18.dp).background(if (row.added > 0) row.member.color else FrColors.Line, CircleShape),
                )
                BasicText(row.text, style = frText(17, color = FrColors.TextSub))
            }
        }
        BasicText("제안 횟수 제한 없음 · 아빠가 수락하거나 가족 과반수가 찬성하면 경로에 반영", style = frText(14, color = FrColors.TextSub))
    }
}
