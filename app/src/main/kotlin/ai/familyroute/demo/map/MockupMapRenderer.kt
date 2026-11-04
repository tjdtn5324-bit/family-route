package ai.familyroute.demo.map

import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.NotoSansKr
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Phase 1 전용: 시안 Main.dc.html의 지도 SVG(1140×500)를 그대로 그린다.
 * Phase 2에서 시뮬레이터 위치를 따라가는 절차적 지도로 바뀐다.
 *
 * 화면이 시안보다 세로로 길면(Pleos 앱 영역) 폭에 맞추고 아래에 붙인 뒤
 * 직선 도로를 위로 연장한다. 시안보다 가로로 길면 SVG slice처럼 위아래를 자른다.
 */
object MockupMapRenderer : MapRenderer {
    private const val W = 1140f
    private const val H = 500f

    private val river = PathParser().parsePathString("M0 380 C200 340 320 440 520 395 S860 310 1140 350").toPath()
    private val mainRoad = PathParser().parsePathString("M0 260 C300 250 500 300 1140 270").toPath()
    private val route = PathParser()
        .parsePathString("M180 470 C260 380 300 300 420 260 S640 220 720 160 S900 90 1040 60").toPath()
    private val car = PathParser().parsePathString("M236 386 L250 418 L236 410 L222 418 Z").toPath()

    private val minorLines = listOf(
        Offset(0f, 120f) to Offset(1140f, 200f),
        Offset(300f, 0f) to Offset(360f, 500f),
        Offset(640f, 0f) to Offset(600f, 500f),
    )
    private val majorLines = listOf(Offset(860f, 0f) to Offset(980f, 500f))

    /** 제안 미리보기 점선(시안 Suggest.dc.html의 모양을 Main 좌표로 옮김) */
    private val previewPath = PathParser().parsePathString("M585 218 C640 252 720 276 790 268").toPath()
    private val previewSlot = Offset(790f, 268f)

    /** 시안의 마커 자리. 들를 곳 순서대로 채운다. */
    private val markerSlots = listOf(Offset(420f, 260f), Offset(720f, 160f), Offset(900f, 96f))

    @Composable
    override fun Render(state: MapUiState, modifier: Modifier) {
        val measurer = rememberTextMeasurer()
        val markers = remember(state) { state.markers.take(markerSlots.size) }
        Canvas(modifier) {
            drawRect(FrColors.MapBg)
            val s = size.width / W
            val ty = if (H * s > size.height) (size.height - H * s) / 2f else size.height - H * s
            withTransform({
                translate(0f, ty)
                scale(s, s, pivot = Offset.Zero)
            }) {
                drawPath(river, FrColors.MapRiver, style = Stroke(44f))
                minorLines.forEach { drawExtendedLine(it, FrColors.MapRoadMinor, 5f) }
                drawPath(mainRoad, FrColors.MapRoad, style = Stroke(9f))
                majorLines.forEach { drawExtendedLine(it, FrColors.MapRoad, 9f) }
                drawPath(route, FrColors.Bg, style = Stroke(22f, cap = StrokeCap.Round))
                drawPath(route, FrColors.Accent, style = Stroke(12f, cap = StrokeCap.Round))

                markers.forEachIndexed { i, m -> drawMarker(measurer, markerSlots[i], m) }
                state.preview?.let { member ->
                    drawPath(
                        previewPath, member.color,
                        style = Stroke(7f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                    drawMarker(measurer, previewSlot, MapMarker(member, confirmed = true))
                }

                drawRoundRect(
                    FrColors.Text,
                    topLeft = Offset(1028f, 40f),
                    size = Size(26f, 26f),
                    cornerRadius = CornerRadius(6f),
                )
                drawCircle(FrColors.Accent.copy(alpha = 0.25f), 26f, Offset(236f, 404f))
                drawPath(car, FrColors.Text)
            }
        }
    }

    /** 두 점을 지나는 직선을 화면 밖까지 연장해서 그린다. */
    private fun DrawScope.drawExtendedLine(line: Pair<Offset, Offset>, color: androidx.compose.ui.graphics.Color, width: Float) {
        val (a, b) = line
        val d = b - a
        drawLine(color, a - d * 8f, b + d * 8f, strokeWidth = width)
    }

    private fun DrawScope.drawMarker(measurer: TextMeasurer, c: Offset, m: MapMarker) {
        if (m.confirmed) {
            drawCircle(m.member.color, 20f, c)
            drawCircle(FrColors.Bg, 20f, c, style = Stroke(4f))
        } else {
            drawCircle(FrColors.Bg, 20f, c)
            drawCircle(
                m.member.color, 20f, c,
                style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))),
            )
        }
        val style = TextStyle(
            fontFamily = NotoSansKr,
            fontWeight = FontWeight.Bold,
            fontSize = pxAsSp(16f),
            color = if (m.confirmed) FrColors.Bg else m.member.color,
        )
        val layout = measurer.measure(m.member.initial, style)
        drawText(layout, topLeft = c - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }

    /** 캔버스 변환 안에서 글자 크기를 시안 단위로 맞추려고 px를 sp로 바꾼다. */
    private fun DrawScope.pxAsSp(px: Float): TextUnit = (px / (density * fontScale)).sp
}
