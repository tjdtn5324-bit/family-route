package ai.familyroute.demo.ui.decide

import ai.familyroute.demo.R
import ai.familyroute.demo.model.DecideCardUi
import ai.familyroute.demo.model.DecideUi
import ai.familyroute.demo.model.formatClock
import ai.familyroute.demo.ui.UiAction
import ai.familyroute.demo.ui.common.Avatar
import ai.familyroute.demo.ui.common.ButtonStyle
import ai.familyroute.demo.ui.common.FrButton
import ai.familyroute.demo.ui.main.BudgetBar
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 정차 중 전용 비교 화면. 카드에서 고른 결과는 "이대로 출발"이나 실제 출발 때 반영되고
 * 화면은 지도로 돌아간다.
 */
@Composable
fun DecideScreen(d: DecideUi, onAction: (UiAction) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(FrColors.Bg)) {
        val compact = maxHeight < 600.dp
        Column(Modifier.fillMaxSize()) {
            Header(d)
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = if (compact) 14.dp else 22.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    d.cards.forEachIndexed { i, card ->
                        Card(
                            card, compact,
                            onToggle = { onAction(UiAction.DecideToggle(card.id)) },
                            focus = if (i == 0) firstFocus else null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    // 카드가 3개보다 적어도 폭은 시안처럼 3등분
                    repeat(3 - d.cards.size) { Spacer(Modifier.weight(1f)) }
                }
                Summary(d, compact, onAction, Modifier.width(400.dp).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Header(d: DecideUi) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .drawBehind { drawLine(FrColors.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).background(FrColors.Line, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            BasicText("P", style = frText(22, FontWeight.Black))
        }
        BasicText("정차 중 · ${d.stopName}", style = frText(22, FontWeight.Bold))
        val more = if (d.hiddenCount > 0) " (${d.hiddenCount}건은 다음 정차 때)" else ""
        BasicText("가족 제안 ${d.cards.size + d.hiddenCount}건을 함께 정해요$more", style = frText(18, color = FrColors.TextSub))
        Spacer(Modifier.weight(1f))
        BasicText("출발하면 지도로 돌아가요", style = frText(16, color = FrColors.TextSub))
    }
}

@Composable
private fun Card(c: DecideCardUi, compact: Boolean, onToggle: () -> Unit, focus: FocusRequester?, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .background(FrColors.Panel, shape)
            .border(2.dp, if (c.selected) FrColors.Accent else FrColors.Line, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(c.member, 40.dp, 16)
            BasicText("${c.member.displayName} 제안", style = frText(16, color = FrColors.TextSub))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(c.placeName, style = frText(24, FontWeight.Black))
            BasicText(c.info, style = frText(15, color = FrColors.TextSub))
        }
        KeyValue("우회", c.detourText)
        KeyValue("도착", c.arrivalText)
        if (c.votes.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText("가족 의견", style = frText(17, color = FrColors.TextSub))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.votes.forEach { v ->
                        val m = if (v.agreed) {
                            Modifier.background(v.member.color, CircleShape)
                        } else {
                            Modifier.border(1.5.dp, v.member.color, CircleShape)
                        }
                        BasicText(
                            v.label,
                            style = frText(14, FontWeight.Bold, if (v.agreed) v.member.onColor else v.member.color),
                            modifier = m.padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        c.banner?.let {
            BasicText(
                it.text,
                style = frText(15, color = if (it.warn) FrColors.WarnBannerText else FrColors.InfoBannerText),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (it.warn) FrColors.WarnBannerBg else FrColors.InfoBannerBg, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        FrButton(
            text = c.actionLabel,
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            style = if (c.selected) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE,
            height = 60.dp,
            fontSize = 19,
            radius = 14.dp,
            focusRequester = focus,
            trailing = if (c.selected) {
                {
                    Image(
                        painterResource(R.drawable.ic_check),
                        contentDescription = "선택됨",
                        colorFilter = ColorFilter.tint(FrColors.OnAccent),
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(key, style = frText(17, color = FrColors.TextSub))
        BasicText(value, style = frText(17, FontWeight.Bold))
    }
}

@Composable
private fun Summary(d: DecideUi, compact: Boolean, onAction: (UiAction) -> Unit, modifier: Modifier) {
    val s = d.summary
    Column(
        modifier
            .background(FrColors.Panel, RoundedCornerShape(18.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        BasicText("이렇게 가면", style = frText(18, FontWeight.Bold))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(formatClock(s.newEta), style = frText(42, FontWeight.Black, tight = true))
            val delta = if (s.deltaMinutes == 0) "변화 없음" else "원래 ${formatClock(s.originalEta)} → +${s.deltaMinutes}분"
            BasicText(delta, style = frText(17, color = FrColors.TextSub), modifier = Modifier.padding(bottom = 6.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BudgetBar(s.budget, Modifier.fillMaxWidth().height(14.dp))
            BasicText(s.budgetText, style = frText(15, color = FrColors.TextSub))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.order.forEach { BasicText(it, style = frText(16, color = FrColors.TextSoft)) }
        }
        Spacer(Modifier.weight(1f))
        FrButton("이대로 출발", { onAction(UiAction.DecideDepart) }, Modifier.fillMaxWidth(), ButtonStyle.PRIMARY, height = 70.dp, fontSize = 22)
        BasicText(
            s.phoneNote,
            style = frText(14, color = FrColors.TextSub).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
