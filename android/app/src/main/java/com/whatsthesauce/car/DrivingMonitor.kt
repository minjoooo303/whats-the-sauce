package com.whatsthesauce.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 주행 규제 상태. Pleos 가이드대로 CarUxRestrictionsManager 의 onUxRestrictionsChanged 로 받은
 * CarUxRestrictions 만 반영한다. isRequiresDistractionOptimization == true 이면 '주행 중' 으로 보고
 * 복잡한 메뉴 탐색 / 슬라이더 조작을 막고 음성 중심 UI 로 바꾼다.
 */
class DrivingMonitor(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    init {
        try {
            car = Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) {
                    manager = null
                    return@createCar
                }
                val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                manager = m
                m?.registerListener { r -> main.post { apply(r) } }
                m?.currentCarUxRestrictions?.let { apply(it) }
            }
        } catch (t: Throwable) {
            Log.w("SauceDriving", "Car service unavailable: $t")
        }
    }

    private fun apply(r: CarUxRestrictions) {
        _restricted.value = r.isRequiresDistractionOptimization
    }

    fun release() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
    }
}
