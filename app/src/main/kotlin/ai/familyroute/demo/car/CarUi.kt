package ai.familyroute.demo.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.familyroute.demo.R
import ai.familyroute.demo.ui.theme.frText
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 차량 화면 공용 색. 휴대폰 화면(server/app.html)과 같은 값을 쓴다. */
object CarColors {
    val Navy = Color(0xFF002C5F)
    val Navy2 = Color(0xFF0B3F7A)
    val Ink = Color(0xFF1C3550)
    val Sub = Color(0xFF55687A)
    val Bg = Color(0xFFF3F5F8)
    val Tint = Color(0xFFEAF1F7)
    val Line = Color(0xFFDDE4EA)
    val Teal = Color(0xFF007FA3)
    val Ok = Color(0xFF00664C)
    val OkBg = Color(0xFFE4F5EE)
    val Bad = Color(0xFFA32216)
    val BadBg = Color(0xFFFDECEA)
    val Warm = Color(0xFFFFF3DC)
    val WarmInk = Color(0xFF774900)
    val OnNavySub = Color(0xFFCCE0F2)
}

@Composable
fun T(text: String, size: Int, weight: FontWeight = FontWeight.Normal, color: Color = CarColors.Navy, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier = modifier, style = frText(size, weight, color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

enum class Btn { Primary, Soft, Line, Danger, OnNavy, OnNavyStrong }

/** 차량용 큰 버튼. 높이 72dp 이상. */
@Composable
fun CarButton(label: String, modifier: Modifier = Modifier, kind: Btn = Btn.Primary, enabled: Boolean = true, icon: Int? = null, onClick: () -> Unit) {
    val (bg, fg) = when {
        !enabled -> Color(0xFFD9E0E5) to Color(0xFF55636F)
        kind == Btn.Soft -> CarColors.Tint to CarColors.Navy
        kind == Btn.Line -> Color.White to CarColors.Navy
        kind == Btn.Danger -> CarColors.BadBg to CarColors.Bad
        kind == Btn.OnNavy -> Color(0x29FFFFFF) to Color.White
        kind == Btn.OnNavyStrong -> Color.White to CarColors.Navy
        else -> CarColors.Navy to Color.White
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.heightIn(min = 72.dp).clip(shape).background(bg)
            .then(if (kind == Btn.Line && enabled) Modifier.border(2.dp, Color(0xFFB3C1CD), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
    ) {
        if (icon != null) Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(fg), modifier = Modifier.size(26.dp))
        T(label, 21, FontWeight.Bold, fg, maxLines = 1)
    }
}

@Composable
fun Tag(text: String, bg: Color = CarColors.Tint, fg: Color = CarColors.Teal) {
    T(text, 16, FontWeight.Bold, fg, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp), maxLines = 1)
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** 왼쪽 세로 메뉴의 한 칸. */
@Composable
fun NavItem(icon: Int, label: String, selected: Boolean, enabled: Boolean = true, badge: Int = 0, onClick: () -> Unit) {
    val fg = if (!enabled) Color(0xFF6F87A3) else Color.White
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 92.dp).clip(RoundedCornerShape(18.dp)).background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Tab, onClick = onClick).semantics { this.selected = selected }.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
        ) {
            Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(fg), modifier = Modifier.size(34.dp))
            T(label, 18, FontWeight.Bold, fg, maxLines = 1)
        }
        if (badge > 0) T(badge.toString(), 15, FontWeight.Bold, Color.White, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 10.dp).background(Color(0xFFD6382A), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

/** 들르는 곳과 도착지를 순서대로 보여 준다. 마지막 항목이 도착지다. */
@Composable
fun StopList(names: List<String>, onNavy: Boolean = false, notes: List<String> = emptyList()) {
    val main = if (onNavy) Color.White else CarColors.Navy
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        names.forEachIndexed { i, name ->
            val last = i == names.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).background(if (last) (if (onNavy) Color.White else CarColors.Navy) else (if (onNavy) Color(0x33FFFFFF) else CarColors.Tint), CircleShape), contentAlignment = Alignment.Center) {
                    if (last) Image(painterResource(R.drawable.ic_flag), contentDescription = "도착지", colorFilter = ColorFilter.tint(if (onNavy) CarColors.Navy else Color.White), modifier = Modifier.size(20.dp))
                    else T((i + 1).toString(), 18, FontWeight.Bold, main)
                }
                Column(Modifier.weight(1f)) {
                    T(name, 21, if (last) FontWeight.Bold else FontWeight.Medium, main, maxLines = 1)
                    val note = notes.getOrNull(i).orEmpty()
                    if (note.isNotBlank()) T(note, 16, color = if (onNavy) CarColors.OnNavySub else CarColors.Sub, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun Segmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFFE3E9EE), RoundedCornerShape(18.dp)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(13.dp)).background(if (on) Color.White else Color.Transparent).clickable(role = Role.Tab) { onSelect(i) }.semantics { selected = on }, contentAlignment = Alignment.Center) {
                T(label, 20, FontWeight.Bold, if (on) CarColors.Navy else CarColors.Sub, maxLines = 1)
            }
        }
    }
}

