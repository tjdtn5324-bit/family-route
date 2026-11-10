package ai.familyroute.demo.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import ai.familyroute.demo.BuildConfig

data class LiveProposal(val id: String, val place: String, val member: String, val message: String, val votes: Int, val latitude: Double, val longitude: Double, val address: String, val type: String, val courseStops: String = "")
data class LiveRoom(val invite: String, val driverToken: String, val name: String = "")
data class LiveRoute(val pointsJson: String, val distance: Int, val duration: Int, val guidance: String, val destinationName: String, val stopsJson: String = "[]")
data class RoomPreferences(val companions: Set<String> = emptySet(), val styles: Set<String> = emptySet(), val hours: Int? = null)
data class LiveWaypoint(val id: String, val name: String)
data class FamilyPhoto(val id: String, val memberName: String)
data class LiveRoomState(val proposals: List<LiveProposal>, val route: LiveRoute?, val preferences: RoomPreferences = RoomPreferences(), val waypoints: List<LiveWaypoint> = emptyList(), val itineraryVersion: Int = -1, val photos: List<FamilyPhoto> = emptyList(), val archived: Boolean = false, val roomName: String = "")
data class SearchPlace(val name: String, val address: String, val latitude: Double, val longitude: Double)
data class SharedCourse(val id: String, val title: String, val stops: String, val uses: Int, val matchReason: String = "", val isSample: Boolean = false, val description: String = "", val destination: SearchPlace? = null, val likes: Int = 0, val rating: Double? = null, val reviewCount: Int = 0, val trending: Boolean = false, val official: Boolean = false, val region: String = "", val sourceName: String = "", val sourceKind: String = "")
data class CourseReview(val name: String, val rating: Int, val text: String)
/** 요즘 활동이 많은 지역 이름. active가 false면 아직 순위를 매길 만큼 활동이 없다는 뜻이다. */
data class TrendingRegions(val active: Boolean, val names: List<String>)

/** 가족방과 앱 자체 경로 서비스를 연결한다. */
class FamilyRoomClient(private val server: String = BuildConfig.FAMILY_SERVER_URL.trimEnd('/')) {
    fun homeUrl(): String? = if (server.startsWith("https://")) "$server/" else null
    fun inviteUrl(room: LiveRoom): String? = if (server.startsWith("https://")) "$server/#invite=${room.invite}" else null
    fun localInviteUrl(room: LiveRoom): String = "http://127.0.0.1:8789/#invite=${room.invite}"
    private suspend fun request(path: String, token: String? = null, body: JSONObject? = null, method: String = if (body != null) "POST" else "GET"): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("$server$path").openConnection() as HttpURLConnection
        try {
            // 길찾기는 서버의 외부 API 제한 시간(12초)보다 여유 있게 기다린다.
            c.connectTimeout = 5000; c.readTimeout = 20000; c.requestMethod = method
            c.setRequestProperty("Accept", "application/json")
            if (path in listOf("/api/rooms", "/api/rooms/claim") && BuildConfig.FAMILY_ROOM_CREATE_SECRET.isNotEmpty()) c.setRequestProperty("X-Room-Create-Secret", BuildConfig.FAMILY_ROOM_CREATE_SECRET)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("가족방 서버 요청 실패 (${c.responseCode})")
            JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { c.disconnect() }
    }

    suspend fun create(): LiveRoom {
        val obj = request("/api/rooms", body = JSONObject().put("name", "가족 여행"))
        return LiveRoom(obj.getString("invite"), obj.getString("driverToken"))
    }

    suspend fun claim(code: String): LiveRoom {
        val obj = request("/api/rooms/claim", body = JSONObject().put("code", code))
        return LiveRoom(obj.getString("invite"), obj.getString("driverToken"))
    }

