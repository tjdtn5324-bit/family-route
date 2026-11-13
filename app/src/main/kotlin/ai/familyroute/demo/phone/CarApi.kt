package ai.familyroute.demo.phone

import ai.familyroute.demo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 차량 앱이 서버와 주고받는 자료. 차량은 "가족에 연결된 차량"으로만 접근하고, 사람 계정으로 로그인하지 않는다.
data class Place(val name: String, val address: String, val latitude: Double, val longitude: Double)
data class Member(val id: String, val name: String, val role: String, val avatar: String = "😊")
data class FamilyInfo(val vehicleName: String, val familyName: String, val members: List<Member>, val inviteCode: String = "", val hasPin: Boolean = false)
/** 가족방 사진첩의 사진 한 장(사진 자체는 따로 받는다). */
data class FamilyPhoto(val id: String, val caption: String, val byName: String, val tripTitle: String, val placeName: String)
data class TripSummary(val id: String, val title: String, val date: String?, val status: String, val destination: Place?, val driverName: String?, val stopCount: Int, val pendingCount: Int, val readyForCar: Boolean = false, val storyCount: Int = 0, val canManage: Boolean = false)
data class Idea(val id: String, val kind: String, val place: Place, val reason: String, val message: String, val status: String, val byName: String, val yes: Int, val no: Int, val mood: String = "", val avatar: String = "😊", val totalReactions: Int = 0, val comments: List<String> = emptyList())
data class RouteLine(val pointsJson: String, val distance: Int, val duration: Int, val version: Int)
data class TripDetail(val id: String, val title: String, val date: String?, val status: String, val destination: Place?, val origin: Place, val driverName: String?,
                      val stops: List<Idea>, val ideas: List<Idea>, val route: RouteLine?, val routeError: String?, val readyForCar: Boolean = false, val readyByName: String? = null) {
    /** 지도에 그릴 들르는 곳 목록(이름과 좌표) */
    fun stopsJson(): String = JSONArray().also { a -> stops.forEach { a.put(JSONObject().put("name", it.place.name).put("latitude", it.place.latitude).put("longitude", it.place.longitude)) } }.toString()
}
data class Story(val by: String, val reason: String, val message: String, val avatar: String = "😊", val mood: String = "", val reactions: Int = 0, val comments: List<String> = emptyList())
/** 추천 코스(참고 코스 또는 다른 가족이 공개한 코스). 가족·사람 이름은 들어 있지 않다. */
data class Course(val id: String, val kind: String, val title: String, val description: String, val destination: Place, val waypoints: List<Place>,
                  val likes: Int, val trending: Boolean, val reviewCount: Int, val rating: Double?, val sourceName: String?, val reviews: List<String> = emptyList())
data class Arrival(val place: Place, val stories: List<Story>, val trip: TripDetail)

/** 서버가 보낸 오류. status 401이면 이 차량의 가족 연결이 끝난 것이다. */
class ApiError(val status: Int, message: String) : IllegalStateException(message)

