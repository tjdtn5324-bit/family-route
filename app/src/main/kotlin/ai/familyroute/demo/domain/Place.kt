package ai.familyroute.demo.domain

import ai.familyroute.demo.route.Pt

enum class PlaceKind { REST_AREA, SPOT }

/**
 * 들를 수 있는 장소. [routeKm]은 기본 경로에서 가장 가까운 지점까지의 거리.
 * [detourMin]은 가족에게 보여 주는 우회 시간(예산에서 차감),
 * [dwellMin]은 머무는 시간(예산과 별도, 도착 시각에는 포함).
 */
data class Place(
    val id: String,
    val name: String,
    val shortName: String,
    val category: String,
    val purpose: String,
    val kind: PlaceKind,
    val routeKm: Double,
    val offRouteKm: Double,
    val detourMin: Int,
    val dwellMin: Int,
    val distanceToDestinationKm: Double,
    val pt: Pt,
    val detour: Detour,
) {
    /** 목적지 반경 5km 안이면 도착 후 일정으로 추천 */
    val nearDestination: Boolean get() = distanceToDestinationKm <= 5.0
}
