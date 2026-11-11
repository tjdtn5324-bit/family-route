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
data class Member(val id: String, val name: String, val role: String)
data class FamilyInfo(val vehicleName: String, val familyName: String, val members: List<Member>)
data class TripSummary(val id: String, val title: String, val date: String?, val status: String, val destination: Place?, val driverName: String?, val stopCount: Int, val pendingCount: Int)
data class Idea(val id: String, val kind: String, val place: Place, val reason: String, val message: String, val status: String, val byName: String, val yes: Int, val no: Int)
data class RouteLine(val pointsJson: String, val distance: Int, val duration: Int, val version: Int)
data class TripDetail(val id: String, val title: String, val date: String?, val status: String, val destination: Place?, val origin: Place, val driverName: String?,
                      val stops: List<Idea>, val ideas: List<Idea>, val route: RouteLine?, val routeError: String?) {
    /** 지도에 그릴 들르는 곳 목록(이름과 좌표) */
    fun stopsJson(): String = JSONArray().also { a -> stops.forEach { a.put(JSONObject().put("name", it.place.name).put("latitude", it.place.latitude).put("longitude", it.place.longitude)) } }.toString()
}
data class Story(val by: String, val reason: String, val message: String)
data class Arrival(val place: Place, val stories: List<Story>, val trip: TripDetail)

/** 서버가 보낸 오류. status 401이면 이 차량의 가족 연결이 끝난 것이다. */
class ApiError(val status: Int, message: String) : IllegalStateException(message)

class CarApi(private val server: String = BuildConfig.FAMILY_SERVER_URL.trimEnd('/')) {
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
    suspend fun unpair(token: String) { request("/api/vehicle/pair", token, method = "DELETE") }
    suspend fun home(token: String): FamilyInfo {
        val r = request("/api/vehicle/home", token)
        val f = r.getJSONObject("family")
        val members = f.getJSONArray("members").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Member(it.getString("id"), it.getString("name"), it.getString("role")) } }
        return FamilyInfo(r.getJSONObject("vehicle").getString("name"), f.getString("name"), members)
    }
    suspend fun trips(token: String): List<TripSummary> {
        val a = request("/api/trips", token).getJSONArray("trips")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            TripSummary(it.getString("id"), it.getString("title"), it.optStringOrNull("date"), it.getString("status"), it.optJSONObject("destination")?.toPlace(),
                it.optJSONObject("driver")?.optString("name"), it.optInt("stopCount"), it.optInt("pendingCount"))
        }
    }
    suspend fun trip(token: String, id: String): TripDetail = parseTrip(request("/api/trips/$id", token))
    suspend fun decide(token: String, proposalId: String, confirm: Boolean): TripDetail = parseTrip(request("/api/proposals/$proposalId/decide", token, JSONObject().put("decision", if (confirm) "CONFIRM" else "DECLINE")))
    suspend fun reorder(token: String, tripId: String, ids: List<String>, version: Int?): TripDetail =
        parseTrip(request("/api/trips/$tripId/order", token, JSONObject().put("ids", JSONArray(ids)).apply { if (version != null) put("version", version) }))
    suspend fun setDestination(token: String, tripId: String, place: Place): TripDetail = parseTrip(request("/api/trips/$tripId/update", token, JSONObject().put("destination", place.toJson())))
    suspend fun start(token: String, tripId: String): TripDetail = parseTrip(request("/api/trips/$tripId/start", token, JSONObject()))
    suspend fun finish(token: String, tripId: String): TripDetail = parseTrip(request("/api/trips/$tripId/finish", token, JSONObject()))
    suspend fun arrive(token: String, proposalId: String): Arrival {
        val r = request("/api/proposals/$proposalId/arrive", token, JSONObject())
        val s = r.getJSONArray("stories")
        return Arrival(r.getJSONObject("place").toPlace(), (0 until s.length()).map { s.getJSONObject(it) }.map { Story(it.getString("by"), it.optString("reason"), it.optString("message")) }, parseTrip(r.getJSONObject("trip")))
    }
    /** 가족 연결 없이 혼자 운전할 때의 경로(출발지 → 들르는 곳 → 목적지). 서버에 아무것도 남기지 않는다. */
    suspend fun soloRoute(destination: Place): RouteLine {
        val r = request("/api/drive/route", null, JSONObject().put("destination", destination.toJson())).getJSONObject("route")
        return RouteLine(r.getJSONArray("points").toString(), r.optInt("distance"), r.optInt("duration"), 0)
    }
    /** 장소 검색. 가족과 연결하지 않은 차량도 쓸 수 있다. */
    suspend fun search(token: String?, query: String): List<Place> {
        val a = request("/api/places?q=${URLEncoder.encode(query, "UTF-8")}", token).getJSONArray("places")
        return (0 until a.length()).map { a.getJSONObject(it).toPlace() }
    }
    /** 휴대폰으로 가족 앱을 열 주소(임시 터널). 에뮬레이터 시연 서버에서만 알 수 있다. */
    suspend fun phoneAppOrigin(): String? = try {
        if (server != "http://10.0.2.2:8789") BuildConfig.FAMILY_INVITE_URL.takeIf { it.isNotBlank() }
        else request("/api/invite-origin", null).optString("origin").takeIf { Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").matches(it) }
    } catch (_: Exception) { null }

    private fun parseTrip(t: JSONObject): TripDetail {
        fun ideas(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }.map {
            Idea(it.getString("id"), it.optString("kind", "STOP"), it.getJSONObject("place").toPlace(), it.optString("reason"), it.optString("message"), it.getString("status"),
                it.optJSONObject("by")?.optString("name") ?: "가족", it.optInt("yes"), it.optInt("no"))
        }
        val r = t.optJSONObject("route")
        return TripDetail(t.getString("id"), t.getString("title"), t.optStringOrNull("date"), t.getString("status"), t.optJSONObject("destination")?.toPlace(),
            t.optJSONObject("origin")?.toPlace() ?: Place("서울 (시연 출발지)", "", 37.5133, 127.1002), t.optJSONObject("driver")?.optString("name"),
            ideas(t.optJSONArray("stops")), ideas(t.optJSONArray("proposals")),
            r?.let { RouteLine(it.getJSONArray("points").toString(), it.optInt("distance"), it.optInt("duration"), it.optInt("version")) }, t.optStringOrNull("routeError"))
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.toPlace() = Place(getString("name"), optString("address"), getDouble("latitude"), getDouble("longitude"))
private fun Place.toJson() = JSONObject().put("name", name).put("address", address).put("latitude", latitude).put("longitude", longitude)
