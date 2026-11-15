package com.whatsthesauce.voice

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 지금 무엇을 듣는 중인지. 데모 모드에서 어떤 대본 문장을 넣을지 고를 때 쓴다. */
enum class ListenPurpose { ASK, ORDER, OFFER, DICTATE }

/**
 * 시연용 음성 입력. 켜져 있으면 마이크 대신 미리 정한 문장을 한 글자씩 "받아쓰는" 것처럼 넣는다.
 * 꺼져 있으면 진짜 STT(real)로 그대로 넘긴다.
 * 에뮬레이터 마이크가 안 될 때, 대본대로 촬영할 때 쓴다.
 */
class DemoStt(private val real: SttEngine, demoOnStart: Boolean) : SttEngine {
    private val _demo = MutableStateFlow(demoOnStart)
    val demo: StateFlow<Boolean> = _demo
    fun toggleDemo() { cancel(); _demo.value = !_demo.value }

    /** StoryPlayer 가 listen 직전에 정해 준다 */
    var purpose: ListenPurpose = ListenPurpose.ASK

    private val main = Handler(Looper.getMainLooper())
    private var askTurn = 0
    private var session = 0

    override val name: String get() = if (_demo.value) "demo" else real.name

    override fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!_demo.value) { real.listen(onPartial, onResult, onError); return }
        val line = when (purpose) {
            ListenPurpose.ASK -> ASK_LINES[askTurn++ % ASK_LINES.size]
            ListenPurpose.ORDER -> "오늘은 달달한 썰 듣고 싶어. 매운 건 좀 덜"
            ListenPurpose.OFFER -> "좋아, 하나 더 들을래"
            ListenPurpose.DICTATE -> "지난주에 회사 엘리베이터에서 사장님인 줄 모르고 커피 쏟았는데, 다음 날 회의에 그분이 들어오셨어요"
        }
        val my = ++session
        val words = line.split(" ")
        // 사람이 말하는 속도처럼: 0.6초 뒤 시작, 단어마다 0.28초
        words.indices.forEach { i ->
            main.postDelayed({ if (my == session) onPartial(words.take(i + 1).joinToString(" ")) }, START_MS + i * WORD_MS)
        }
        main.postDelayed({ if (my == session) onResult(line) }, START_MS + words.size * WORD_MS + END_MS)
    }

    override fun cancel() { session++; main.removeCallbacksAndMessages(null); real.cancel() }
    override fun release() { cancel(); real.release() }

    companion object {
        private const val START_MS = 600L
        private const val WORD_MS = 280L
        private const val END_MS = 500L
        val ASK_LINES = listOf(
            "그래서 그 사람 결국 어떻게 됐어?",
            "나라면 어떻게 했을 것 같아?",
            "잠깐, 방금 그 부분 무슨 뜻이야?",
        )
    }
}
