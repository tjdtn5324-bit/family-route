package ai.familyroute.demo.domain

enum class PlaceKind { REST_AREA, SPOT }

/**
 * 들를 수 있는 장소. [routeKm]은 출발지에서 경로를 따라 잰 거리.
 * [detourMin]은 경로를 벗어났다 돌아오는 데 더 드는 시간(예산에서 차감),
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
) {
    /** 목적지 반경 5km 안이면 도착 후 일정으로 추천 */
    val nearDestination: Boolean get() = distanceToDestinationKm <= 5.0
}

/**
 * 임시 시나리오 데이터. Phase 2에서 assets/scenario.json(위경도 폴리라인 포함)으로 옮긴다.
 * 거리·시간은 가상의 값이다.
 */
object DemoCatalog {
    const val ORIGIN_NAME = "서울 집"
    const val DESTINATION_NAME = "강릉 경포해변"
    const val DESTINATION_SHORT = "강릉"
    const val ORIGIN_SHORT = "서울"
    const val TOTAL_KM = 230.0

    /** 경유지 없이 달릴 때 걸리는 시간(분). 평균 약 63km/h */
    const val DRIVE_MIN = 218

    val places: List<Place> = listOf(
        Place("cafe_dumulmeori", "두물머리 카페", "카페", "카페 · 영업 중", "커피", PlaceKind.SPOT, 45.0, 1.1, 8, 20, 185.0),
        Place("rest_hongcheon", "홍천 휴게소", "휴게소", "휴게소", "휴식", PlaceKind.REST_AREA, 90.0, 0.0, 6, 10, 140.0),
        Place("hanu_hoengseong", "횡성 한우 식당", "식당", "식당 · 영업 중", "점심", PlaceKind.SPOT, 128.0, 2.3, 12, 50, 102.0),
        Place("rest_hoengseong", "횡성 휴게소", "휴게소", "휴게소", "휴식", PlaceKind.REST_AREA, 135.0, 0.0, 4, 10, 95.0),
        Place("rest_pyeongchang", "평창 휴게소", "휴게소", "휴게소", "휴식", PlaceKind.REST_AREA, 172.0, 0.0, 5, 10, 58.0),
        Place("sheep_daegwallyeong", "대관령 양떼목장", "목장", "관광 · 체류 60분 예상", "관광", PlaceKind.SPOT, 205.0, 6.5, 35, 60, 25.0),
        Place("market_gangneung", "강릉 중앙시장", "시장", "시장 · 목적지에서 3km", "시장", PlaceKind.SPOT, 227.0, 1.0, 0, 40, 3.0),
    )

    fun place(id: String): Place? = places.firstOrNull { it.id == id }
}
