package ai.familyroute.demo.phone

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/** 오래된 마지막 위치나 잘못된 좌표를 실제 차량 출발지로 쓰지 않는다. */
data class VehiclePosition(val latitude: Double, val longitude: Double, val timestampMillis: Long) {
    fun isUsable(now: Long): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0) && timestampMillis > 0 &&
        now - timestampMillis in -10_000L..300_000L
    fun distanceKmTo(place: Place): Double {
        val lat = Math.toRadians(place.latitude - latitude)
        val lon = Math.toRadians(place.longitude - longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(place.latitude)) * sin(lon / 2).pow(2)
        return 6371.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/** 이동 이력은 수집하지 않고, 새 길찾기와 가족 여행 출발 시 최신 출발지만 전달한다. */
class PleosVehicleLocation(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val expire = Runnable { if (!closed) { _position.value = null; _status.value = "차량 위치가 오래됐어요 · 서울 시연 위치 사용" } }
    private val sdk = FusedLocation(context.applicationContext)
    private val _position = MutableStateFlow<VehiclePosition?>(null)
    val position = _position.asStateFlow()
    private val _status = MutableStateFlow("Pleos 차량 위치 수신 대기")
    val status = _status.asStateFlow()
    @Volatile private var closed = false
    private var initialized = false
    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (closed) return
            val p = location?.let { VehiclePosition(it.latitude, it.longitude, it.timestampMillis) }
            if (p?.isUsable(System.currentTimeMillis()) == true) {
                val first = _position.value == null
                _position.value = p
                main.removeCallbacks(expire)
                main.postDelayed(expire, (300_001L - (System.currentTimeMillis() - p.timestampMillis)).coerceAtLeast(1L))
                _status.value = "Pleos 차량 위치 수신됨"
                if (first) Log.i(TAG, "SDK fresh position received")
            } else { _position.value = null; _status.value = "차량 위치 대기 · 서울 시연 위치 사용" }
        }
        override fun onError(exception: Exception) {
            if (!closed) { _position.value = null; _status.value = "차량 위치 수신 실패 · 서울 시연 위치 사용"; Log.w(TAG, "SDK location error", exception) }
        }
    }
    init { start() }
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(appContext, PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun start() {
        if (closed) return
        if (!hasPermission()) { _status.value = "차량 위치 사용을 허용해 주세요 · 서울 시연 위치 사용"; return }
        try {
            if (sdk.isSupported()) {
                if (!initialized) { sdk.initialize(); initialized = true }
                sdk.registerFusedLocationCallback(listener)
                sdk.getLastKnownLocation(listener)
                Log.i(TAG, "SDK initialized and subscribed")
            } else _status.value = "차량 위치 미지원 · 서울 시연 위치 사용"
        } catch (e: Exception) { listener.onError(e) }
    }
    fun currentOrigin(): Place? = _position.value?.takeIf { it.isUsable(System.currentTimeMillis()) }
        ?.let { Place("현재 차량 위치", "", it.latitude, it.longitude) }
    fun close() { closed = true; main.removeCallbacksAndMessages(null); runCatching { sdk.unregisterFusedLocationCallback(listener) }; runCatching { sdk.release() } }
    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "FamilyRoutePleosLocation"
    }
}