class CarApi(private val server: String = BuildConfig.FAMILY_SERVER_URL.trimEnd('/'), private val originProvider: () -> Place? = { null }) {
    private suspend fun request(path: String, token: String?, body: JSONObject? = null, method: String = if (body != null) "POST" else "GET"): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("$server$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 15000; c.requestMethod = method
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (!ok) throw ApiError(c.responseCode, json.optString("error", "잠시 문제가 생겼어요. 다시 시도해 주세요."))
            json
        } finally { c.disconnect() }
    }

    /** 휴대폰 '우리 가족 → 차량 연결 코드 받기'의 숫자 6자리로 이 차를 가족에 연결한다. */
    suspend fun pair(code: String, name: String): Pair<String, FamilyInfo> {
        val r = request("/api/vehicle/pair", null, JSONObject().put("code", code).put("name", name))
        val token = r.getString("vehicleToken")
        return token to home(token)
    }
    /** 차량 OS에서 바로 새 가족방을 만들고 이 차를 연결한다. 초대 코드(QR용)가 함께 발급된다. */
    suspend fun createFamily(familyName: String, vehicleName: String, pin: String): Pair<String, FamilyInfo> {
        val r = request("/api/vehicle/family", null, JSONObject().put("familyName", familyName).put("vehicleName", vehicleName).put("pin", pin))
        val token = r.getString("vehicleToken")
        val f = r.getJSONObject("family")
        val v = r.getJSONObject("vehicle")
        return token to FamilyInfo(v.getString("name"), f.getString("name"), emptyList(), f.optString("inviteCode"), f.optBoolean("hasPin"))
    }
    /** 차량에서 가족방 초대 코드(QR)를 새로 발급받는다. */
    suspend fun refreshInvite(token: String): String {
        val r = request("/api/vehicle/invite", token, JSONObject())
        return r.getString("code")
    }
    suspend fun unpair(token: String) { request("/api/vehicle/pair", token, method = "DELETE") }
    suspend fun home(token: String): FamilyInfo {
        val r = request("/api/vehicle/home", token)
        val f = r.getJSONObject("family")
        val members = f.getJSONArray("members").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Member(it.getString("id"), it.getString("name"), it.getString("role"), it.optString("avatar", "😊")) } }
        return FamilyInfo(r.getJSONObject("vehicle").getString("name"), f.getString("name"), members, f.optString("inviteCode"), f.optBoolean("hasPin"))
    }
    /** 가족방 사진첩 목록(새것부터). */
    suspend fun familyPhotos(token: String): List<FamilyPhoto> {
        val a = request("/api/family/photos", token).getJSONArray("photos")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { FamilyPhoto(it.getString("id"), it.optString("caption"), it.optJSONObject("by")?.optString("name") ?: "가족", it.optString("tripTitle"), it.optString("placeName")) }
    }
    /** 사진 한 장의 JPG 바이트. */
    suspend fun photoBytes(token: String, id: String): ByteArray {
        val data = request("/api/photos/$id", token).getString("data")
        return withContext(Dispatchers.Default) { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }
    }
    suspend fun trips(token: String): List<TripSummary> {
        val a = request("/api/trips", token).getJSONArray("trips")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            TripSummary(it.getString("id"), it.getString("title"), it.optStringOrNull("date"), it.getString("status"), it.optJSONObject("destination")?.toPlace(),
                it.optJSONObject("driver")?.optString("name"), it.optInt("stopCount"), it.optInt("pendingCount"), it.optBoolean("readyForCar", false), it.optInt("storyCount"), it.optBoolean("canManage", false))
        }
    }
    suspend fun createTrip(token: String, title: String, destination: Place? = null, courseId: String? = null, waypoints: List<Place>? = null): TripDetail =
        parseTrip(request("/api/vehicle/trips", token, JSONObject().put("title", title).apply {
            if (destination != null) put("destination", destination.toJson())
            if (!courseId.isNullOrBlank()) put("courseId", courseId)
            if (waypoints != null) put("waypoints", JSONArray().also { a -> waypoints.forEach { a.put(it.toJson()) } })
        }))
    suspend fun trip(token: String, id: String): TripDetail = parseTrip(request("/api/trips/$id", token))
    suspend fun decide(token: String, proposalId: String, confirm: Boolean): TripDetail = parseTrip(request("/api/proposals/$proposalId/decide", token, JSONObject().put("decision", if (confirm) "CONFIRM" else "DECLINE")))
    suspend fun removeProposal(token: String, proposalId: String): TripDetail = parseTrip(request("/api/proposals/$proposalId", token, method = "DELETE"))
    suspend fun addStop(token: String, tripId: String, place: Place, reason: String = ""): TripDetail =
        parseTrip(request("/api/trips/$tripId/stops", token, JSONObject().put("place", place.toJson()).apply { if (reason.isNotBlank()) put("reason", reason) }))
    suspend fun applyCourse(token: String, tripId: String, courseId: String, waypoints: List<Place>? = null, destination: Place? = null): TripDetail =
        parseTrip(request("/api/trips/$tripId/course", token, JSONObject().put("courseId", courseId).apply {
            if (waypoints != null) put("waypoints", JSONArray().also { a -> waypoints.forEach { a.put(it.toJson()) } })
            if (destination != null) put("destination", destination.toJson())
        }))
    suspend fun reorder(token: String, tripId: String, ids: List<String>, version: Int?): TripDetail =
        parseTrip(request("/api/trips/$tripId/order", token, JSONObject().put("ids", JSONArray(ids)).apply { if (version != null) put("version", version) }))
    suspend fun setDestination(token: String, tripId: String, place: Place): TripDetail = parseTrip(request("/api/trips/$tripId/update", token, JSONObject().put("destination", place.toJson())))
    suspend fun setReady(token: String, tripId: String, ready: Boolean = true, autoConfirmPending: Boolean = true): TripDetail =
        parseTrip(request("/api/trips/$tripId/ready", token, JSONObject().put("ready", ready).put("autoConfirmPending", autoConfirmPending)))
    private fun departureBody() = JSONObject().apply { originProvider()?.let { put("origin", it.toJson()) } }
    suspend fun quickStart(token: String, tripId: String): TripDetail = parseTrip(request("/api/trips/$tripId/quick-start", token, departureBody()))
    suspend fun start(token: String, tripId: String): TripDetail = parseTrip(request("/api/trips/$tripId/start", token, departureBody()))
    /** 출발을 취소하고 '준비 중'으로 되돌린다. */
    suspend fun cancelStart(token: String, tripId: String): TripDetail = parseTrip(request("/api/trips/$tripId/cancel-start", token, JSONObject()))
    /** 목적지와 들르는 곳을 모두 비운다. 가족이 한마디를 남긴 곳은 제안으로 돌아간다. */
    suspend fun clearCourse(token: String, tripId: String): TripDetail = parseTrip(request("/api/trips/$tripId/clear-course", token, JSONObject()))
    /** 여행방을 지운다. 제안과 사진도 함께 사라진다. */
    suspend fun deleteTrip(token: String, tripId: String) { request("/api/trips/$tripId", token, method = "DELETE") }
    suspend fun finish(token: String, tripId: String): TripDetail = parseTrip(request("/api/trips/$tripId/finish", token, JSONObject()))
    suspend fun arrive(token: String, proposalId: String): Arrival {
        val r = request("/api/proposals/$proposalId/arrive", token, JSONObject())
        val s = r.optJSONArray("storyCards") ?: r.getJSONArray("stories")
        return Arrival(r.getJSONObject("place").toPlace(), (0 until s.length()).map { s.getJSONObject(it) }.map {
            val cmts = it.optJSONArray("comments")?.let { ca -> (0 until ca.length()).map { idx -> ca.getJSONObject(idx).toCommentLine() } } ?: emptyList()
            Story(it.optJSONObject("by")?.optString("name") ?: it.optString("by", "가족"), it.optString("reason"), it.optString("message"), it.optString("avatar", "😊"), it.optString("mood"), it.optInt("totalReactions"), cmts)
        }, parseTrip(r.getJSONObject("trip")))
    }
    /** 가족 연결 없이 혼자 운전할 때의 경로(출발지 → 들르는 곳 → 목적지). 서버에 아무것도 남기지 않는다. */
    suspend fun soloRoute(destination: Place, waypoints: List<Place> = emptyList()): RouteLine {
        val body = JSONObject().put("destination", destination.toJson()).put("waypoints", JSONArray().also { a -> waypoints.forEach { a.put(it.toJson()) } })
        originProvider()?.let { body.put("origin", it.toJson()) }
        val r = request("/api/drive/route", null, body).getJSONObject("route")
        return RouteLine(r.getJSONArray("points").toString(), r.optInt("distance"), r.optInt("duration"), 0)
    }
    /** 추천 코스 목록. sort: trending(요즘 인기) / popular(좋아요순) / new(새로 올라온), kind: REFERENCE / COMMUNITY / 빈 값 */
    suspend fun courses(sort: String, kind: String = ""): List<Course> {
        val a = request("/api/courses?sort=$sort&kind=$kind", null).getJSONArray("courses")
        return (0 until a.length()).map { parseCourse(a.getJSONObject(it)) }
    }
    suspend fun course(id: String): Course = parseCourse(request("/api/courses/" + URLEncoder.encode(id, "UTF-8"), null))
    private fun parseCourse(c: JSONObject): Course {
        val w = c.getJSONArray("waypoints"); val r = c.optJSONArray("reviews")
        return Course(c.getString("id"), c.getString("kind"), c.getString("title"), c.optString("description"), c.getJSONObject("destination").toPlace(),
            (0 until w.length()).map { w.getJSONObject(it).toPlace() }, c.optInt("likes"), c.optBoolean("trending"), c.optInt("reviewCount"),
            if (c.isNull("rating") || !c.has("rating")) null else c.optDouble("rating"), c.optJSONObject("source")?.optString("name"),
            if (r == null) emptyList() else (0 until r.length()).map { r.getJSONObject(it) }.map { "★".repeat(it.optInt("rating").coerceIn(0, 5)) + " " + it.optString("text") })
    }
    /** 장소 검색. 가족과 연결하지 않은 차량도 쓸 수 있다. */
    suspend fun search(token: String?, query: String): List<Place> {
        val a = request("/api/places?q=${URLEncoder.encode(query, "UTF-8")}", token).getJSONArray("places")
        return (0 until a.length()).map { a.getJSONObject(it).toPlace() }
    }
    /** 휴대폰으로 가족 앱을 열 주소(임시 터널, 없으면 같은 Wi-Fi의 시연 PC 주소). 에뮬레이터 시연 서버에서만 알 수 있다. */
    suspend fun phoneAppOrigin(): String? = try {
        if (server != "http://10.0.2.2:8789") BuildConfig.FAMILY_INVITE_URL.takeIf { it.isNotBlank() }
        else request("/api/invite-origin", null).optString("origin").takeIf { Regex("https://[a-z0-9-]+\\.trycloudflare\\.com|http://(192\\.168|10\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01]))\\.\\d{1,3}\\.\\d{1,3}:\\d{2,5}").matches(it) }
    } catch (_: Exception) { null }

    private fun parseTrip(t: JSONObject): TripDetail {
        fun ideas(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }.map {
            val by = it.optJSONObject("by")
            val cmts = it.optJSONArray("comments")?.let { ca -> (0 until ca.length()).map { idx -> ca.getJSONObject(idx).toCommentLine() } } ?: emptyList()
            Idea(it.getString("id"), it.optString("kind", "STOP"), it.getJSONObject("place").toPlace(), it.optString("reason"), it.optString("message"), it.getString("status"),
                by?.optString("name") ?: "가족", it.optInt("yes"), it.optInt("no"), it.optString("mood"), by?.optString("avatar", "😊") ?: "😊", it.optInt("totalReactions"), cmts)
        }
        val r = t.optJSONObject("route")
        return TripDetail(t.getString("id"), t.getString("title"), t.optStringOrNull("date"), t.getString("status"), t.optJSONObject("destination")?.toPlace(),
            t.optJSONObject("origin")?.toPlace() ?: Place("서울 (시연 출발지)", "", 37.5133, 127.1002), t.optJSONObject("driver")?.optString("name"),
            ideas(t.optJSONArray("stops")), ideas(t.optJSONArray("proposals")),
            r?.let { RouteLine(it.getJSONArray("points").toString(), it.optInt("distance"), it.optInt("duration"), it.optInt("version")) }, t.optStringOrNull("routeError"),
            t.optBoolean("readyForCar", false), t.optJSONObject("readyBy")?.optString("name"))
    }
}

internal fun JSONObject.toCommentLine(): String {
    val byObj = optJSONObject("by")
    val author = byObj?.optString("name", "가족") ?: optString("by", "가족")
    return "$author: ${optString("text")}"
}
internal fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }
internal fun JSONObject.toPlace() = Place(getString("name"), optString("address"), getDouble("latitude"), getDouble("longitude"))
internal fun Place.toJson() = JSONObject().put("name", name).put("address", address).put("latitude", latitude).put("longitude", longitude)
