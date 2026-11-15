package com.whatsthesauce.playback

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * 재생/일시정지 키 한 개로 두 가지를 한다.
 *  - 짧게: 재생/일시정지
 *  - 길게(LONG_PRESS_MS 이상): CREW에게 묻기 (사연 일시정지 → 질문 듣기 → 답변 → 이어 재생)
 * 스티어링 휠 미디어 키와 화면의 재생 버튼이 같은 규칙을 쓴다.
 */
class SteeringKeys(private val player: StoryPlayer) {
    private val main = Handler(Looper.getMainLooper())
    private var down = false
    private var longFired = false

    private val longPress = Runnable {
        longFired = true
        player.askCrew()
    }

    fun onPlayPauseKey(ev: KeyEvent) {
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!down) {
                    down = true
                    longFired = false
                    main.postDelayed(longPress, LONG_PRESS_MS)
                }
                // 시스템이 길게 누름으로 판정해서 보내 준 경우(반복 DOWN / FLAG_LONG_PRESS)엔 기다리지 않고 바로
                if (!longFired && (ev.repeatCount > 0 || ev.isLongPress)) {
                    main.removeCallbacks(longPress)
                    longPress.run()
                }
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (down && !longFired) player.togglePlay()
                down = false
            }
        }
    }

    companion object {
        const val LONG_PRESS_MS = 600L
    }
}
