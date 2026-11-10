package ai.familyroute.demo.domain

import ai.familyroute.demo.route.Geo
import ai.familyroute.demo.route.Pt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalTime

/** 이벤트 JSON 규칙. UrgentRequest가 `type` 필드를 가져서 구분자는 `kind`를 쓴다. */
val FamilyJson = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
}

@Serializable
data class ScenarioJson(
    val seed: Long,
    val startTime: String,
    val origin: NameJson,
    val destination: NameJson,
    val route: List<List<Double>>,
    val segments: List<SegmentJson>,
    val turnTexts: List<TurnTextJson>,
    val places: List<PlaceJson>,
    val initialAccepted: List<InitialJson>,
    val script: List<ScriptJson>,
)

@Serializable data class NameJson(val name: String, val shortName: String)
@Serializable data class SegmentJson(val fromIndex: Int, val road: String, val limitKph: Int)
@Serializable data class TurnTextJson(val lat: Double, val lon: Double, val text: String)
@Serializable data class InitialJson(val memberId: String, val placeId: String)
@Serializable data class ScriptJson(val atKm: Double, val event: FamilyEvent)

@Serializable
data class PlaceJson(
    val id: String, val name: String, val shortName: String, val category: String, val purpose: String,
    val kind: String, val lat: Double, val lon: Double,
    val detourMin: Int, val dwellMin: Int, val offRouteKm: Double, val routeKm: Double,
    val distanceToDestinationKm: Double, val detour: DetourJson,
)

@Serializable
data class DetourJson(val exitIndex: Int, val mergeIndex: Int, val stopIndex: Int, val via: List<List<Double>>)

/** 경로 이탈·합류 지점(기본 경로 인덱스)과 그 사이 우회 폴리라인 */
data class Detour(val exitIndex: Int, val mergeIndex: Int, val stopIndex: Int, val via: List<Pt>)

/** 기본 경로 한 점 */
data class BasePoint(val pt: Pt, val road: String, val limitKph: Int)

/** 시나리오 전체(assets/scenario.json). 실제 길찾기 결과가 아닌 가상 데이터. */
class Scenario(json: ScenarioJson) {
    val seed = json.seed
    val start: LocalTime = LocalTime.parse(json.startTime)
    val originName = json.origin.name
    val originShort = json.origin.shortName
    val destinationName = json.destination.name
    val destinationShort = json.destination.shortName

    val base: List<BasePoint> = json.route.mapIndexed { i, ll ->
        val seg = json.segments.last { it.fromIndex <= i }
        BasePoint(Geo.toPt(ll[0], ll[1]), seg.road, seg.limitKph)
    }

    val turnTexts: List<Pair<Pt, String>> = json.turnTexts.map { Geo.toPt(it.lat, it.lon) to it.text }

    val places: List<Place> = json.places.map { p ->
        Place(
            id = p.id, name = p.name, shortName = p.shortName, category = p.category, purpose = p.purpose,
            kind = PlaceKind.valueOf(p.kind), routeKm = p.routeKm, offRouteKm = p.offRouteKm,
            detourMin = p.detourMin, dwellMin = p.dwellMin, distanceToDestinationKm = p.distanceToDestinationKm,
            pt = Geo.toPt(p.lat, p.lon),
            detour = Detour(p.detour.exitIndex, p.detour.mergeIndex, p.detour.stopIndex, p.detour.via.map { Geo.toPt(it[0], it[1]) }),
        )
    }

    val initialAccepted = json.initialAccepted
    val script = json.script

    fun place(id: String): Place? = places.firstOrNull { it.id == id }

    val startClockMs: Long get() = start.toSecondOfDay() * 1000L

    companion object {
        fun parse(text: String) = Scenario(FamilyJson.decodeFromString(ScenarioJson.serializer(), text))
    }
}
