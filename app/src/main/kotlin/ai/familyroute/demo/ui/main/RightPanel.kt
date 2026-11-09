package ai.familyroute.demo.ui.main

import ai.familyroute.demo.model.BudgetUi
import ai.familyroute.demo.model.StopStatus
import ai.familyroute.demo.model.StopUi
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.model.formatClock
import ai.familyroute.demo.model.formatDistance
import ai.familyroute.demo.model.formatDuration
import ai.familyroute.demo.ui.common.Avatar
import ai.familyroute.demo.ui.common.focusRing
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 시안 높이(500dp)보다 낮으면 간격을 줄인다. 1920×720 AVD는 약 448dp. */
private val CompactBelow = 500.dp

@Composable
fun RightPanel(state: TripUiState, onOpenTimeline: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.width(MainDimens.PanelWidth).background(FrColors.Panel)) {
        val compact = maxHeight < CompactBelow
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = if (compact) 14.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
        ) {
            EtaHeader(state)
            BudgetCard(state.budget, compact)
            val stopsShape = RoundedCornerShape(8.dp)
            BasicText(
                "들를 곳 ${state.stops.size}",
                style = frText(15, FontWeight.Bold, FrColors.TextSub),
                modifier = Modifier
                    .focusRing(stopsShape)
                    .clickable(onClickLabel = "가족 코스 타임라인 열기", onClick = onOpenTimeline),
            )
            FittingStops(state.stops, gap = if (compact) 6.dp else 10.dp, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 남은 높이에 다 들어가는 줄만 보여주고, 나머지는 "외 N곳"으로 접는다.
 * 주행 화면이라 스크롤은 쓰지 않는다. 전체 목록은 타임라인 화면에서 본다.
 */
@Composable
private fun FittingStops(stops: List<StopUi>, gap: Dp, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val rows = stops.mapIndexed { i, stop ->
            subcompose("row$i") { StopRow(stop) }.first().measure(loose)
        }
        fun heightOf(n: Int) = rows.take(n).sumOf { it.height } + gapPx * (n - 1).coerceAtLeast(0)
        var shown = rows.size
        var more: Placeable? = null
        if (heightOf(shown) > constraints.maxHeight) {
            // "외 N곳" 줄 자리까지 남기고 들어가는 만큼만
            val label = { n: Int ->
                subcompose("more$n") {
                    BasicText("외 ${n}곳 · 들를 곳을 눌러 전체 보기", style = frText(14, color = FrColors.TextSub))
                }.first().measure(loose)
            }
            val probe = label(rows.size)
            while (shown > 0 && heightOf(shown) + gapPx + probe.height > constraints.maxHeight) shown--
            more = label(rows.size - shown)
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = 0
            rows.take(shown).forEach {
                it.place(0, y)
                y += it.height + gapPx
            }
            more?.place(0, y)
        }
    }
}

@Composable
private fun EtaHeader(state: TripUiState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            BasicText("${state.destinationName} 도착", style = frText(15, color = FrColors.TextSub))
            BasicText(formatClock(state.eta), style = frText(44, FontWeight.Black, tight = true))
        }
        BasicText(
            "${formatDuration(state.remainingMinutes)}\n${formatDistance(state.remainingMeters)}",
            style = frText(17, color = FrColors.TextSub).copy(textAlign = TextAlign.End),
        )
    }
}

@Composable
private fun BudgetCard(budget: BudgetUi, compact: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FrColors.Panel2, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("가족 여유시간", style = frText(16, FontWeight.Bold))
            BasicText(
                buildAnnotatedString {
                    append("${budget.totalMinutes}분 중 ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${budget.usedMinutes}분") }
                    append(" 사용")
                },
                style = frText(16, color = FrColors.TextSoft),
            )
        }
        BudgetBar(budget, Modifier.fillMaxWidth().height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            budget.usage.forEach {
                BasicText("${it.member.displayName} ${it.minutes}분", style = frText(14, color = FrColors.TextSub))
            }
            BasicText("남은 ${budget.remainingMinutes}분", style = frText(14, color = FrColors.TextSub))
        }
    }
}

/** 사람별 색 구간 막대. 폭은 예산 대비 비율. */
@Composable
fun BudgetBar(budget: BudgetUi, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(7.dp))) {
        drawRect(FrColors.Line)
        val total = budget.totalMinutes.coerceAtLeast(1).toFloat()
        var x = 0f
        budget.usage.forEach {
            val w = (it.minutes / total * size.width).coerceAtMost(size.width - x)
            if (w > 0f) drawRect(it.member.color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun StopRow(stop: StopUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(stop.member, 36.dp, 16, pending = stop.status == StopStatus.PENDING)
        Column(Modifier.weight(1f)) {
            BasicText(stop.placeName, style = frText(18, FontWeight.Bold))
            BasicText(stop.detail, style = frText(14, color = FrColors.TextSub))
        }
        when (stop.status) {
            StopStatus.CONFIRMED -> BasicText(
                "확정",
                style = frText(14, FontWeight.Bold, FrColors.Bg),
                modifier = Modifier
                    .background(FrColors.AccentSoft, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            StopStatus.PENDING -> BasicText(
                "대기",
                style = frText(14, FontWeight.Bold, FrColors.Warn),
                modifier = Modifier
                    .border(1.5.dp, FrColors.Warn, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
            // 시안에 없는 칩: 도착 후 일정·귀갓길로 미룬 제안
            StopStatus.AFTER_ARRIVAL, StopStatus.RETURN_TRIP -> BasicText(
                if (stop.status == StopStatus.AFTER_ARRIVAL) "도착 후" else "귀갓길",
                style = frText(14, FontWeight.Bold, FrColors.TextSub),
                modifier = Modifier
                    .border(1.5.dp, FrColors.Outline, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}
