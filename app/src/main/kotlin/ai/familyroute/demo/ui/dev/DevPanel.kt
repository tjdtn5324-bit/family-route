package ai.familyroute.demo.ui.dev

import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.UrgentType
import ai.familyroute.demo.domain.VoteChoice
import ai.familyroute.demo.model.DevUi
import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.ui.UiAction
import ai.familyroute.demo.ui.common.ButtonStyle
import ai.familyroute.demo.ui.common.FrButton
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 개발자 패널(운전자 아바타 길게 누르기). 데모 녹화 때는 열지 않는다.
 * 왼쪽: 상태·가족 폰 연결, 오른쪽: 시뮬레이터 조작과 이벤트 주입. 1920×720에서도 한 화면에 들어가게 좁게 짰다.
 */
@Composable
fun DevPanel(d: DevUi, onAction: (UiAction) -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC030A14))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 1500.dp)
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.96f)
                .background(FrColors.Panel, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            val small = maxHeight < 560.dp
            val btnH: Dp = if (small) 40.dp else 48.dp
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText("개발자 패널", style = frText(20, FontWeight.Black))
                    Spacer(Modifier.weight(1f))
                    FrButton("처음부터 다시", { onAction(UiAction.DevReset) }, style = ButtonStyle.OUTLINE, height = btnH, fontSize = 15)
                    FrButton("닫기", { onAction(UiAction.DevPanel(false)) }, style = ButtonStyle.PRIMARY, height = btnH, fontSize = 15, focusRequester = closeFocus)
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().background(FrColors.Panel2, RoundedCornerShape(10.dp)).padding(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                d.lines.forEach { BasicText(it, style = frText(13, color = FrColors.TextSoft)) }
                            }
                        }
                        Box(Modifier.fillMaxWidth().background(FrColors.Panel2, RoundedCornerShape(10.dp)).padding(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                BasicText("가족 폰 연결 · ${d.phoneConnected}대", style = frText(14, FontWeight.Bold))
                                BasicText(d.phoneUrl, style = frText(13, color = FrColors.AccentSoft))
                                BasicText("같은 Wi-Fi의 폰 브라우저에서 열기. 에뮬레이터는 tools/phone-link.ps1 실행 후 노트북 IP로", style = frText(12, color = FrColors.TextSub))
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1.25f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Section("주행") {
                            listOf(1, 5, 20, 60).forEach { m ->
                                DevButton("×$m", btnH, active = d.multiplier == m) { onAction(UiAction.DevSpeed(m)) }
                            }
                            DevButton(if (d.paused) "재생" else "일시정지", btnH, active = d.paused) { onAction(UiAction.DevPause(!d.paused)) }
                            DevButton(if (d.hold) "출발" else "세우기(P)", btnH, active = d.hold) { onAction(UiAction.DevHold(!d.hold)) }
                            DevButton("다음 경유지 직전으로", btnH) { onAction(UiAction.DevJumpToNextStop) }
                        }
                        Section("제안 주입") {
                            d.proposalOptions.forEach { o ->
                                DevButton("${o.member.displayName}→${o.placeName}", btnH) {
                                    onAction(UiAction.DevInject(FamilyEvent.Proposal(o.member.id, o.placeId, o.message)))
                                }
                            }
                        }
                        Section("투표 · ${d.voteTargetLabel ?: "대기 제안 없음"}") {
                            val id = d.voteTargetId
                            if (id != null) {
                                d.voters.forEach { v ->
                                    DevButton("${v.displayName} 찬성", btnH) { onAction(UiAction.DevInject(FamilyEvent.Vote(v.id, id, VoteChoice.YES))) }
                                    DevButton("${v.displayName} 반대", btnH) { onAction(UiAction.DevInject(FamilyEvent.Vote(v.id, id, VoteChoice.NO))) }
                                }
                            }
                        }
                        Section("급한 요청") {
                            FamilyMember.passengers.forEach { m ->
                                UrgentType.entries.forEach { type ->
                                    DevButton("${m.displayName} ${type.label}", btnH) {
                                        onAction(UiAction.DevInject(FamilyEvent.UrgentRequest(m.id, type)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(title, style = frText(13, FontWeight.Bold, FrColors.TextSub))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun DevButton(text: String, height: Dp, active: Boolean = false, onClick: () -> Unit) {
    FrButton(
        text, onClick,
        style = if (active) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
        height = height, fontSize = 14, radius = 10.dp,
    )
}
