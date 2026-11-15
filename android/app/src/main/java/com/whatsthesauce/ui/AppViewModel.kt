package com.whatsthesauce.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whatsthesauce.SauceApp
import com.whatsthesauce.car.EtaProvider
import com.whatsthesauce.car.EtaSource
import com.whatsthesauce.data.DriveSet
import com.whatsthesauce.data.Flavor
import com.whatsthesauce.data.Sauce
import com.whatsthesauce.data.Story
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SauceApp).container

    val player = c.player.state
    val eta = c.eta.eta
    /** 디버그: 로고를 누르면 시연 대사를 앱 목소리로 차례로 읽는다(따로 녹음용) */
    fun speakDemoScript() { if (com.whatsthesauce.BuildConfig.DEBUG) com.whatsthesauce.voice.VoiceExport.speakAll(c.tts) }

    /** 시연용 음성(대본 문장) 켜짐 여부 */
    val demoVoice = c.demoStt.demo
    fun toggleDemoVoice() = c.demoStt.toggleDemo()
    val driving = c.driving.restricted
    val mix: StateFlow<Flavor> = c.mix.asStateFlow()
    val library = c.library.state
    val submissions = c.factory.items
    val stories: List<Story> = c.stories.all

    private val _tab = MutableStateFlow(Tab.HOME)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _set = MutableStateFlow(c.buildSet())
    /** Home 에 보여줄 추천 세트 (아직 주문 전) */
    val recommended: StateFlow<DriveSet> = _set.asStateFlow()

    /** 썰 제보 화면에서 받아쓰는 중인 글 */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private var testPreset = EtaProvider.TEST_PRESETS_KM.indexOf(EtaProvider.DEFAULT_TEST_KM)

    init {
        // 남은 거리로 정한 편수가 바뀌면 추천 세트를 다시 짠다
        viewModelScope.launch {
            c.eta.eta.map { c.recommender.storiesFor(it.remainingMeters) }.distinctUntilChanged().collect {
                if (!listening()) refresh()
            }
        }
        // 재생 중인 사연이 없을 때 CREW 키(핸들 재생 버튼 길게) → 음성 주문
        c.player.idleVoice = { voiceOrder() }
        c.player.greet(c.eta.eta.value.minutes)
    }

    fun select(t: Tab) { _tab.value = t }

    /** 세트를 듣는 중인지 (CREW 인사말 같은 짧은 말은 제외: 홈 화면 기준과 같음) */
    private fun listening(): Boolean = c.player.state.value.let {
        it.current != null && (it.isActive || it.mode == com.whatsthesauce.playback.Mode.PAUSED)
    }

    private fun refresh(prefer: Sauce? = null) {
        _set.value = c.buildSet(c.mix.value, prefer)
    }

    /** "이 세트 듣기" */
    fun orderRecommended() {
        c.player.startSet(_set.value)
        _tab.value = Tab.NOW
    }

    fun voiceOrder() {
        c.player.orderByVoice(c.mix.value) { r ->
            c.mix.value = r.mix
            refresh(r.sauce)
            _tab.value = Tab.HOME
        }
    }

    /** Sauce Menu 에서 소스 선택 → 그 소스 중심 세트를 바로 재생 */
    fun pickSauce(s: Sauce) {
        c.mix.value = s.profile
        refresh(s)
        orderRecommended()
    }

    fun setMix(f: Flavor) { c.mix.value = f.clamped() }

    fun applyMix() {
        refresh()
        _tab.value = Tab.HOME
    }

    /** 내비 안내가 없을 때 테스트 주행 거리 바꾸기 (4 → 12 → 25 → 45km) */
    fun cycleTestDistance() {
        if (c.eta.eta.value.source != EtaSource.TEST) return
        testPreset = (testPreset + 1) % EtaProvider.TEST_PRESETS_KM.size
        c.eta.setTestKm(EtaProvider.TEST_PRESETS_KM[testPreset])
        if (!listening()) refresh()
    }

    // 재생 버튼: 짧게 = 재생/일시정지, 길게 = CREW에게 묻기 (핸들 재생 키와 같은 규칙)
    fun togglePlay() = c.player.togglePlay()
    fun askCrew() = c.player.askCrew()
    fun next() = c.player.next()
    fun previous() = c.player.previous()
    fun acceptOffer() = c.player.acceptOffer()
    fun declineOffer() = c.player.declineOffer()
    fun clearNotice() = c.player.clearNotice()
    fun toggleSaved(id: String) = c.library.toggleSaved(id)
    fun story(id: String) = c.stories.get(id)

    fun play(story: Story) {
        c.player.playStory(story)
        _tab.value = Tab.NOW
    }

    // ---------------------------------------------------------------- Sauce Factory

    fun setDraft(text: String) { _draft.value = text.take(MAX_DRAFT) }

    /** 말로 제보: 한 번 말할 때마다 이어 붙인다 */
    fun dictate() {
        c.player.dictate { text ->
            _draft.value = listOf(_draft.value.trim(), text.trim()).filter { it.isNotBlank() }.joinToString(" ").take(MAX_DRAFT)
        }
    }

    fun submitDraft(sauce: Sauce?, nickname: String?, onDone: (Boolean) -> Unit) {
        val text = _draft.value.trim()
        if (text.length < MIN_DRAFT) return
        c.factory.submit(text, sauce, nickname) { onDone(it.sent) }
        _draft.value = ""
    }

    companion object {
        const val MIN_DRAFT = 10
        const val MAX_DRAFT = 2000
    }
}
