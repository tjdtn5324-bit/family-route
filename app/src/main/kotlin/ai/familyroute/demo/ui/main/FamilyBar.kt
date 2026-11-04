package ai.familyroute.demo.ui.main

import ai.familyroute.demo.R
import ai.familyroute.demo.model.MemberStatusUi
import ai.familyroute.demo.model.TripUiState
import ai.familyroute.demo.ui.common.Avatar
import ai.familyroute.demo.ui.common.ButtonStyle
import ai.familyroute.demo.ui.common.FrButton
import ai.familyroute.demo.ui.common.focusRing
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 사양: 주행 중 누르는 버튼은 높이 76dp 이상 (시안은 64px) */
private val DriveButtonHeight = 76.dp

@Composable
fun FamilyBar(
    state: TripUiState,
    onOpenProposals: () -> Unit,
    onOpenDevPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(MainDimens.BarHeight)
            .background(FrColors.Bar)
            .drawBehind { drawLine(FrColors.Line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        state.members.forEach { MemberChip(it, if (it.member.isDriver) onOpenDevPanel else null) }
        Spacer(Modifier.weight(1f))
        ProposalsButton(state.pendingProposals, onOpenProposals)
        VoiceButton()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberChip(item: MemberStatusUi, onLongPress: (() -> Unit)?) {
    val m = if (onLongPress != null) {
        // 운전자 아바타 길게 누르기 = 개발자 패널 (데모 녹화 때는 숨김)
        Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
    } else {
        Modifier
    }
    Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(item.member, 52.dp, 20)
        Column {
            BasicText(item.member.displayName, style = frText(17, FontWeight.Bold))
            BasicText(
                item.status,
                style = frText(14, color = if (item.emphasized) item.member.color else FrColors.TextSub),
            )
        }
    }
}

@Composable
private fun ProposalsButton(count: Int, onClick: () -> Unit) {
    FrButton(
        text = "가족 제안 보기",
        onClick = onClick,
        style = if (count > 0) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE,
        height = DriveButtonHeight,
        modifier = Modifier.padding(0.dp),
        trailing = if (count > 0) {
            {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 28.dp)
                        .height(28.dp)
                        .background(Color.White, CircleShape)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("$count", style = frText(16, FontWeight.Bold, FrColors.BadgeText))
                }
            }
        } else {
            null
        },
    )
}

/** 실제 음성 인식 없이 "음성 명령(데모)" 상태만 잠깐 보여준다. */
@Composable
private fun VoiceButton() {
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(showing) {
        if (showing) {
            delay(2500)
            showing = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showing) {
            BasicText(
                "음성 명령(데모)",
                style = frText(16, FontWeight.Bold, FrColors.AccentSoft),
                modifier = Modifier
                    .border(1.5.dp, FrColors.Accent, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(
            Modifier
                .size(DriveButtonHeight)
                .focusRing(CircleShape)
                .border(2.dp, FrColors.Accent, CircleShape)
                .clickable { showing = true }
                .semantics { contentDescription = "음성으로 결정" },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_mic),
                contentDescription = null,
                colorFilter = ColorFilter.tint(FrColors.AccentSoft),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
