package com.whatsthesauce.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import com.whatsthesauce.crew.CrewClient
import com.whatsthesauce.crew.OrderResult
import com.whatsthesauce.data.ChatLine
import com.whatsthesauce.data.DriveSet
import com.whatsthesauce.data.LibraryStore
import com.whatsthesauce.data.Sauce
import com.whatsthesauce.data.SetType
import com.whatsthesauce.data.Story
import com.whatsthesauce.voice.SPEECH_ATTRIBUTES
import com.whatsthesauce.voice.SttEngine
import com.whatsthesauce.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Mode {
    IDLE,          // 아무것도 재생 안 함
    PLAYING,       // 사연 낭독 중
    PAUSED,        // 일시정지
    LISTENING,     // 사용자 발화 듣는 중
    THINKING,      // CREW 답변 생성 중
    CREW_SPEAKING, // CREW 가 말하는 중 (소개, 답변, 전환 멘트)
    OFFER,         // "하나 더 어떠세요?" 응답 대기
    FINISHED,      // 세트 종료
}

data class PlayerState(
    val queue: List<Story> = emptyList(),
    val index: Int = 0,
    val sentence: Int = 0,
    val mode: Mode = Mode.IDLE,
    val setType: SetType? = null,
    val transcript: List<ChatLine> = emptyList(),
    val partial: String = "",
    val offer: Story? = null,
    val sentenceStartedAt: Long = 0L,
    val notice: String? = null,
) {
    val current: Story? get() = queue.getOrNull(index)
    val next: Story? get() = queue.getOrNull(index + 1)
    val isActive: Boolean get() = mode != Mode.IDLE && mode != Mode.FINISHED

    /** 현재 사연에서의 대략적인 재생 위치(초) */
    fun positionSec(now: Long = SystemClock.elapsedRealtime()): Int {
        val s = current ?: return 0
        val base = s.secondsBefore(sentence)
        val inSentence = if (mode == Mode.PLAYING && sentence < s.sentences.size) {
            ((now - sentenceStartedAt) / 1000.0).coerceIn(0.0, s.sentenceSec[sentence])
        } else 0.0
        return (base + inSentence).toInt()
    }
}

/** 세트 재편성에 필요한 것들 (AppContainer 가 제공) */
interface Planner {
    fun remainingSec(): Int?
    fun replan(heard: Set<String>, keep: List<Story>): List<Story>
    fun oneMore(heard: Set<String>, remainingSec: Int): Story?
}

/**
 * 사연 재생과 CREW 대화를 한 곳에서 관리한다.
 * 사연 본문은 문장 단위로 낭독하고, 질문이 들어오면 현재 문장 위치를 기억한 채 대화 모드로
 * 전환했다가 답변이 끝나면 그 문장부터 다시 이어간다.
 */
