package ai.familyroute.demo.phone

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import ai.familyroute.demo.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 실제 기어 P와 UX 제한 해제가 확인되지 않으면 항상 조작을 막는다. */
class ParkingGuard(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _allowed = MutableStateFlow(false)
    val allowed = _allowed.asStateFlow()
    private var parked = false
    private var unrestricted = false
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    // 디버그 빌드 전용 주행 시뮬레이션: 화면이 주행 상태에 반응하는지만 확인하며, 실제 기어 신호는 검증하지 않는다.
    // 강제로 막기만 할 수 있고(주행=true), 해제하면 실제 계산값으로 돌아간다. 릴리스 빌드에서는 등록되지 않는다.
    private var debugDriving = false
    private var debugReceiver: BroadcastReceiver? = null
    private fun publish() { main.post { _allowed.value = parked && unrestricted && !debugDriving } }
    init {
        if (BuildConfig.DEBUG) try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) { debugDriving = intent.getBooleanExtra("driving", false); publish() }
            }
            val filter = IntentFilter(DEBUG_DRIVING_ACTION)
            // adb shell(다른 UID)에서 보내므로 API 33+에서는 RECEIVER_EXPORTED를 명시해야 받는다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) appContext.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            else appContext.registerReceiver(r, filter)
            debugReceiver = r
        } catch (_: Throwable) { }
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!ready) { parked = false; unrestricted = false; publish() }
                else try {
                    val p = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    properties = p
                    fun apply(value: CarPropertyValue<*>?) {
                        parked = value != null && value.status == CarPropertyValue.STATUS_AVAILABLE && value.value == VehicleGear.GEAR_PARK
                        publish()
                    }
                    val cb = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(value: CarPropertyValue<*>) { if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) apply(value) }
                        override fun onErrorEvent(propertyId: Int, areaId: Int) { if (propertyId == VehiclePropertyIds.GEAR_SELECTION) apply(null) }
                    }
                    callback = cb
                    if (p.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) apply(p.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)) else apply(null)
                    val m = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    ux = m
                    m.registerListener { unrestricted = !it.isRequiresDistractionOptimization; publish() }
                    unrestricted = !m.currentCarUxRestrictions.isRequiresDistractionOptimization; publish()
                } catch (_: Throwable) { parked = false; unrestricted = false; publish() }
            }
        } catch (_: Throwable) { parked = false; unrestricted = false; publish() }
    }
    fun close() {
        try { callback?.let { properties?.unregisterCallback(it) }; ux?.unregisterListener(); car?.disconnect() } catch (_: Throwable) { }
        try { debugReceiver?.let { appContext.unregisterReceiver(it) } } catch (_: Throwable) { }
        debugReceiver = null
        main.removeCallbacksAndMessages(null)
    }
    private companion object { const val DEBUG_DRIVING_ACTION = "ai.familyroute.demo.DEBUG_DRIVING" }
}
