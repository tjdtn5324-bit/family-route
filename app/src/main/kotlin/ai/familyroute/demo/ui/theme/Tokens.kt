package ai.familyroute.demo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 디자인 토큰 — 딥 네이비 (현대자동차 색 계열).
 * Hyundai Blue #002C5F를 어둡게 뽑은 남색 바탕, Active Blue #00AAD2 강조, Light Sand #F6F3F2 글자,
 * Sand 계열 경고. 배치·크기는 design-ref 시안 그대로이고 색만 바꿨다.
 * (#002C5F 외 값은 공식 가이드로 확인하지 않은 알려진 값)
 */
object FrColors {
    val HyundaiBlue = Color(0xFF002C5F)
    val ActiveBlue = Color(0xFF00AAD2)

    val Bg = Color(0xFF061528)
    val Panel = Color(0xFF0C2240)
    val Panel2 = Color(0xFF12305A)
    val Line = Color(0xFF1E3A63)
    val Outline = Color(0xFF3A5680)
    val Text = Color(0xFFF6F3F2)
    val TextSub = Color(0xFF9FB3C8)
    val Accent = ActiveBlue
    /** 강조색 위 글자: 흰색은 Active Blue 위에서 대비가 낮아 Hyundai Blue를 쓴다(대비 약 5:1) */
    val OnAccent = HyundaiBlue
    val AccentSoft = Color(0xFF7FD3E8)
    val Warn = Color(0xFFE8B97E)
    val Ok = Color(0xFF34D399)

    /** 하단 가족 바 배경 */
    val Bar = Color(0xFF081B33)
    /** 지도 위 칩·알약의 글자 */
    val TextSoft = Color(0xFFD3DCE6)
    /** 제안 보기 버튼 배지 */
    val BadgeBg = HyundaiBlue
    val BadgeText = Color(0xFFFFFFFF)

    val MapBg = Color(0xFF0A1C33)
    val MapRoad = Color(0xFF1B3557)
    val MapRoadMinor = Color(0xFF132A48)
    val MapRiver = Color(0xFF0D3050)
    val RoutePassed = Color(0xFF4A5D78)

    /** 정차 비교 화면 안내 띠 */
    val WarnBannerBg = Color(0xFF3A2C1C)
    val WarnBannerText = Color(0xFFF1D3B0)
    val InfoBannerBg = Color(0xFF0D3050)
    val InfoBannerText = Color(0xFFBFE6F3)
}
