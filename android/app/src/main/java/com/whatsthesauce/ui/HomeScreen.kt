package com.whatsthesauce.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whatsthesauce.R
import com.whatsthesauce.car.Eta
import com.whatsthesauce.data.DriveSet
import com.whatsthesauce.data.Story
import com.whatsthesauce.playback.PlayerState
import com.whatsthesauce.ui.theme.Sauce
import kotlin.math.ceil

/** 남은 썰에 따라 줄어드는 감자튀김: 2 = 가득, 1 = 몇 가닥, 0 = 부스러기 */
val FRIES = intArrayOf(R.drawable.tray_fries_0, R.drawable.tray_fries_1, R.drawable.tray_fries_2)

/** 남은 비율을 3단계로: 2/3 초과 가득, 1/3 초과 몇 가닥, 그 이하(마지막 썰·다 들음) 부스러기 */
fun friesLevel(remaining: Int, total: Int): Int =
    if (total <= 0) 2 else (ceil(remaining.toDouble() / total * 3).toInt() - 1).coerceIn(0, 2)

/**
 * Home: 왼쪽은 크게 담은 감튀 트레이 + 소스, 오른쪽은 세로로 긴 썰 리스트.
 * 세트를 듣는 중이면 지금 세트를 보여주고, 남은 썰이 줄어들수록 감튀도 줄어든다.
 */
@Composable
fun HomeScreen(
    set: DriveSet,
    player: PlayerState,
    eta: Eta,
    onOrder: () -> Unit,
    onResume: () -> Unit,
) {
    val active = player.current != null && (player.isActive || player.mode == com.whatsthesauce.playback.Mode.PAUSED)
    val stories: List<Story> = if (active) player.queue else set.stories
    val playing = if (active) player.index else -1
    val remaining = if (active) stories.size - player.index else stories.size
    val level = friesLevel(remaining, stories.size)

    Row(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽: 감튀 트레이 + 소스 (크게)
        BoxWithConstraints(Modifier.weight(0.58f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val trayW = minOf(maxWidth * 0.92f, maxHeight * 1.30f)
            Box(Modifier.width(trayW)) {
                Image(
                    painterResource(FRIES[level]),
                    contentDescription = "남은 썰 ${remaining}편만큼 담긴 감자튀김",
                    modifier = Modifier.fillMaxWidth(),
                )
                val sauces = stories.drop(maxOf(playing, 0)).map { it.sauce }.distinct().take(2)
                sauces.getOrNull(0)?.let {
                    SauceCup(it, trayW * 0.33f, Modifier.align(Alignment.TopEnd).offset(x = 20.dp, y = (-34).dp))
                }
                sauces.getOrNull(1)?.let {
                    SauceCup(it, trayW * 0.27f, Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 28.dp))
                }
            }
        }

        // 오른쪽: 세로로 긴 썰 리스트
        Column(Modifier.weight(0.42f).fillMaxHeight().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(if (active) "지금 드시는 세트" else "지금, 이 여정에 딱", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(
                    "${set.type.takeIf { !active }?.label ?: player.setType?.label ?: ""} · " +
                        (if (active) "남은 썰 ${remaining}편" else "${stories.size}편") +
                        (eta.distanceLabel?.let { " · 남은 거리 $it" } ?: ""),
                    fontSize = 17.sp, color = Sauce.Muted,
                )
            }
            OutlineCard(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn {
                    itemsIndexed(stories, key = { i, s -> "$i-${s.id}" }) { i, s ->
                        if (i > 0) HorizontalDivider(color = Sauce.Line)
                        val done = active && i < playing
                        val now = i == playing
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 76.dp)
                                .then(if (now) Modifier.background(Sauce.Cream) else Modifier)
                                .alpha(if (done) 0.4f else 1f)
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            SauceCup(s.sauce, 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${s.sauce.label} · ${s.minutes}분" + if (now) " · 재생 중" else if (done) " · 다 먹음" else "",
                                    fontSize = 15.sp, color = Sauce.Muted,
                                )
                            }
                        }
                    }
                }
            }
            if (active) {
                BigButton("Now Serving 으로", onResume, Modifier.fillMaxWidth(), height = 68.dp,
                    leading = { PlayIcon(22.dp, Sauce.OnCherry) })
            } else {
                BigButton("이 세트 듣기", onOrder, Modifier.fillMaxWidth(), height = 68.dp,
                    leading = { PlayIcon(22.dp, Sauce.OnCherry) })
            }
        }
    }
}
