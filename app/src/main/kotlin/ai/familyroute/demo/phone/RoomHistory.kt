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

/** 차량에 보관하는 운전자 연결 토큰은 Android Keystore 키로 암호화한다. */
class RoomHistory(context: Context) {
    private val prefs = context.getSharedPreferences("family_room_history", Context.MODE_PRIVATE)
    private val alias = "family_route_room_history_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<LiveRoom> = try {
        val raw = Base64.decode(prefs.getString("rooms", "") ?: "", Base64.NO_WRAP)
        if (raw.size < 29) emptyList() else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            val arr = JSONArray(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { LiveRoom(it.getString("invite"), it.getString("driverToken"), it.optString("name")) }.take(5)
        }
    } catch (_: Exception) { emptyList() }
    fun save(rooms: List<LiveRoom>): Boolean = try {
        val arr = JSONArray(); rooms.take(5).forEach { arr.put(JSONObject().put("invite",it.invite).put("driverToken",it.driverToken).put("name",it.name)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(arr.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("rooms",encoded).commit()
    } catch (_: Exception) { false }
}
