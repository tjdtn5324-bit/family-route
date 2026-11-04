package ai.familyroute.demo.ui.dev

import ai.familyroute.demo.domain.FamilyEvent
import ai.familyroute.demo.domain.Gear
import ai.familyroute.demo.domain.UrgentType
import ai.familyroute.demo.domain.VoteChoice
import ai.familyroute.demo.model.DevUi
import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.ui.UiAction
import ai.familyroute.demo.ui.common.ButtonStyle
import ai.familyroute.demo.ui.common.FrButton
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp

/**
 * 개발자 패널(운전자 아바타 길게 누르기). 데모 녹화 때는 열지 않는다.
 * 기어 전환과 위치 점프는 시뮬레이터(Phase 2)가 붙기 전 임시 조작이다.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DevPanel(d: DevUi, onAction: (UiAction) -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC05080F))
            // 바깥을 눌러도 아래 화면이 눌리지 않게 막는다
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 1100.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.94f)
                .background(FrColors.Panel, RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("개발자 패널", style = frText(24, FontWeight.Black))
                Spacer(Modifier.weight(1f))
                FrButton("리셋", { onAction(UiAction.DevReset) }, style = ButtonStyle.OUTLINE, height = 52.dp, fontSize = 17)
                Spacer(Modifier.padding(6.dp))
                FrButton("닫기", { onAction(UiAction.DevPanel(false)) }, style = ButtonStyle.PRIMARY, height = 52.dp, fontSize = 17, focusRequester = closeFocus)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Section("상태") {
                    Column(
                        Modifier.fillMaxWidth().background(FrColors.Panel2, RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        d.lines.forEach { BasicText(it, style = frText(15, color = FrColors.TextSoft)) }
                    }
                }
                Section("차량 (시뮬레이터 전 임시)") {
                    DevButton("기어 P (정차)", d.gear == Gear.P) { onAction(UiAction.DevGear(Gear.P)) }
                    DevButton("기어 D (주행)", d.gear == Gear.D) { onAction(UiAction.DevGear(Gear.D)) }
                    DevButton("다음 경유지로 점프") { onAction(UiAction.DevJumpToNextStop) }
                }
                Section("제안 주입") {
                    d.proposalOptions.forEach { o ->
                        DevButton("${o.member.displayName} → ${o.placeName}") {
                            onAction(UiAction.DevInject(FamilyEvent.Proposal(o.member.id, o.placeId, o.message)))
                        }
                    }
                }
                Section("투표 주입 · ${d.voteTargetLabel ?: "대기 중인 제안 없음"}") {
                    val id = d.voteTargetId
                    if (id != null) {
                        d.voters.forEach { v ->
                            DevButton("${v.displayName} 찬성") { onAction(UiAction.DevInject(FamilyEvent.Vote(v.id, id, VoteChoice.YES))) }
                            DevButton("${v.displayName} 반대") { onAction(UiAction.DevInject(FamilyEvent.Vote(v.id, id, VoteChoice.NO))) }
                        }
                    }
                }
                Section("급한 요청 주입") {
                    FamilyMember.passengers.forEach { m ->
                        UrgentType.entries.forEach { type ->
                            DevButton("${m.displayName} ${type.label}") {
                                onAction(UiAction.DevInject(FamilyEvent.UrgentRequest(m.id, type)))
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, style = frText(16, FontWeight.Bold, FrColors.TextSub))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun DevButton(text: String, active: Boolean = false, onClick: () -> Unit) {
    FrButton(
        text, onClick,
        style = if (active) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
        height = 52.dp, fontSize = 16, radius = 12.dp,
    )
}
