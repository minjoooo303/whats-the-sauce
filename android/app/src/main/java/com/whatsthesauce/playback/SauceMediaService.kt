package com.whatsthesauce.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.view.KeyEvent
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.whatsthesauce.R
import com.whatsthesauce.SauceApp
import com.whatsthesauce.crew.LocalCrew
import com.whatsthesauce.data.Sauce
import com.whatsthesauce.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 차량 미디어 시스템 연동 (Pleos Music/Audio 가이드: MBS + MediaSession 필수).
 *  - 스티어링 휠 / 미디어 버튼: 재생, 일시정지, 정지, 이전/다음 사연
 *  - 미디어 ID 재생: 브라우즈 트리에서 고른 사연
 *  - 검색 재생: "매운 썰 틀어줘" → 소스 매칭
 *  - 앱 연결 시 자동 재생하지 않음
 */
class SauceMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as SauceApp).container
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        // bind 만 되는 경우 onTaskRemoved 를 못 받을 수 있어 Started 상태로 만든다 (Pleos 가이드)
        runCatching { startService(Intent(this, SauceMediaService::class.java)) }

        session = MediaSessionCompat(this, "WhatsTheSauce").apply {
            setCallback(callback)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@SauceMediaService, 0, Intent(this@SauceMediaService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            isActive = true
        }
        sessionToken = session.sessionToken
        createChannel()

        scope.launch {
            container.player.state
                .map { Triple(it.mode, it.current?.id, it.sentence) }
                .distinctUntilChanged()
                .collect { sync() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        container.player.stop()
        stopForegroundCompat()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- browse tree

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = when {
            parentId == ROOT -> Sauce.entries.map { s ->
                MediaBrowserCompat.MediaItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId("sauce:${s.name}")
                        .setTitle(s.label)
                        .setSubtitle(s.mood)
                        .setIconBitmap(BitmapFactory.decodeResource(resources, s.cup))
                        .build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
                )
            }
            parentId.startsWith("sauce:") -> {
                val sauce = runCatching { Sauce.valueOf(parentId.removePrefix("sauce:")) }.getOrNull()
                sauce?.let { container.stories.bySauce(it) }.orEmpty().map { st ->
                    MediaBrowserCompat.MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId(st.id)
                            .setTitle(st.title)
                            .setSubtitle("${st.sauce.label} · ${st.minutes}분")
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                    )
                }
            }
            else -> emptyList()
        }
        result.sendResult(items.toMutableList())
    }

    // ---------------------------------------------------------------- session callback

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            val p = container.player
            if (p.state.value.current == null) p.startSet(container.buildSet()) else p.play()
        }
        override fun onPause() = container.player.pause()
        override fun onStop() = container.player.stop()
        override fun onSkipToNext() = container.player.next()
        override fun onSkipToPrevious() = container.player.previous()
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val story = mediaId?.let { container.stories.get(it) } ?: return
            container.player.playStory(story)
        }
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val r = LocalCrew.order(query.orEmpty(), container.mix.value)
            container.mix.value = r.mix
            container.player.startSet(container.buildSet(r.mix, r.sauce))
        }

        /** 시스템 미디어 화면에 뜨는 "CREW에게 묻기" 버튼 */
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_ASK_CREW) container.player.askCrew()
        }

        /**
         * 스티어링 휠 키 → CREW 호출
         *  - 재생/일시정지 키를 길게(0.6초) 누르면 CREW에게 묻기, 짧게 누르면 원래대로 재생/일시정지
         *  - 음성(PTT)·헤드셋 훅 키가 앱으로 전달되는 차량이면 바로 CREW에게 묻기
         */
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val ev = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return super.onMediaButtonEvent(mediaButtonEvent)
            return when (ev.keyCode) {
                KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> {
                    if (ev.action == KeyEvent.ACTION_UP) container.player.askCrew()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    keys.onPlayPauseKey(ev)
                    true
                }
                else -> super.onMediaButtonEvent(mediaButtonEvent)
            }
        }
    }

    private val keys get() = container.keys

    // ---------------------------------------------------------------- state sync

    private fun sync() {
        val s = container.player.state.value
        val st = when (s.mode) {
            Mode.PLAYING, Mode.CREW_SPEAKING, Mode.LISTENING, Mode.THINKING -> PlaybackStateCompat.STATE_PLAYING
            Mode.PAUSED, Mode.OFFER -> PlaybackStateCompat.STATE_PAUSED
            Mode.IDLE, Mode.FINISHED -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(ACTION_ASK_CREW, "CREW에게 묻기", R.drawable.ic_ask_crew).build()
                )
                .setState(st, s.positionSec() * 1000L, if (st == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .build()
        )
        s.current?.let { story ->
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, story.id)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, story.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "CREW · ${story.sauce.label}")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "What's the Sauce?")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, story.durationSec * 1000L)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, BitmapFactory.decodeResource(resources, story.sauce.cup))
                    .build()
            )
        }
        if (st == PlaybackStateCompat.STATE_PLAYING) startForegroundCompat()
    }

    // ---------------------------------------------------------------- foreground

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val s = container.player.state.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.crew_face)
            .setContentTitle(s.current?.title ?: getString(R.string.app_name))
            .setContentText(s.current?.sauce?.label ?: "CREW")
            .setContentIntent(session.controller.sessionActivity)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken))
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        runCatching {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        }
    }

    private fun stopForegroundCompat() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    companion object {
        const val ACTION_ASK_CREW = "com.whatsthesauce.ASK_CREW"
        private const val ROOT = "root"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 7
    }
}