    suspend fun state(room: LiveRoom): LiveRoomState {
        val response = request("/api/state", room.driverToken)
        val items = response.getJSONArray("proposals")
        val proposals = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("status") == "PENDING" }.map {
            val place = it.getJSONObject("place")
            val course = it.optJSONObject("course")
            val stops = course?.optJSONArray("waypoints")
            val names = if (stops != null) (0 until stops.length()).map { i -> stops.getJSONObject(i).getString("name") } + place.getString("name") else emptyList()
            LiveProposal(it.getString("id"), place.getString("name"), it.getJSONObject("member").getString("name"), it.optString("message"), it.getJSONObject("votes").length(), place.getDouble("latitude"), place.getDouble("longitude"), place.optString("address"), it.optString("type", "WAYPOINT"), names.joinToString(" → "))
        }
        val r = response.optJSONObject("route")
        val route = r?.let { LiveRoute(it.getJSONArray("points").toString(), it.getInt("distance"), it.getInt("duration"), it.optJSONArray("guides")?.optJSONObject(1)?.optString("text") ?: "경로 안내", it.optString("destinationName", "목적지"), response.optJSONObject("itinerary")?.optJSONArray("waypoints")?.toString() ?: "[]") }
        val prefs = response.optJSONObject("preferences")
        fun values(name: String): Set<String> { val a = prefs?.optJSONArray(name) ?: return emptySet(); return (0 until a.length()).map { a.getString(it) }.toSet() }
        val preferences = RoomPreferences(values("companions"), values("styles"), if (prefs == null || prefs.isNull("hours")) null else prefs.optInt("hours"))
        val stops = response.optJSONObject("itinerary")?.optJSONArray("waypoints")
        val waypoints = if (stops == null) emptyList() else (0 until stops.length()).map { stops.getJSONObject(it) }.mapNotNull { val id = it.optString("proposalId"); if (id.isBlank()) null else LiveWaypoint(id, it.getString("name")) }
        val pictures = response.optJSONArray("photos")
        val photos = if (pictures == null) emptyList() else (0 until pictures.length()).map { pictures.getJSONObject(it) }.map { FamilyPhoto(it.getString("id"), it.optString("memberName", "가족")) }
        return LiveRoomState(proposals, route, preferences, waypoints, response.optJSONObject("itinerary")?.optInt("version", -1) ?: -1, photos, response.optBoolean("archived", false), response.optJSONObject("room")?.optString("name") ?: "")
    }

    suspend fun addToRoute(room: LiveRoom, id: String) { request("/api/proposals/$id/route", room.driverToken, JSONObject()) }
    suspend fun deletePhoto(room: LiveRoom, id: String) { request("/api/photos/$id", room.driverToken, method = "DELETE") }
    suspend fun photo(room: LiveRoom, id: String): ByteArray = android.util.Base64.decode(request("/api/photos/$id", room.driverToken).getString("data"), android.util.Base64.DEFAULT)
    suspend fun finishTrip(room: LiveRoom, choice: String) { request("/api/trip/finish", room.driverToken, JSONObject().put("choice", choice)) }
    suspend fun updateWaypoints(room: LiveRoom, ids: List<String>, version: Int) { request("/api/itinerary/waypoints", room.driverToken, JSONObject().put("ids", JSONArray(ids)).put("version", version)) }

    suspend fun search(room: LiveRoom, query: String): List<SearchPlace> {
        val items = request("/api/places?q=${URLEncoder.encode(query, "UTF-8")}", room.driverToken).getJSONArray("places")
        return (0 until items.length()).map { items.getJSONObject(it) }.map { SearchPlace(it.getString("name"), it.optString("address"), it.getDouble("latitude"), it.getDouble("longitude")) }
    }

    suspend fun setDestination(room: LiveRoom, place: SearchPlace) {
        val p = JSONObject().put("name", place.name).put("address", place.address).put("latitude", place.latitude).put("longitude", place.longitude)
        request("/api/destination", room.driverToken, JSONObject().put("place", p))
    }

    suspend fun savePreferences(room: LiveRoom, companions: Set<String>, styles: Set<String>, hours: Int?) {
        val data = JSONObject().put("companions", JSONArray(companions.toList()))
            .put("styles", JSONArray(styles.toList())).put("hours", hours ?: JSONObject.NULL)
        request("/api/preferences", room.driverToken, data)
    }

    /** view: "sort=trending", "sort=popular&kind=community", "kind=official&sort=trending" 처럼 서버의 보는 순서를 그대로 넘긴다. */
    suspend fun publicCourses(query: String = "", view: String = ""): List<SharedCourse> = parseCourses(request("/api/public/courses?q=${URLEncoder.encode(query, "UTF-8")}${if (view.isBlank()) "" else "&$view"}").getJSONArray("courses"))

    suspend fun trendingRegions(): TrendingRegions {
        val items = request("/api/public/trending").getJSONArray("regions")
        val regions = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("region").isNotBlank() }
        return TrendingRegions(regions.any { it.optDouble("score", 0.0) > 0 }, regions.map { it.getString("region") })
    }

    suspend fun reviews(course: SharedCourse): List<CourseReview> {
        val items = request("/api/courses/${course.id}/reviews").getJSONArray("reviews")
        return (0 until items.length()).map { items.getJSONObject(it) }.map { CourseReview(it.optString("name", "여행자"), it.optInt("rating"), it.optString("text")) }
    }

    suspend fun recommendations(room: LiveRoom, place: SearchPlace): List<SharedCourse> = parseCourses(request("/api/recommended-courses?latitude=${place.latitude}&longitude=${place.longitude}", room.driverToken).getJSONArray("courses"))

    private fun parseCourses(items: JSONArray): List<SharedCourse> {
        return (0 until items.length()).map { items.getJSONObject(it) }.map { c ->
            val stops = c.getJSONArray("waypoints")
            val names = (0 until stops.length()).map { stops.getJSONObject(it).getString("name") } + c.getJSONObject("destination").getString("name")
            val matched = c.optJSONObject("matchedPreferences")
            val labels = mapOf("CHILD" to "아이와 함께", "PARENTS" to "부모님과 함께", "PET" to "반려동물과 함께", "FOOD" to "맛있는 여행", "RELAX" to "여유로운 여행", "ACTIVITY" to "즐거운 체험")
            val reasons = listOf("companions", "styles").flatMap { key -> val a = matched?.optJSONArray(key); if (a == null) emptyList() else (0 until a.length()).mapNotNull { labels[a.optString(it)] } }.toMutableList()
            if (matched != null && !matched.isNull("hours")) reasons.add("${matched.optInt("hours")}시간")
            SharedCourse(c.getString("id"), c.getString("title"), names.joinToString(" → "), c.optInt("uses"), reasons.joinToString(" · "), c.optBoolean("sample", false), c.optString("description"), c.getJSONObject("destination").let { SearchPlace(it.getString("name"), it.optString("address"), it.getDouble("latitude"), it.getDouble("longitude")) }, c.optInt("likes"), if (c.isNull("rating")) null else c.optDouble("rating"), c.optInt("reviewCount"), c.optBoolean("trending", false), c.optBoolean("official", false), c.optString("region"), c.optString("sourceName"), c.optString("sourceKind"))
        }
    }

    suspend fun startCourse(room: LiveRoom, course: SharedCourse) { request("/api/courses/${course.id}/start", room.driverToken, JSONObject()) }

    suspend fun useCourse(room: LiveRoom, course: SharedCourse) { request("/api/courses/${course.id}/use", room.driverToken, JSONObject()) }

    suspend fun decide(room: LiveRoom, id: String, decision: String) {
        request("/api/proposals/$id/decision", room.driverToken, JSONObject().put("decision", decision))
    }
}
