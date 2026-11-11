package ai.familyroute.demo.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 이 차량이 연결된 가족 정보. 토큰은 차량 접근 권한이므로 Android Keystore 키로 암호화해 둔다. */
data class SavedLink(val token: String, val familyName: String, val vehicleName: String)

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
    fun clear() { prefs.edit().clear().commit() }
}
