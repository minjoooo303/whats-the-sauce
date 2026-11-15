package com.whatsthesauce.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class EtaSource { NAVI, TEST }

/** 목적지까지 남은 거리·시간 */
data class Eta(val remainingMeters: Int?, val remainingSec: Int?, val source: EtaSource) {
    val minutes: Int? get() = remainingSec?.let { (it + 59) / 60 }
    val km: Double? get() = remainingMeters?.let { it / 1000.0 }

    /** "12km" / "800m" */
    val distanceLabel: String?
        // 내비와 같은 표기: 10km 미만은 소수 첫째 자리(7.6km), 그 이상은 정수, 1km 미만은 m
        get() = remainingMeters?.let {
            when {
                it >= 10_000 -> "${(it / 1000.0).roundToInt()}km"
                it >= 1000 -> "%.1fkm".format(it / 1000.0)
                else -> "${(it / 10) * 10}m"
            }
        }
}

/**
 * 목적지까지 남은 거리와 시간.
 *  1) NaviHelper SDK: onRouteStarted / onDrivingInfo / onDestinationInfo (남은 거리 m, 시간 s)
 *  2) 경로 안내 중이 아니면 테스트 주행: 고른 거리(기본 25km)를 평균 40km/h 로 달리는 것처럼 줄여 나감
 */
class EtaProvider(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _eta = MutableStateFlow(testEta(DEFAULT_TEST_KM * 1000))
    val eta: StateFlow<Eta> = _eta.asStateFlow()

    private var naviMeters: Int? = null
    private var naviSec: Int? = null
    private var naviAt = 0L

    private var testStartMeters = DEFAULT_TEST_KM * 1000
    private var testStartAt = SystemClock.elapsedRealtime()

    private var navi: NaviHelper? = null

    private val listener = object : NaviHelperEventListener {
        // requestTrip() 응답(전체 경로 요약)이라 남은 거리로 쓰지 않는다
        override fun onTripInfo(tripInfo: TripInfo) = Unit
        // 주행 중 주기적으로 오는 정보: 목적지까지 남은 거리(m)·시간(s)
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val d = drivingInfo.destination
            main.post { onNavi(meters = d.distance, sec = d.duration) }
        }
        // 경로 안내 시작 직후
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            main.post { onNavi(meters = routeStartInfo.distance, sec = routeStartInfo.duration) }
        }
        // getDestinationInfo() 응답 (앱이 안내 도중에 켜졌을 때)
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            main.post { onNavi(meters = destinationInfo.remainDistance, sec = destinationInfo.remainTime) }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            main.post { naviMeters = null; naviSec = null; tick() }
        }
        override fun onRouteCancelled() {
            main.post { naviMeters = null; naviSec = null; tick() }
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 1000)
        }
    }

    init {
        try {
            val helper = NaviHelper(context.applicationContext)
            helper.initialize(
                onConnected = {
                    main.post {
                        helper.addListener(listener)
                        runCatching { helper.getRouteStateInfo() }
                        runCatching { helper.getDestinationInfo() }
                        navi = helper
                        Log.i(TAG, "NaviHelper connected")
                    }
                },
                onFailed = { t -> Log.w(TAG, "NaviHelper init failed: $t") },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper unavailable: $t")
        }
        main.post(ticker)
    }

    private fun onNavi(meters: Int?, sec: Int?) {
        // 안내 종료·도착 직후엔 0 이 들어온다 → 안내 없음으로 본다
        if ((meters ?: 0) <= 0 && (sec ?: 0) <= 0) { naviMeters = null; naviSec = null; tick(); return }
        if (meters != null) naviMeters = meters.coerceAtLeast(0)
        if (sec != null) naviSec = sec.coerceAtLeast(0)
        naviAt = SystemClock.elapsedRealtime()
        tick()
    }

    private fun tick() {
        val ns = naviSec
        val nm = naviMeters
        _eta.value = if (ns != null || nm != null) {
            // 내비 안내 중: 내비가 준 값을 그대로 쓴다(차가 서 있으면 줄지 않음).
            // 경로 취소·도착 이벤트가 오기 전까지는 마지막 값을 유지한다.
            val meters = nm ?: ns?.let { (it * AVG_MPS).toInt() }
            Eta(meters, ns ?: meters?.let { (it / AVG_MPS).toInt() }, EtaSource.NAVI)
        } else {
            val now = SystemClock.elapsedRealtime()
            val driven = ((now - testStartAt) / 1000.0) * AVG_MPS
            testEta((testStartMeters - driven).toInt().coerceAtLeast(0))
        }
    }

    private fun testEta(meters: Int) = Eta(meters, (meters / AVG_MPS).toInt(), EtaSource.TEST)

    /** 테스트 주행 거리 설정 (내비 안내가 없을 때만 의미 있음) */
    fun setTestKm(km: Int) {
        testStartMeters = km * 1000
        testStartAt = SystemClock.elapsedRealtime()
        tick()
    }

    fun release() {
        main.removeCallbacks(ticker)
        navi?.let { runCatching { it.removeListener(listener); it.release() } }
        navi = null
    }

    companion object {
        private const val TAG = "SauceEta"
        const val DEFAULT_TEST_KM = 25
        /** 평균 40km/h */
        const val AVG_MPS = 40_000.0 / 3600.0
        val TEST_PRESETS_KM = listOf(4, 12, 25, 45)
    }
}
