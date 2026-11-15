package com.whatsthesauce.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener as SttServerListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/** 한 번 듣고(한 발화) 결과를 돌려준다. 모든 콜백은 메인 스레드. */
interface SttEngine {
    val name: String
    fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit)
    fun cancel()
    fun release()
}

private const val TAG = "SauceStt"
private const val LISTEN_TIMEOUT_MS = 12_000L

/** Pleos SpeechToText SDK 를 먼저 쓰고, 안 되면 안드로이드 SpeechRecognizer 로 전환 */
class SwitchingStt(private val context: Context, clientId: String, clientSecret: String) : SttEngine {
    private var delegate: SttEngine? = null
    private var failed = false
    override val name: String get() = delegate?.name ?: if (failed) "unavailable" else "initializing"

    init {
        PleosStt.create(context, clientId, clientSecret,
            onReady = { delegate = it },
            onFailed = { reason ->
                Log.w(TAG, "Pleos STT unavailable ($reason) → Android SpeechRecognizer")
                if (SpeechRecognizer.isRecognitionAvailable(context)) delegate = AndroidStt(context)
                else failed = true
            })
    }

    override fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val d = delegate
        if (d == null) onError(if (failed) "이 기기에서는 음성 인식을 쓸 수 없어요" else "음성 인식을 준비 중이에요")
        else d.listen(onPartial, onResult, onError)
    }

    override fun cancel() { delegate?.cancel() }
    override fun release() { delegate?.release() }
}

/** Pleos SpeechToText SDK v3.3.1 */
class PleosStt private constructor(private val stt: SpeechToText) : SttEngine {
    override val name = "Pleos STT"
    private val main = Handler(Looper.getMainLooper())
    private var session = 0
    private var cbPartial: ((String) -> Unit)? = null
    private var cbResult: ((String) -> Unit)? = null
    private var cbError: ((String) -> Unit)? = null
    private var lastText = ""

    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            val s = session
            main.post {
                if (s != session) return@post
                lastText = stt
                if (completed) deliverResult(stt) else cbPartial?.invoke(stt)
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            val s = session
            main.post {
                if (s != session) return@post
                if (lastText.isNotBlank()) deliverResult(lastText) else deliverError("잘 못 들었어요. 한 번 더 말씀해주세요")
            }
        }
        override fun onError(errorType: SttErrorType) {
            val s = session
            main.post { if (s == session) deliverError("음성 인식 오류 (${errorType.name})") }
        }
    }

    private val timeout = Runnable {
        runCatching { stt.stop() }
        if (lastText.isNotBlank()) deliverResult(lastText) else deliverError("말씀이 들리지 않았어요")
    }

    private fun deliverResult(text: String) {
        val cb = cbResult
        reset()
        cb?.invoke(text.trim())
    }

    private fun deliverError(msg: String) {
        val cb = cbError
        reset()
        cb?.invoke(msg)
    }

    private fun reset() {
        main.removeCallbacks(timeout)
        session++
        cbPartial = null; cbResult = null; cbError = null; lastText = ""
    }

    override fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        reset()
        cbPartial = onPartial; cbResult = onResult; cbError = onError
        main.postDelayed(timeout, LISTEN_TIMEOUT_MS)
        try {
            stt.request()
        } catch (t: Throwable) {
            deliverError("음성 인식을 시작하지 못했어요")
        }
    }

    override fun cancel() {
        reset()
        runCatching { stt.stop() }
    }

    override fun release() {
        cancel()
        runCatching { stt.removeListener(listener) }
        runCatching { stt.release() }
    }

    companion object {
        fun create(
            context: Context, clientId: String, clientSecret: String,
            onReady: (SttEngine) -> Unit, onFailed: (String) -> Unit,
        ) {
            val main = Handler(Looper.getMainLooper())
            try {
                val useServer = clientId.isNotBlank() && clientSecret.isNotBlank()
                val stt = SpeechToText(context.applicationContext, if (useServer) SttMode.HYBRID else SttMode.ON_DEVICE)
                val engine = PleosStt(stt)
                stt.initialize(object : SttInitListener {
                    override fun onSuccess() {
                        main.post {
                            stt.addListener(engine.listener)
                            stt.setLocale(Locale.KOREA)
                            if (!useServer) {
                                onReady(engine); return@post
                            }
                            stt.registerApp(clientId, clientSecret, object : SttServerListener {
                                override fun onConnected() { main.post { onReady(engine) } }
                                override fun onFailed(errorType: SttErrorType) {
                                    Log.w(TAG, "Pleos STT registerApp failed: $errorType")
                                    main.post { onReady(engine) }
                                }
                            })
                        }
                    }
                    override fun onFailure(errorType: SttErrorType) {
                        main.post { onFailed(errorType.name) }
                    }
                })
            } catch (t: Throwable) {
                main.post { onFailed(t.javaClass.simpleName + ": " + t.message) }
            }
        }
    }
}

/** 안드로이드 기본 SpeechRecognizer (RECORD_AUDIO 런타임 권한 필요) */
class AndroidStt(private val context: Context) : SttEngine {
    override val name = "Android STT"
    private var recognizer: SpeechRecognizer? = null

    override fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                cancel(); onError("잘 못 들었어요. 한 번 더 말씀해주세요")
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                cancel()
                if (text.isBlank()) onError("잘 못 들었어요") else onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(intent)
    }

    override fun cancel() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    override fun release() = cancel()
}
