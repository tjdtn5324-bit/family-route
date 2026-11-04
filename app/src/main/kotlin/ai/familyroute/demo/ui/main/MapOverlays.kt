package ai.familyroute.demo.ui.main

import ai.familyroute.demo.R
import ai.familyroute.demo.model.NoticeUi
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.ui.common.Avatar
import ai.familyroute.demo.model.TurnDirection
import ai.familyroute.demo.model.TurnGuide
import ai.familyroute.demo.model.formatDistance
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BoxScope.MapOverlays(state: TripUiState) {
    state.turn?.let { TurnCard(it, Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 24.dp)) }
    Column(
        Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 24.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectedPill(state.connectedMembers)
        // 급한 요청 등 운전자 확인 없이 처리된 일을 알린다. 회전 안내 카드와 겹치지 않는 오른쪽 위.
        state.notice?.let { NoticeBanner(it) }
    }
    MapCaption(
        "${state.roadName} · ${state.speedKph}",
        Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 20.dp),
    )
}

@Composable
fun MapCaption(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = frText(16, color = FrColors.TextSoft),
        modifier = modifier
            .background(FrColors.Panel, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 회전 안내 카드. 어떤 오버레이도 이 카드를 가리지 않는다. */
@Composable
fun TurnCard(turn: TurnGuide, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(FrColors.Panel, RoundedCornerShape(18.dp))
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier.size(68.dp).background(FrColors.Accent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_turn_right),
                contentDescription = if (turn.direction == TurnDirection.RIGHT) "우회전" else "좌회전",
                modifier = Modifier
                    .size(40.dp)
                    .scale(scaleX = if (turn.direction == TurnDirection.LEFT) -1f else 1f, scaleY = 1f),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(formatDistance(turn.distanceM), style = frText(36, FontWeight.Black, tight = true))
            BasicText(turn.text, style = frText(18, color = FrColors.TextSub))
        }
    }
}

@Composable
private fun NoticeBanner(notice: NoticeUi) {
    Row(
        Modifier
            .widthIn(max = 460.dp)
            .background(FrColors.Panel, RoundedCornerShape(14.dp))
            .border(1.5.dp, FrColors.Warn, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        notice.member?.let { Avatar(it, 32.dp, 14) }
        BasicText(notice.text, style = frText(17, FontWeight.Bold))
    }
}

@Composable
private fun ConnectedPill(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(FrColors.Panel, CircleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(FrColors.Ok, CircleShape))
        BasicText("가족 ${count}명 연결됨", style = frText(16, color = FrColors.TextSoft))
    }
}
