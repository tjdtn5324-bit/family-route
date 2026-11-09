package ai.familyroute.demo.phone

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 실제 기어 P와 UX 제한 해제가 확인되지 않으면 항상 조작을 막는다. */
class ParkingGuard(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _allowed = MutableStateFlow(false)
    val allowed = _allowed.asStateFlow()
    private var parked = false
    private var unrestricted = false
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private fun publish() { main.post { _allowed.value = parked && unrestricted } }
    init {
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
        main.removeCallbacksAndMessages(null)
    }
}
