package ai.familyroute.demo.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DepartureStatus(val openCount: Int = 0, val label: String = "출발 전 문 상태 확인 중")

/** 도어를 제어하지 않고 지원되는 문의 열림 상태만 읽어 가족의 출발 준비를 돕는다. */
class PleosDepartureCheck(context: Context) {
    private val sdk = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(DepartureStatus())
    val status = _status.asStateFlow()
    private var closed = false
    private var capabilities: Map<DoorArea, AreaBounds<Int>>? = null
    private var generation = 0
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val round = ++generation
            val areas = capabilities
            try {
                if (areas == null) sdk.getDoor().checkDoorPositionCapabilities(
                    onComplete = { config: CapabilityConfig, result: Map<DoorArea, AreaBounds<Int>> -> main.post {
                        if (!closed && generation == round) {
                            val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                            capabilities = if (readable) result else emptyMap()
                            if (capabilities.isNullOrEmpty()) _status.value = DepartureStatus(label = "이 차량은 문 상태 확인을 지원하지 않아요")
                        }
                    } }, onFailed = { failed(it, round) }
                ) else if (areas.isNotEmpty()) {
                    val readings = mutableMapOf<DoorArea, Int>()
                    areas.keys.forEach { area -> sdk.getDoor().getCurrentDoorPosition(area, onComplete = { value -> main.post {
                        if (!closed && generation == round) {
                            if (value == null || value < 0) { failed(IllegalStateException("Door position unavailable"), round); return@post }
                            readings[area] = value
                            if (readings.size == areas.size) {
                                val open = readings.values.count { it > 0 }
                                val next = DepartureStatus(open, if (open > 0) "문 ${open}곳이 열려 있어요" else "Pleos 차량 확인 · 문이 모두 닫혀 있어요")
                                if (_status.value != next) Log.i(TAG, "SDK door readings received open=$open")
                                _status.value = next
                            }
                        }
                    } }, onFailed = { failed(it, round) }) }
                }
            } catch (e: Exception) { failed(e, round) }
            main.postDelayed(this, 5000)
        }
    }
    init { try { sdk.initialize(); main.post(poll) } catch (e: Exception) { failed(e, generation) } }
    private fun failed(e: Exception, round: Int) { main.post {
        if (!closed && generation == round) { _status.value = DepartureStatus(label = "문 상태를 확인할 수 없어요 · 직접 확인해 주세요"); Log.w(TAG, "SDK door read failed", e) }
    } }
    fun close() { closed = true; generation++; main.removeCallbacksAndMessages(null); runCatching { sdk.release() } }
    private companion object { const val TAG = "FamilyRoutePleosVehicle" }
}
