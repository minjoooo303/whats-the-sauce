package com.whatsthesauce.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsthesauce.SauceApp
import com.whatsthesauce.car.EtaSource
import com.whatsthesauce.playback.Mode
import com.whatsthesauce.playback.SauceMediaService
import com.whatsthesauce.ui.theme.Sauce
import com.whatsthesauce.ui.theme.SauceTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val keys get() = (application as SauceApp).container.keys

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 미디어 세션이 항상 살아 있도록 서비스 시작 (자동 재생은 하지 않음)
        runCatching { startService(Intent(this, SauceMediaService::class.java)) }

        val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) askPermissions.launch(need.toTypedArray())

        setContent { SauceTheme { App(vm) } }
    }

    /**
     * 앱이 앞에 있을 때 들어오는 핸들 키.
     *  - 음성(PTT)/검색 키가 앱까지 전달되는 차량이면: 바로 CREW에게 묻기
     *  - 재생/일시정지 키: 짧게 = 재생/일시정지, 길게 = CREW에게 묻기
     * 앱이 뒤에 있을 때는 미디어 세션(SauceMediaService)이 같은 규칙으로 처리한다.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> true
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { keys.onPlayPauseKey(event); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> { vm.askCrew(); true }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { keys.onPlayPauseKey(event); true }
        else -> super.onKeyUp(keyCode, event)
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val eta by vm.eta.collectAsStateWithLifecycle()
    val driving by vm.driving.collectAsStateWithLifecycle()
    val mix by vm.mix.collectAsStateWithLifecycle()
    val set by vm.recommended.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val subs by vm.submissions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(player.notice) {
        player.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearNotice()
        }
    }

    Column(Modifier.fillMaxSize().background(Sauce.Bg)) {
        TopBar {
            val dist = eta.distanceLabel?.let { "남은 거리 $it" } ?: "목적지 미설정"
            if (eta.source == EtaSource.TEST) Tag("$dist · 테스트", onClick = vm::cycleTestDistance) else Tag(dist)
            Tag(if (driving) "주행 중" else "정차 중", bg = Sauce.Bg)
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.HOME -> HomeScreen(set, player, eta, vm::orderRecommended, onResume = { vm.select(Tab.NOW) })
                Tab.MENU -> SauceMenuScreen(vm.stories, vm::pickSauce)
                Tab.MIX -> MixScreen(mix, driving, vm::setMix, vm::applyMix)
                Tab.NOW -> NowServingScreen(
                    s = player, saved = lib.saved, etaLabel = eta.distanceLabel,
                    onToggle = vm::togglePlay, onAsk = vm::askCrew, onPrev = vm::previous, onNext = vm::next,
                    onSave = vm::toggleSaved, onAccept = vm::acceptOffer, onDecline = vm::declineOffer,
                    onGoHome = { vm.select(Tab.HOME) },
                )
                Tab.BOX -> MyBoxScreen(lib, vm::story, vm::play, vm::toggleSaved)
                Tab.FACTORY -> FactoryScreen(
                    draft = draft, partial = player.partial, listening = player.mode == Mode.LISTENING,
                    driving = driving, items = subs,
                    onDraft = vm::setDraft, onDictate = vm::dictate,
                    onSubmit = { sauce, nick ->
                        vm.submitDraft(sauce, nick) { sent ->
                            Toast.makeText(
                                context,
                                if (sent) "제보가 접수됐어요. 감사합니다!" else "지금은 보낼 수 없어서 보관해 뒀어요. 연결되면 다시 보낼게요.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
        BottomNav(tab, vm::select)
    }
}