/** 누르면 켜지고 꺼지는 알약 모양 선택지. */
@Composable
fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    T(label, 19, FontWeight.Bold, if (on) Color.White else CarColors.Navy,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) CarColors.Navy else CarColors.Tint).clickable(role = Role.Checkbox, onClick = onClick).semantics { selected = on }.padding(horizontal = 22.dp, vertical = 19.dp), maxLines = 1)
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, placeholder: String, description: String, modifier: Modifier = Modifier, size: Int = 22, numeric: Boolean = false, onDone: () -> Unit = {}) {
    Box(modifier.heightIn(min = 64.dp).background(Color.White, RoundedCornerShape(16.dp)).border(2.dp, Color(0xFFB3C1CD), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) T(placeholder, size, color = Color(0xFF7D8E9E), maxLines = 1)
        BasicTextField(value, onChange, singleLine = true, textStyle = frText(size, FontWeight.Medium, CarColors.Navy),
            // 숫자 코드를 적을 때는 숫자 자판을 띄운다.
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Text, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
    }
}

@Composable
fun InviteQr(url: String, sizeDp: Int = 200) {
    val matrix = remember(url) { QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 200, 200) }
    Canvas(Modifier.size(sizeDp.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp).semantics { contentDescription = "가족 초대 QR 코드" }) {
        val x = size.width / matrix.width; val y = size.height / matrix.height
        for (row in 0 until matrix.height) for (col in 0 until matrix.width) if (matrix[col, row]) drawRect(Color.Black, Offset(col * x, row * y), Size(x + .3f, y + .3f))
    }
}

/** 지도 왼쪽에 뜨는 옆 패널. 지도는 오른쪽에 계속 보인다. */
@Composable
fun SidePanel(title: String, error: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight().background(CarColors.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            T(title, 28, FontWeight.Black, CarColors.Navy, Modifier.weight(1f), maxLines = 1)
            Box(Modifier.size(64.dp).clip(CircleShape).background(CarColors.Tint).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "닫고 지도로 돌아가기" }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_close), contentDescription = null, colorFilter = ColorFilter.tint(CarColors.Navy), modifier = Modifier.size(28.dp))
            }
        }
        if (error.isNotBlank()) T(error, 19, FontWeight.Bold, CarColors.Bad, Modifier.padding(horizontal = 24.dp).fillMaxWidth().background(CarColors.BadBg, RoundedCornerShape(14.dp)).padding(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** 화면 가운데 뜨는 확인 창. */
@Composable
fun CenterDialog(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3031429)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(.7f).background(CarColors.Bg, RoundedCornerShape(24.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
