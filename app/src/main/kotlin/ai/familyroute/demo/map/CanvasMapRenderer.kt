package ai.familyroute.demo.map

import ai.familyroute.demo.domain.Scenario
import ai.familyroute.demo.route.ActiveRoute
import ai.familyroute.demo.route.Geo
import ai.familyroute.demo.route.Pt
import ai.familyroute.demo.ui.theme.FrColors
import ai.familyroute.demo.ui.theme.NotoSansKr
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Compose Canvas로 그리는 스타일라이즈드 다크 지도(오프라인).
 * 차량은 화면 아래 1/3 지점에 고정, 진행 방향이 위(heading-up). 지나온 길은 회색, 남은 길은 강조색.
 * 차량 상태는 10Hz로 오지만 화면은 프레임마다 보간해서 부드럽게 따라간다.
 */
class CanvasMapRenderer(scenario: Scenario) : MapRenderer {
    private val decor: List<Pair<DecorLine, Path>> = WorldDecor.generate(scenario).map { it to pathOf(it.pts) }

    @Composable
    override fun Render(state: MapUiState, modifier: Modifier) {
        val measurer = rememberTextMeasurer()
        val latest by rememberUpdatedState(state)
        var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
        var shown by remember { mutableDoubleStateOf(state.progressM) }
        var heading by remember { mutableDoubleStateOf(state.route.headingAt(state.progressM, 60.0)) }
        var mpd by remember { mutableDoubleStateOf(2.0) }
        var frame by remember { mutableLongStateOf(0L) }

        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { t ->
                    val dt = if (last == 0L) 0.016 else ((t - last) / 1e9).coerceIn(0.0, 0.1)
                    last = t
                    val s = latest
                    val diff = s.progressM - shown
                    shown = if (abs(diff) > 3000) s.progressM else shown + diff * min(1.0, dt / 0.12)
                    shown = shown.coerceIn(0.0, s.route.length)
                    heading = lerpAngle(heading, s.route.headingAt(shown, 60.0), min(1.0, dt / 0.35))
                    // 빠를수록 넓게 보고, 제안 미리보기는 우회 경로가 다 보이게
                    var target = 1.6 + 1.8 * (s.speedMps / 27.0).coerceIn(0.0, 1.0)
                    s.preview?.let { pv ->
                        val car = s.route.pointAt(shown)
                        val far = (pv.path + pv.pt).maxOf { it.dist(car) }
                        val px = min(canvasSize.width, canvasSize.height).toDouble().coerceAtLeast(1.0)
                        target = max(target, far * 1.35 / (px * 0.6 / densityGuess))
                    }
                    mpd += (target.coerceAtMost(40.0) - mpd) * min(1.0, dt / 0.8)
                    frame = t
                }
            }
        }

        Canvas(modifier.onSizeChanged { canvasSize = it }) {
            frame // 프레임마다 다시 그리기
            densityGuess = density.toDouble()
            drawMap(latest, shown, heading, mpd, measurer)
        }
    }

    private var densityGuess = 1.0

    private fun DrawScope.drawMap(s: MapUiState, shown: Double, heading: Double, mpd: Double, measurer: TextMeasurer) {
        drawRect(FrColors.MapBg)
        val route = s.route
        val car = route.pointAt(shown)
        val scale = (density / mpd).toFloat() // px per m
        val anchor = Offset(size.width / 2f, size.height * 2f / 3f)
        val radius = hypot(size.width.toDouble(), size.height.toDouble()) / scale
        val view = Bounds(car.x - radius, car.y - radius, car.x + radius, car.y + radius)
        fun w(dp: Float) = dp * density / scale

        withTransform({
            translate(anchor.x, anchor.y)
            rotate(-heading.toFloat(), Offset.Zero)
            scale(scale, scale, Offset.Zero)
            translate(-car.x.toFloat(), -car.y.toFloat())
        }) {
            for ((line, path) in decor) {
                if (!line.bounds.intersects(view)) continue
                when (line.kind) {
                    DecorKind.RIVER -> drawPath(path, FrColors.MapRiver, style = Stroke(w(44f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    DecorKind.MAJOR -> drawPath(path, FrColors.MapRoad, style = Stroke(w(9f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    DecorKind.MINOR -> drawPath(path, FrColors.MapRoadMinor, style = Stroke(w(5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            val passed = routePath(route, shown - radius, shown)
            val ahead = routePath(route, shown, shown + radius)
            drawPath(passed, FrColors.Bg, style = Stroke(w(20f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(ahead, FrColors.Bg, style = Stroke(w(22f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(passed, FrColors.RoutePassed, style = Stroke(w(10f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(ahead, FrColors.Accent, style = Stroke(w(12f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            s.preview?.let { pv ->
                drawPath(
                    pathOf(pv.path),
                    pv.member.color,
                    style = Stroke(w(7f), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w(10f), w(8f)))),
                )
            }
        }

        fun toScreen(p: Pt): Offset {
            val dx = (p.x - car.x) * scale
            val dy = (p.y - car.y) * scale
            val a = Math.toRadians(-heading)
            return Offset((anchor.x + dx * cos(a) - dy * sin(a)).toFloat(), (anchor.y + dx * sin(a) + dy * cos(a)).toFloat())
        }
        fun visible(o: Offset) = o.x > -60 && o.y > -60 && o.x < size.width + 60 && o.y < size.height + 60

        val dest = toScreen(s.destination)
        if (visible(dest)) {
            val r = 13 * density
            drawRoundRect(FrColors.Text, Offset(dest.x - r, dest.y - r), Size(2 * r, 2 * r), CornerRadius(6 * density))
        }
        s.markers.forEach { m -> toScreen(m.pt).takeIf(::visible)?.let { drawMarker(measurer, it, m.member.color, m.member.onColor, m.member.initial, m.confirmed) } }
        s.preview?.let { pv -> toScreen(pv.pt).takeIf(::visible)?.let { drawMarker(measurer, it, pv.member.color, pv.member.onColor, pv.member.initial, true) } }

        // 차량(항상 위를 향함)
        drawCircle(FrColors.Accent.copy(alpha = 0.25f), 26 * density, anchor)
        val k = density
        val arrow = Path().apply {
            moveTo(anchor.x, anchor.y - 18 * k)
            lineTo(anchor.x + 14 * k, anchor.y + 14 * k)
            lineTo(anchor.x, anchor.y + 6 * k)
            lineTo(anchor.x - 14 * k, anchor.y + 14 * k)
            close()
        }
        drawPath(arrow, FrColors.Text)
    }

    private fun DrawScope.drawMarker(measurer: TextMeasurer, c: Offset, color: Color, onColor: Color, initial: String, confirmed: Boolean) {
        val r = 20 * density
        if (confirmed) {
            drawCircle(color, r, c)
            drawCircle(FrColors.Bg, r, c, style = Stroke(4 * density))
        } else {
            drawCircle(FrColors.Bg, r, c)
            drawCircle(color, r, c, style = Stroke(4 * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5 * density, 4 * density))))
        }
        val layout = measurer.measure(
            initial,
            TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (confirmed) onColor else color),
        )
        drawText(layout, topLeft = c - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }

    private fun routePath(route: ActiveRoute, from: Double, to: Double): Path {
        val a = from.coerceIn(0.0, route.length)
        val b = to.coerceIn(0.0, route.length)
        val path = Path()
        if (b <= a) return path
        val start = route.pointAt(a)
        path.moveTo(start.x.toFloat(), start.y.toFloat())
        var i = route.indexAt(a) + 1
        while (i < route.points.size && route.cum[i] < b) {
            val p = route.points[i].pt
            path.lineTo(p.x.toFloat(), p.y.toFloat())
            i++
        }
        val end = route.pointAt(b)
        path.lineTo(end.x.toFloat(), end.y.toFloat())
        return path
    }

    private fun pathOf(pts: List<Pt>): Path = Path().apply {
        pts.firstOrNull()?.let { moveTo(it.x.toFloat(), it.y.toFloat()) }
        pts.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
    }

    private fun lerpAngle(from: Double, to: Double, t: Double): Double = (from + Geo.angleDiff(from, to) * t + 360.0) % 360.0
}
