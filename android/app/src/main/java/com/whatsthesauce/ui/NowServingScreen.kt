package com.whatsthesauce.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whatsthesauce.R
import com.whatsthesauce.playback.Mode
import com.whatsthesauce.playback.PlayerState
import com.whatsthesauce.ui.theme.Sauce
import kotlinx.coroutines.delay

/**
 * Now Serving: 사연 제목, 진행률, 재생 컨트롤만. CREW 대화 자막은 띄우지 않는다.
 * CREW에게 묻기 = 핸들 재생 버튼(또는 화면 재생 버튼)을 길게 누르기.
 */
@Composable
fun NowServingScreen(
    s: PlayerState,
    saved: Set<String>,
    etaLabel: String?,
    onToggle: () -> Unit,
    onAsk: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSave: (String) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onGoHome: () -> Unit,
) {
    val story = s.current
    if (story == null) {
        Column(
            Modifier.fillMaxSize().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Image(painterResource(R.drawable.crew_full), contentDescription = "CREW", modifier = Modifier.size(220.dp))
            Text("아직 주문하신 메뉴가 없어요", fontSize = 28.sp, fontWeight = FontWeight.Black)
            BigButton("메뉴 고르러 가기", onGoHome)
        }
        return
    }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(s.mode, s.sentence) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(500) }
    }
    val pos = s.positionSec(now)
    val progress = (pos.toFloat() / story.durationSec.coerceAtLeast(1)).coerceIn(0f, 1f)

    Row(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 진행률 링 + 트레이 + 지금 소스
        BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val d = minOf(maxWidth, maxHeight) * 0.96f
            Box(Modifier.size(d), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 10.dp.toPx()
                    val tl = Offset(sw / 2, sw / 2)
                    val sz = Size(size.width - sw, size.height - sw)
                    drawArc(Sauce.Line, 0f, 360f, false, tl, sz, style = Stroke(sw))
                    drawArc(Sauce.Cherry, -90f, 360f * progress, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Image(
                    painterResource(FRIES[friesLevel(s.queue.size - s.index, s.queue.size)]),
                    contentDescription = "은색 트레이에 담긴 감자튀김",
                    modifier = Modifier.width(d * 0.9f),
                )
                SauceCup(story.sauce, d * 0.3f, Modifier.offset(x = d * 0.22f, y = d * 0.2f))
            }
        }

        Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tag("${story.sauce.label} · ${story.sauce.mood}")
                s.setType?.let { Tag("${it.label} ${s.index + 1}/${s.queue.size}", bg = Sauce.Card) }
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    story.title, Modifier.weight(1f), fontSize = 36.sp, lineHeight = 44.sp,
                    fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val isSaved = story.id in saved
                IconSquare(if (isSaved) "My Box 에서 빼기" else "My Box 에 담기", { onSave(story.id) }, size = 56.dp) {
                    Icon(
                        if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null, tint = Sauce.Cherry,
                    )
                }
            }
            Text(
                "${mmss(pos)} / ${mmss(story.durationSec)}" +
                    (s.next?.let { " · 다음: ${it.sauce.label}" } ?: "") +
                    (etaLabel?.let { " · 남은 거리 $it" } ?: ""),
                fontSize = 18.sp, color = Sauce.Muted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconSquare("이전 사연", onPrev, size = 72.dp) { SkipIcon(false, 28.dp) }
                PlayPauseButton(s.mode, onToggle, onAsk)
                IconSquare("다음 사연", onNext, size = 72.dp) { SkipIcon(true, 28.dp) }
                CrewStatus(s.mode, s.partial)
            }

            if (s.mode == Mode.OFFER && s.offer != null) {
                val o = s.offer
                OutlineCard {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SauceCup(o.sauce, 40.dp)
                        Text("${o.sauce.label} ${o.minutes}분 하나 더?", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        BigButton("좋아요", onAccept, height = 56.dp)
                        BigButton("괜찮아요", onDecline, container = Sauce.Card, content = Sauce.Ink, height = 56.dp)
                    }
                }
            } else {
                Text("재생 버튼을 길게 누르면 CREW에게 물어볼 수 있어요", fontSize = 15.sp, color = Sauce.Muted)
            }
        }
    }
}

/**
 * 짧게 = 재생/일시정지, 길게(0.5초) = CREW에게 묻기. 핸들 재생 버튼과 같은 규칙.
 * 누르고 있는 동안 버튼 테두리가 차오르고, 다 차면 바로 CREW 가 듣기 시작한다(손을 떼기 전).
 */
@Composable
private fun PlayPauseButton(mode: Mode, onToggle: () -> Unit, onAsk: () -> Unit) {
    val playing = mode == Mode.PLAYING || mode == Mode.CREW_SPEAKING
    val shape = RoundedCornerShape(22.dp)
    val hold = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(96.dp, 72.dp)
            .clip(shape)
            .background(Sauce.Cherry)
            .border(1.5.dp, Sauce.Ink, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val fill = scope.launch { hold.snapTo(0f); hold.animateTo(1f, tween(HOLD_MS.toInt(), easing = LinearEasing)) }
                    // HOLD_MS 안에 손을 떼면 탭, 아니면 길게 누르기
                    val up = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() }
                    fill.cancel()
                    if (up != null) {
                        onToggle()
                    } else {
                        onAsk()
                        // 손을 뗄 때까지 기다렸다가 다음 제스처로
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    }
                    scope.launch { hold.snapTo(0f) }
                }
            }
            .drawBehind {
                // 길게 누르는 진행 표시: 버튼 아래쪽부터 청록색이 차오름
                val h = size.height * hold.value
                drawRect(Sauce.Teal, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            }
            .semantics {
                contentDescription = if (playing) "일시정지 (길게 누르면 CREW에게 묻기)" else "재생 (길게 누르면 CREW에게 묻기)"
                onLongClick(label = "CREW에게 묻기") { onAsk(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hold.value > 0f) MicIcon(30.dp, Sauce.Ink)
        else if (playing) PauseIcon(30.dp, Sauce.OnCherry) else PlayIcon(30.dp, Sauce.OnCherry)
    }
}

private const val HOLD_MS = 500L

/** CREW 상태는 글자 대신 작은 표시 하나로만 */
@Composable
private fun CrewStatus(mode: Mode, heard: String = "") {
    val label = when (mode) {
        Mode.LISTENING -> "듣는 중"
        Mode.THINKING -> "생각 중"
        Mode.CREW_SPEAKING -> "CREW"
        else -> "CREW" // 평소엔 연한 칩으로 계속 보여서 사라진 것처럼 보이지 않게
    }
    val active = mode == Mode.LISTENING || mode == Mode.THINKING || mode == Mode.CREW_SPEAKING
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (active) Sauce.Teal else Sauce.Card)
            .border(1.5.dp, if (active) Sauce.Teal else Sauce.Line, RoundedCornerShape(999.dp))
            .alpha(if (active) 1f else 0.6f).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painterResource(R.drawable.crew_face), contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(Sauce.Cream),
        )
        if (mode == Mode.LISTENING) MicIcon(22.dp)
        // 듣는 동안엔 받아쓴 말을 칩 안에 짧게 (질문이 들어갔는지 확인용)
        Text(
            if (mode == Mode.LISTENING && heard.isNotBlank()) "\u201C$heard\u201D" else label,
            fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

fun mmss(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)
