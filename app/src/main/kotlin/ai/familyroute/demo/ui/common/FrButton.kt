package ai.familyroute.demo.ui.common

import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.frText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonStyle { PRIMARY, SECONDARY, OUTLINE }

/**
 * 시안의 버튼 3종. 터치와 DPAD(포커스 이동 + 가운데·Enter) 모두로 누를 수 있고
 * 포커스를 받으면 밝은 테두리가 생긴다.
 */
@Composable
fun FrButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    height: Dp = 76.dp,
    fontSize: Int = 20,
    radius: Dp = 16.dp,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(radius)
    val textColor = when (style) {
        ButtonStyle.PRIMARY -> FrColors.OnAccent
        ButtonStyle.SECONDARY -> FrColors.Text
        ButtonStyle.OUTLINE -> FrColors.TextSoft
    }
    val base = modifier
        .height(height)
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
        .focusRing(shape)
    val styled = when (style) {
        ButtonStyle.PRIMARY -> base.background(FrColors.Accent, shape)
        ButtonStyle.SECONDARY -> base.background(FrColors.Line, shape)
        ButtonStyle.OUTLINE -> base.border(1.5.dp, FrColors.Outline, shape)
    }
    Row(
        styled
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        BasicText(text, style = frText(fontSize, FontWeight.Bold, textColor).copy(textAlign = TextAlign.Center))
        trailing?.invoke(this)
    }
}
