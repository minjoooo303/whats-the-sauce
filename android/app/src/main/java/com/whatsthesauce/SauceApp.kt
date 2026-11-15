package com.whatsthesauce

import android.app.Application
import com.whatsthesauce.car.DrivingMonitor
import com.whatsthesauce.car.EtaProvider
import com.whatsthesauce.crew.CrewClient
import com.whatsthesauce.data.DriveSet
import com.whatsthesauce.data.Flavor
import com.whatsthesauce.data.SubmissionQueue
import com.whatsthesauce.data.LibraryStore
import com.whatsthesauce.data.Sauce
import com.whatsthesauce.data.Story
import com.whatsthesauce.data.StoryRepository
import com.whatsthesauce.playback.Planner
import com.whatsthesauce.playback.SteeringKeys
import com.whatsthesauce.playback.StoryPlayer
import com.whatsthesauce.reco.Recommender
import com.whatsthesauce.voice.SwitchingStt
import com.whatsthesauce.voice.DemoStt
import com.whatsthesauce.voice.SwitchingTts
import kotlinx.coroutines.flow.MutableStateFlow

/** 앱 전역 의존성. 액티비티(UI)와 MediaBrowserService 가 같은 플레이어를 공유한다. */
class SauceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val stories = StoryRepository(app)
    val library = LibraryStore(app)
    val recommender = Recommender(stories)
    val eta = EtaProvider(app)
    val driving = DrivingMonitor(app)
    val crew = CrewClient(BuildConfig.CREW_BASE_URL)

    /** 현재 소스 믹싱 (Home 추천, Mix 화면, 재편성이 공유) */
    val mix = MutableStateFlow(Sauce.SWEET_CHILI.profile)

    private val planner = object : Planner {
        override fun remainingSec(): Int? = eta.eta.value.remainingSec

        override fun replan(heard: Set<String>, keep: List<Story>): List<Story> {
            val e = eta.eta.value
            return recommender.replan(
                mix.value, e.remainingMeters, e.remainingSec,
                heard + keep.map { it.id } + library.state.value.recentlyHeard(),
            )
        }

        override fun oneMore(heard: Set<String>, remainingSec: Int): Story? =
            recommender.oneMore(mix.value, remainingSec, heard)
    }

    /** 시연용 음성 입력. 디버그 빌드에선 처음부터 켜짐, 상단 "음성" 태그로 마이크와 전환 */
    val demoStt = DemoStt(
        SwitchingStt(app, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET),
        demoOnStart = BuildConfig.DEBUG,
    )

    val tts = SwitchingTts(app, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET)

    val player = StoryPlayer(
        context = app,
        tts = tts,
        stt = demoStt,
        crew = crew,
        library = library,
        planner = planner,
    )

    /** 스티어링 휠 재생 키 / 화면 재생 버튼: 짧게 = 재생·일시정지, 길게 = CREW에게 묻기 */
    val keys = SteeringKeys(player)

    fun buildSet(flavor: Flavor = mix.value, prefer: Sauce? = null): DriveSet {
        val e = eta.eta.value
        return recommender.buildSet(flavor, e.remainingMeters, e.remainingSec, library.state.value.recentlyHeard(), prefer)
    }

    /** 썰 제보: 서버에 닿지 않으면 기기에 보관했다가 다음 실행 때 다시 보낸다 */
    val factory = SubmissionQueue(app, crew)
}
