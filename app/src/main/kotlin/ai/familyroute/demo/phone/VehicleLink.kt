package ai.familyroute.demo.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 이 차량이 연결된 가족 정보. 토큰은 차량 접근 권한이므로 Android Keystore 키로 암호화해 둔다. */
data class SavedLink(val token: String, val familyName: String, val vehicleName: String)

/** 앱이나 에뮬레이터를 껐다 켜도 안내 중이던 추천 코스·경유지·경로가 유지되도록 기기에 저장하는 자료. */
data class SavedSoloDrive(val place: Place, val route: RouteLine, val stops: List<Place> = emptyList(), val courseTitle: String? = null)

class VehicleLink(context: Context) {
    private val prefs = context.getSharedPreferences("vehicle_link_v2", Context.MODE_PRIVATE)
    private val alias = "family_route_vehicle_link_v2"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): SavedLink? = try {
        val raw = Base64.decode(prefs.getString("link", "") ?: "", Base64.NO_WRAP)
        if (raw.size < 29) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            val o = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
            SavedLink(o.getString("token"), o.optString("family"), o.optString("vehicle"))
        }
    } catch (_: Exception) { null }
    fun save(link: SavedLink): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSONObject().put("token", link.token).put("family", link.familyName).put("vehicle", link.vehicleName).toString().toByteArray(Charsets.UTF_8)
        prefs.edit().putString("link", Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)).commit()
    } catch (_: Exception) { false }
    fun clear() { prefs.edit().remove("link").remove("trip_id").commit() }

    fun loadTripId(): String? = prefs.getString("trip_id", null)?.takeIf { it.isNotBlank() }
    fun saveTripId(tripId: String?) {
        if (tripId.isNullOrBlank()) prefs.edit().remove("trip_id").apply()
        else prefs.edit().putString("trip_id", tripId).apply()
    }

    fun loadSolo(): SavedSoloDrive? = try {
        val raw = prefs.getString("solo_drive", null) ?: return null
        val o = JSONObject(raw)
        val r = o.getJSONObject("route")
        val s = o.optJSONArray("stops") ?: JSONArray()
        SavedSoloDrive(
            place = o.getJSONObject("place").toPlace(),
            route = RouteLine(r.getString("pointsJson"), r.optInt("distance"), r.optInt("duration"), 0),
            stops = (0 until s.length()).map { s.getJSONObject(it).toPlace() },
            courseTitle = o.optStringOrNull("courseTitle")
        )
    } catch (_: Exception) { null }

    fun saveSolo(solo: SavedSoloDrive?) {
        if (solo == null) { prefs.edit().remove("solo_drive").apply(); return }
        val o = JSONObject()
            .put("place", solo.place.toJson())
            .put("route", JSONObject().put("pointsJson", solo.route.pointsJson).put("distance", solo.route.distance).put("duration", solo.route.duration))
            .put("stops", JSONArray().also { a -> solo.stops.forEach { a.put(it.toJson()) } })
            .apply { if (!solo.courseTitle.isNullOrBlank()) put("courseTitle", solo.courseTitle) }
        prefs.edit().putString("solo_drive", o.toString()).apply()
    }
}
