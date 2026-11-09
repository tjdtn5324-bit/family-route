package ai.familyroute.demo.ui.common

import ai.familyroute.demo.model.FamilyMember
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 가족 아바타. 색과 이름 첫 글자를 항상 함께 보여준다.
 * [pending]이면 시안처럼 점선 테두리 + 가족 색 글자.
 */
@Composable
fun Avatar(member: FamilyMember, size: Dp, fontSize: Int, modifier: Modifier = Modifier, pending: Boolean = false) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (pending) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 2.dp.toPx()
                drawCircle(
                    member.color,
                    radius = (this.size.minDimension - w) / 2f,
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
        } else {
            Box(Modifier.fillMaxSize().background(member.color, CircleShape))
        }
        BasicText(
            member.initial,
            style = frText(fontSize, FontWeight.Bold, if (pending) member.color else member.onColor),
        )
    }
}

/**
 * DPAD 포커스 표시. 키 조작 중에 포커스를 받으면 밝은 테두리를 그린다.
 * 터치 중에는 그리지 않는다: 터치 모드에서 첫 DPAD 키는 Android가 "키 조작 모드 전환"에 쓰므로,
 * 그때 테두리가 나타나고 다음 키부터 이동하는 표준 동작과 맞춘다.
 * clickable/focusable 보다 앞에 둬야 한다.
 */
fun Modifier.focusRing(shape: Shape, width: Dp = 3.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    onFocusChanged { focused = it.isFocused }
        .then(if (focused && keyboard) Modifier.border(width, FrColors.Text, shape) else Modifier)
}
