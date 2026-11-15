package com.whatsthesauce.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 시연 영상용: 대본 대사를 앱과 같은 안드로이드 TTS 목소리로 WAV 파일에 저장한다(소리는 나지 않음).
 * 저장 위치: /sdcard/Android/data/com.whatsthesauce/files/voice/
 * 디버그 빌드에서 앱을 켤 때마다 한 번 실행된다.
 */
object VoiceExport {
    private const val TAG = "VoiceExport"

    val LINES = linkedMapOf(
        "A" to "오늘의 메뉴는 콤보 세트, 두 편이에요. 첫 번째는 스위트 칠리, 소개팅 상대가 전 남친의 절친이었다. 달콤하게 시작하지만 끝맛이 상당히 매울 수 있어요!",
        "B" to "안녕하세요, 저는 서른 살 직장인입니다.",
        "D" to "지금까지는 여기까지예요. 서른 살 직장인의 소개팅 이야기죠. 바로 다음에 이어지니까 조금만 더 들어봐요! 그럼 이어서 들려드릴게요.",
        "E1" to "지난달, 친한 회사 동기가 정말 괜찮은 사람이 있다며 소개팅을 주선해줬어요.",
        "E2" to "약속 장소는 성수동의 작은 파스타집이었고, 저는 오랜만에 원피스까지 꺼내 입고 나갔죠.",
        "E3" to "문을 열고 들어가니 창가 자리에 앉아 있던 남자가 저를 보자마자 눈이 동그래지더라고요.",
        "E4" to "그런데 이상하게 저도 그 얼굴이 낯이 익었어요.",
        "G" to "저라면요? 일단 해바라기씨 하나 까먹으면서 숨 한번 고르고 생각했을 거예요. 스위트 칠리 사연답게, 끝까지 들어봐야 알겠죠? 그럼 이어서 들려드릴게요.",
        "H" to "인사를 나누고 십 분쯤 지났을까,",
        "I" to "다음 메뉴는 와사비 마요, 첫 드라이브스루에서 생긴 일.",
    )

    /**
     * 앱이 실제로 쓰는 TTS(engine)로 대사를 하나씩 소리 내어 읽는다. 사이에 2초 쉼.
     * 에뮬레이터 화면 녹화로 앱 목소리만 깨끗하게 따로 녹음할 때 쓴다.
     */
    fun speakAll(engine: TtsEngine, gapMs: Long = 2000) {
        val main = Handler(Looper.getMainLooper())
        val items = LINES.entries.toList()
        fun step(i: Int) {
            if (i >= items.size) return
            engine.speak(items[i].value,
                onDone = { main.postDelayed({ step(i + 1) }, gapMs) },
                onError = { main.postDelayed({ step(i + 1) }, gapMs) })
        }
        main.postDelayed({ step(0) }, 1500)
    }

    fun run(context: Context, appEngineName: () -> String) {
        val dir = File(context.getExternalFilesDir(null), "voice").apply { mkdirs() }
        val main = Handler(Looper.getMainLooper())
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) { Log.e(TAG, "init failed $status"); return@TextToSpeech }
            tts.language = Locale.KOREA
            var left = LINES.size
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { finishOne() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { Log.e(TAG, "error $id"); finishOne() }
                private fun finishOne() = main.post {
                    if (--left == 0) {
                        File(dir, "info.txt").writeText(
                            "appEngine=${appEngineName()}\nexportEngine=${tts.defaultEngine}\nvoice=${tts.voice?.name}\n"
                        )
                        Log.i(TAG, "done → ${dir.absolutePath}")
                        tts.shutdown()
                    }
                }
            })
            LINES.forEach { (k, text) ->
                tts.synthesizeToFile(text, null, File(dir, "$k.wav"), k)
            }
        }
    }
}
