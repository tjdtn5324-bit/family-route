package ai.familyroute.demo.ui.theme

import ai.familyroute.demo.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Noto Sans KR(OFL). 굵기별 고정 파일을 쓴다.
 * 가변 폰트 한 파일에 굵기 설정을 거는 방식은 Pleos AVD에서 900이 700처럼 얇게 나와서 바꿨다.
 * 파일은 원본 가변 폰트에서 wght 400/500/700/900을 뽑고 한자를 뺀 것(docs/licenses 참고).
 */
val NotoSansKr = FontFamily(
    Font(R.font.noto_sans_kr_regular, FontWeight.Normal),
    Font(R.font.noto_sans_kr_medium, FontWeight.Medium),
    Font(R.font.noto_sans_kr_bold, FontWeight.Bold),
    Font(R.font.noto_sans_kr_black, FontWeight.Black),
)

/** 글자 크기는 시안 px를 그대로 sp로 옮긴다(시안 1px = 1dp). */
fun frText(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: androidx.compose.ui.graphics.Color = FrColors.Text,
    tight: Boolean = false,
) = TextStyle(
    fontFamily = NotoSansKr,
    fontSize = size.sp,
    fontWeight = weight,
    color = color,
    // 시안의 line-height: 1.1 (큰 숫자)
    lineHeight = if (tight) 1.1.em else TextStyle.Default.lineHeight,
    lineHeightStyle = if (tight) {
        LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    } else {
        null
    },
)
