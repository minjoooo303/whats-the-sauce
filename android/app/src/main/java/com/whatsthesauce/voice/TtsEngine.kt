package com.whatsthesauce.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener as TtsServerListener
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import android.speech.tts.TextToSpeech as AndroidTextToSpeech

/** 한 번에 한 문장(또는 CREW 한 마디)을 말하고, 끝나면 onDone 을 메인 스레드에서 부른다. */
interface TtsEngine {
    val name: String
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun release()
}

private const val TAG = "SauceTts"

internal val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Pleos TextToSpeech SDK 를 먼저 쓰고, 초기화에 실패하면(일반 AAOS 에뮬레이터 등)
 * 안드로이드 기본 TTS 로 자동 전환한다.
 */
class SwitchingTts(private val context: Context, clientId: String, clientSecret: String) : TtsEngine {
    private var delegate: TtsEngine? = null
    private val pending = ArrayDeque<Triple<String, () -> Unit, (String) -> Unit>>()

    override val name: String get() = delegate?.name ?: "initializing"

    init {
        PleosTts.create(
            context, clientId, clientSecret,
            onReady = { use(it) },
            onFailed = { reason ->
                Log.w(TAG, "Pleos TTS unavailable ($reason) → Android TTS")
                AndroidTts.create(context, onReady = { use(it) }, onFailed = { r ->
                    Log.e(TAG, "Android TTS unavailable: $r")
                    pending.forEach { it.third("TTS 를 사용할 수 없어요") }
                    pending.clear()
                })
            },
        )
    }

    private fun use(engine: TtsEngine) {
        delegate = engine
        while (pending.isNotEmpty()) {
            val (t, d, e) = pending.removeFirst()
            engine.speak(t, d, e)
        }
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val d = delegate
        if (d == null) pending.addLast(Triple(text, onDone, onError)) else d.speak(text, onDone, onError)
    }

    override fun stop() {
        pending.clear()
        delegate?.stop()
    }

    override fun release() {
        delegate?.release()
    }
}

/** Pleos TextToSpeech SDK v3.3.1 */
class PleosTts private constructor(private val tts: PleosTextToSpeech) : TtsEngine {
    override val name = "Pleos TTS"
    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    private var onDone: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private val listener = object : TtsEventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() {
            val t = token
            main.post { if (t == token) finish()?.invoke() }
        }
        override fun onStop() = Unit
        override fun onError(errorType: TtsErrorType) {
            val t = token
            main.post {
                if (t == token) {
                    val e = onError
                    clear()
                    e?.invoke(errorType.name)
                }
            }
        }
    }

    private fun finish(): (() -> Unit)? {
        val d = onDone
        clear()
        return d
    }

    private fun clear() {
        onDone = null; onError = null
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        token++
        this.onDone = onDone
        this.onError = onError
        tts.speak(text, isFinal = true)
    }

    override fun stop() {
        token++
        clear()
        runCatching { tts.stop() }
    }

    override fun release() {
        stop()
        runCatching { tts.removeEventListener(listener) }
        runCatching { tts.release() }
    }

    companion object {
        fun create(
            context: Context,
            clientId: String,
            clientSecret: String,
            onReady: (TtsEngine) -> Unit,
            onFailed: (String) -> Unit,
        ) {
            val main = Handler(Looper.getMainLooper())
            try {
                val useServer = clientId.isNotBlank() && clientSecret.isNotBlank()
                val tts = PleosTextToSpeech(context.applicationContext, if (useServer) TtsMode.HYBRID else TtsMode.ON_DEVICE)
                val engine = PleosTts(tts)
                tts.initialize(object : TtsInitListener {
                    override fun onSuccess() {
                        main.post {
                            tts.addEventListener(engine.listener)
                            tts.setLocale(Locale.KOREA)
                            runCatching { tts.setAudioAttributes(SPEECH_ATTRIBUTES) }
                            if (!useServer) {
                                onReady(engine)
                                return@post
                            }
                            tts.registerApp(clientId, clientSecret, object : TtsServerListener {
                                override fun onConnected() {
                                    main.post { onReady(engine) }
                                }
                                override fun onFailed(errorType: TtsErrorType) {
                                    // HYBRID 는 서버 실패 시 온디바이스로 동작하므로 그대로 사용
                                    Log.w(TAG, "Pleos TTS registerApp failed: $errorType")
                                    main.post { onReady(engine) }
                                }
                            })
                        }
                    }
                    override fun onFailure(errorType: TtsErrorType) {
                        main.post { onFailed(errorType.name) }
                    }
                })
            } catch (t: Throwable) {
                main.post { onFailed(t.javaClass.simpleName + ": " + t.message) }
            }
        }
    }
}

/** 안드로이드 기본 TTS (Pleos SDK 가 없는 환경용 대체) */
class AndroidTts private constructor() : TtsEngine {
    override val name = "Android TTS"
    private lateinit var tts: AndroidTextToSpeech
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, Pair<() -> Unit, (String) -> Unit>>()
    private var seq = 0

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val id = "u${++seq}"
        callbacks[id] = onDone to onError
        tts.speak(text, AndroidTextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    override fun stop() {
        callbacks.clear()
        tts.stop()
    }

    override fun release() {
        stop()
        tts.shutdown()
    }

    companion object {
        fun create(context: Context, onReady: (TtsEngine) -> Unit, onFailed: (String) -> Unit) {
            val engine = AndroidTts()
            engine.tts = AndroidTextToSpeech(context.applicationContext) { status ->
                engine.main.post {
                    if (status == AndroidTextToSpeech.SUCCESS) {
                        engine.tts.language = Locale.KOREA
                        engine.tts.setAudioAttributes(SPEECH_ATTRIBUTES)
                        engine.tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) {
                                engine.main.post { engine.callbacks.remove(utteranceId)?.first?.invoke() }
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                engine.main.post { engine.callbacks.remove(utteranceId)?.second?.invoke("error") }
                            }
                        })
                        onReady(engine)
                    } else {
                        onFailed("status=$status")
                    }
                }
            }
        }
    }
}