class StoryPlayer(
    context: Context,
    private val tts: TtsEngine,
    private val stt: SttEngine,
    private val crew: CrewClient,
    private val library: LibraryStore,
    private val planner: Planner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val heard = mutableSetOf<String>()

    /** 사연이 없을 때 CREW 호출 키를 누르면 할 일 (음성 주문). 화면 쪽에서 등록한다. */
    var idleVoice: (() -> Unit)? = null
    /** TTS/STT 콜백이 오래된 요청에 반응하지 않도록 하는 세대 번호 */
    private var gen = 0

    // ---------------------------------------------------------------- audio focus
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    private var pausedByFocus = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(SPEECH_ATTRIBUTES)
        .setOnAudioFocusChangeListener { change ->
            scope.launch {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { pause(); abandonFocus() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (_state.value.mode == Mode.PLAYING) { pausedByFocus = true; pause() }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        if (pausedByFocus) { pausedByFocus = false; play() }
                    }
                }
            }
        }
        .build()

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    // ---------------------------------------------------------------- 세트 시작

    fun startSet(set: DriveSet) {
        if (set.stories.isEmpty()) return
        stopAudio()
        _state.value = PlayerState(queue = set.stories, setType = set.type, transcript = _state.value.transcript.takeLast(4))
        val first = set.stories.first()
        val intro = "오늘의 메뉴는 ${set.type.label}, ${set.stories.size}편이에요. " +
            "첫 번째는 ${first.sauce.label}, ${first.title}. ${tagline(first.sauce)}"
        crewSay(intro) { narrate() }
    }

    fun playStory(story: Story) = startSet(DriveSet(SetType.SINGLE, listOf(story)))

    // ---------------------------------------------------------------- 재생 제어

    fun play() {
        val s = _state.value
        when (s.mode) {
            Mode.PAUSED -> narrate()
            Mode.FINISHED, Mode.IDLE -> if (s.current != null) {
                _state.update { it.copy(sentence = if (it.mode == Mode.FINISHED) 0 else it.sentence) }
                narrate()
            }
            else -> Unit
        }
    }

    fun pause() {
        val m = _state.value.mode
        if (m == Mode.PLAYING || m == Mode.CREW_SPEAKING || m == Mode.LISTENING || m == Mode.THINKING) {
            stopAudio()
            _state.update { it.copy(mode = Mode.PAUSED, partial = "") }
        }
    }

    fun togglePlay() = if (_state.value.mode == Mode.PLAYING) pause() else play()

    fun next() {
        val s = _state.value
        val cur = s.current ?: return
        library.record(cur.id, completed = false)
        heard += cur.id
        stopAudio()
        if (s.index + 1 < s.queue.size) {
            _state.update { it.copy(index = it.index + 1, sentence = 0) }
            announceAndNarrate()
        } else {
            finishOrOffer()
        }
    }

    fun previous() {
        val s = _state.value
        stopAudio()
        if (s.sentence > 2 || s.index == 0) _state.update { it.copy(sentence = 0) }
        else _state.update { it.copy(index = it.index - 1, sentence = 0) }
        narrate()
    }

    fun stop() {
        stopAudio()
        abandonFocus()
        _state.update { it.copy(mode = Mode.IDLE, partial = "", offer = null) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ---------------------------------------------------------------- CREW 대화

    /** 사연 도중 질문 (마이크 버튼 / 스티어링 음성 키) */
    fun askCrew() {
        val s = _state.value
        if (s.mode == Mode.OFFER) { listenForOfferReply(); return }
        // 재생 중인 사연이 없으면 질문 대신 음성 주문 ("달달한 썰 줘")
        val story = s.current?.takeIf { s.isActive || s.mode == Mode.PAUSED } ?: run { idleVoice?.invoke(); return }
        val wasPlaying = s.mode == Mode.PLAYING || s.mode == Mode.CREW_SPEAKING
        stopAudio()
        // 듣던 문장은 중간에 끊겼으니 답변 후 그 문장부터 다시 읽는다
        val resumeAt = s.sentence
        val heardUntil = (resumeAt).coerceAtMost(story.sentences.lastIndex)
        _state.update { it.copy(mode = Mode.LISTENING, partial = "") }
        (stt as? com.whatsthesauce.voice.DemoStt)?.purpose = com.whatsthesauce.voice.ListenPurpose.ASK
        stt.listen(
            onPartial = { p -> _state.update { it.copy(partial = p) } },
            onResult = { q ->
                _state.update { it.copy(mode = Mode.THINKING, partial = "", transcript = it.transcript + ChatLine(false, q)) }
                val myGen = gen
                scope.launch {
                    val answer = crew.chat(story, heardUntil, q, _state.value.transcript)
                    if (myGen != gen) return@launch
                    val tail = if (wasPlaying) " 그럼 이어서 들려드릴게요." else ""
                    crewSay(answer + tail) {
                        _state.update { it.copy(sentence = resumeAt) }
                        if (wasPlaying) narrate() else _state.update { it.copy(mode = Mode.PAUSED) }
                    }
                }
            },
            onError = { msg ->
                _state.update { it.copy(notice = "CREW가 못 들었어요: $msg") }
                crewSay(msg) {
                    if (wasPlaying) narrate() else _state.update { it.copy(mode = Mode.PAUSED) }
                }
            },
        )
    }

    /** Home 에서 음성으로 소스 주문 ("오늘은 달달한 썰이 듣고 싶은데, 반전도 조금") */
    fun orderByVoice(current: com.whatsthesauce.data.Flavor, onOrder: (OrderResult) -> Unit) {
        if (_state.value.mode == Mode.PLAYING) { askCrew(); return }
        stopAudio()
        val before = _state.value.mode.takeIf { it != Mode.LISTENING && it != Mode.THINKING } ?: Mode.IDLE
        _state.update { it.copy(mode = Mode.LISTENING, partial = "") }
        (stt as? com.whatsthesauce.voice.DemoStt)?.purpose = com.whatsthesauce.voice.ListenPurpose.ORDER
        stt.listen(
            onPartial = { p -> _state.update { it.copy(partial = p) } },
            onResult = { u ->
                _state.update { it.copy(mode = Mode.THINKING, partial = "", transcript = it.transcript + ChatLine(false, u)) }
                scope.launch {
                    val r = crew.order(u, current)
                    onOrder(r)
                    crewSay(r.reply + if (r.sauce != null || r.mix != current) " 이 세트로 준비해드릴까요?" else "") {
                        _state.update { it.copy(mode = before) }
                    }
                }
            },
            onError = { msg -> crewSay(msg) { _state.update { it.copy(mode = before) } } },
        )
    }

    /** 썰 제보 받아쓰기: 재생 중이면 멈추고 한 번 듣는다. 제보 글은 CREW 대화 기록에 남기지 않는다. */
    fun dictate(onText: (String) -> Unit) {
        val wasActive = _state.value.current != null && _state.value.isActive
        stopAudio()
        val after = if (wasActive) Mode.PAUSED else _state.value.mode.takeIf { it != Mode.LISTENING && it != Mode.THINKING } ?: Mode.IDLE
        _state.update { it.copy(mode = Mode.LISTENING, partial = "") }
        (stt as? com.whatsthesauce.voice.DemoStt)?.purpose = com.whatsthesauce.voice.ListenPurpose.DICTATE
        stt.listen(
            onPartial = { p -> _state.update { it.copy(partial = p) } },
            onResult = { text ->
                _state.update { it.copy(mode = after, partial = "") }
                onText(text)
            },
            onError = { msg -> _state.update { it.copy(mode = after, partial = "", notice = msg) } },
        )
    }

    /** 앱을 열었을 때 CREW 인사 (자동 재생 금지 정책에 맞게, 사연은 틀지 않고 말만 한다) */
    fun greet(remainingMin: Int?) {
        if (_state.value.mode != Mode.IDLE || _state.value.transcript.isNotEmpty()) return
        val eta = remainingMin?.let { "목적지까지 ${it}분 정도 남았네요. " } ?: ""
        crewSay("어서 오세요! ${eta}오늘은 어떤 맛의 썰로 드릴까요?") { _state.update { it.copy(mode = Mode.IDLE) } }
    }

    fun acceptOffer() {
        val offer = _state.value.offer ?: return
        stopAudio()
        _state.update { it.copy(queue = it.queue + offer, index = it.queue.size, sentence = 0, offer = null) }
        crewSay("좋아요! ${offer.sauce.label}, ${offer.title}.") { narrate() }
    }

    fun declineOffer() {
        stopAudio()
        _state.update { it.copy(offer = null) }
        crewSay("네, 남은 길은 편하게 가세요. 오늘 주문 감사합니다!") { _state.update { it.copy(mode = Mode.FINISHED) } }
    }

    private fun listenForOfferReply() {
        stopAudio()
        _state.update { it.copy(mode = Mode.LISTENING, partial = "") }
        (stt as? com.whatsthesauce.voice.DemoStt)?.purpose = com.whatsthesauce.voice.ListenPurpose.OFFER
        stt.listen(
            onPartial = { p -> _state.update { it.copy(partial = p) } },
            onResult = { u ->
                _state.update { it.copy(transcript = it.transcript + ChatLine(false, u), mode = Mode.OFFER) }
                if (Regex("응|네|좋아|그래|오케이|콜|틀어").containsMatchIn(u)) acceptOffer() else declineOffer()
            },
            onError = { _state.update { it.copy(mode = Mode.OFFER) } },
        )
    }

    // ---------------------------------------------------------------- 내부

    private fun narrate() {
        val s = _state.value
        val story = s.current ?: return
        if (s.sentence >= story.sentences.size) { storyFinished(); return }
        if (!requestFocus()) {
            _state.update { it.copy(mode = Mode.PAUSED, notice = "다른 오디오가 재생 중이에요") }
            return
        }
        val myGen = ++gen
        val idx = s.sentence
        _state.update { it.copy(mode = Mode.PLAYING, sentenceStartedAt = SystemClock.elapsedRealtime()) }
        tts.speak(
            story.sentences[idx],
            onDone = {
                if (myGen == gen && _state.value.mode == Mode.PLAYING) {
                    _state.update { it.copy(sentence = idx + 1) }
                    narrate()
                }
            },
            onError = { msg ->
                if (myGen == gen) _state.update { it.copy(mode = Mode.PAUSED, notice = "음성 출력 오류: $msg") }
            },
        )
    }

    private fun storyFinished() {
        val s = _state.value
        val cur = s.current ?: return
        library.record(cur.id, completed = true)
        heard += cur.id

        val remaining = planner.remainingSec()
        if (remaining != null && remaining <= ARRIVAL_SEC) {
            _state.update { it.copy(queue = it.queue.take(it.index + 1)) }
            crewSay("목적지에 거의 다 왔어요! 오늘 주문 감사합니다. 또 들러주세요!") { _state.update { it.copy(mode = Mode.FINISHED) } }
            return
        }
        // 대화로 시간이 늘었거나 ETA 가 바뀌었을 수 있으니 남은 메뉴를 다시 짠다
        val keep = s.queue.take(s.index + 1)
        val upcoming = planner.replan(heard, keep)
        _state.update { it.copy(queue = keep + upcoming) }

        if (s.index + 1 < _state.value.queue.size) {
            _state.update { it.copy(index = it.index + 1, sentence = 0) }
            announceAndNarrate()
        } else {
            finishOrOffer()
        }
    }

    private fun finishOrOffer() {
        val remaining = planner.remainingSec()
        val offer = remaining?.let { planner.oneMore(heard, it) }
        if (offer != null) {
            _state.update { it.copy(offer = offer) }
            crewSay("이제 거의 다 왔네요! 마지막으로 ${offer.minutes}분짜리 ${offer.sauce.label} 하나 더 어떠세요?") {
                _state.update { it.copy(mode = Mode.OFFER) }
            }
        } else {
            crewSay("오늘 세트는 여기까지예요. 맛있게 드셨나요?") { _state.update { it.copy(mode = Mode.FINISHED) } }
        }
    }

    private fun announceAndNarrate() {
        val story = _state.value.current ?: return
        crewSay("다음 메뉴는 ${story.sauce.label}, ${story.title}.") { narrate() }
    }

    private fun crewSay(text: String, then: () -> Unit) {
        requestFocus()
        val myGen = ++gen
        _state.update { it.copy(mode = Mode.CREW_SPEAKING, transcript = (it.transcript + ChatLine(true, text)).takeLast(20)) }
        tts.speak(
            text,
            onDone = { if (myGen == gen) then() },
            onError = { if (myGen == gen) then() },
        )
    }

    private fun stopAudio() {
        gen++
        tts.stop()
        stt.cancel()
    }

    private fun tagline(sauce: Sauce) = when (sauce) {
        Sauce.RANCH -> "담백하게 즐기기 좋은 맛이에요."
        Sauce.SWEET_CHILI -> "달콤하게 시작하지만 끝맛이 상당히 매울 수 있어요!"
        Sauce.HOT_SAUCE -> "화끈하니까 물 한 모금 준비하세요!"
        Sauce.CHEESE_DIP -> "따뜻하고 고소한 맛이에요."
        Sauce.WASABI_MAYO -> "코끝이 찡할 만큼 황당한 맛이에요!"
        Sauce.BLACK_PEPPER -> "묵직하고 알싸한 맛, 끝까지 긴장하세요."
    }

    fun release() {
        stop()
        tts.release()
        stt.release()
    }

    companion object {
        private const val ARRIVAL_SEC = 45
    }
}
