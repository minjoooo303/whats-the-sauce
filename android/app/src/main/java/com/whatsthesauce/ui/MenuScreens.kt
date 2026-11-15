package com.whatsthesauce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whatsthesauce.data.Flavor
import com.whatsthesauce.data.Library
import com.whatsthesauce.data.Story
import com.whatsthesauce.data.Taste
import com.whatsthesauce.ui.theme.Sauce
import com.whatsthesauce.data.Sauce as SauceKind

// ---------------------------------------------------------------- Sauce Menu

@Composable
fun SauceMenuScreen(stories: List<Story>, onPick: (SauceKind) -> Unit) {
    // 두 줄을 화면 가운데로 모으고, 남는 높이만큼 소스컵을 키운다 (최대 260dp)
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 4.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val rowGap = 36.dp
        val textH = 72.dp
        val cup = minOf((maxHeight - rowGap - textH * 2) / 2, (maxWidth / 3) * 0.7f, 260.dp).coerceAtLeast(96.dp)
        Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
            SauceKind.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { s -> SauceTile(s, cup, Modifier.weight(1f)) { onPick(s) } }
                }
            }
        }
    }
}

/** 칸 없이: 소스컵 바로 아래에 이름 + 맛 태그, 맛 설명 한 줄 */
@Composable
private fun SauceTile(s: SauceKind, cup: Dp, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${s.label}, ${s.mood}. 이 소스로 듣기" }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SauceCup(s, cup)
        Row(
            Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(s.label, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(Sauce.Cream).padding(horizontal = 12.dp, vertical = 3.dp)
            ) { Text(s.tag, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        Text(s.mood, fontSize = 17.sp, color = Sauce.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Mix

@Composable
fun MixScreen(mix: Flavor, driving: Boolean, onChange: (Flavor) -> Unit, onApply: () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 접시 위 두 소스 (가장 가까운 두 맛)
        // 접시 + 바로 아래 맛 요약
        BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val d = minOf(maxWidth, maxHeight - 64.dp) * 0.92f
            val (a, b) = mix.topSauces().let { it[0] to it[1] }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(d).shadow(24.dp, CircleShape).clip(CircleShape).background(Sauce.Card), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(d * 0.74f).border(1.dp, Sauce.Line, CircleShape))
                    SauceCup(a, d * 0.42f, Modifier.offset(x = -(d * 0.08f), y = -(d * 0.06f)))
                    SauceCup(b, d * 0.26f, Modifier.offset(x = d * 0.2f, y = d * 0.18f))
                }
                Tag("${mix.summary()} → ${a.label} 계열", bg = Sauce.Card)
            }
        }

        Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Tag(if (driving) "주행 중에는 조절할 수 없어요 · 정차 후 조절" else "정차 중에만 조절할 수 있어요")
            OutlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Taste.entries.forEach { taste ->
                        FlavorSlider(taste.label, mix[taste], !driving) { onChange(mix.with(taste, it)) }
                    }
                }
            }
            BigButton("이 맛으로 사연 추천받기", onApply, Modifier.fillMaxWidth(), container = Sauce.Ink, content = Sauce.OnCherry)
        }
    }
}

/**
 * 맛 슬라이더. 트랙 아무 곳이나 누르면 그 값으로 바로 바뀌고, 끌어도 된다(10 단위).
 * (기본 Slider 는 에뮬레이터 마우스 클릭에서 값이 0 으로 튀는 경우가 있어 직접 만든다)
 */
@Composable
private fun FlavorSlider(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Canvas(
            Modifier
                .weight(1f)
                .height(40.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val pad = 12.dp.toPx()
                    fun at(x: Float): Int {
                        val f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                        return (Math.round(f * 10) * 10)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        latest(at(down.position.x))
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            latest(at(c.position.x))
                            c.consume()
                        }
                    }
                }
                .semantics { contentDescription = "$label $value" },
        ) {
            val pad = 12.dp.toPx()
            val w = size.width - 2 * pad
            val cy = size.height / 2
            val h = 10.dp.toPx()
            val x = pad + w * value / 100f
            drawRoundRect(Sauce.Line, Offset(pad, cy - h / 2), Size(w, h), CornerRadius(h / 2))
            drawRoundRect(Sauce.Cherry, Offset(pad, cy - h / 2), Size(x - pad, h), CornerRadius(h / 2))
            for (i in 0..10) drawCircle(if (i * 10 <= value) Sauce.Cream else Sauce.Cherry, 1.5.dp.toPx(), Offset(pad + w * i / 10f, cy))
            drawRoundRect(Sauce.Cherry, Offset(x - 2.dp.toPx(), cy - 14.dp.toPx()), Size(4.dp.toPx(), 28.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
        Text("$value", Modifier.width(48.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

// ---------------------------------------------------------------- My Box

@Composable
fun MyBoxScreen(lib: Library, find: (String) -> Story?, onPlay: (Story) -> Unit, onSave: (String) -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BoxColumn("저장한 사연", lib.saved.mapNotNull(find), lib.saved, Modifier.weight(1f), onPlay, onSave)
        val recent = lib.history.map { it.storyId }.distinct().mapNotNull(find).take(12)
        BoxColumn("최근 들은 사연", recent, lib.saved, Modifier.weight(1f), onPlay, onSave)
    }
}

@Composable
private fun BoxColumn(
    title: String, items: List<Story>, saved: Set<String>, modifier: Modifier,
    onPlay: (Story) -> Unit, onSave: (String) -> Unit,
) {
    OutlineCard(modifier.fillMaxHeight()) {
        Column {
            Text(title, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), fontSize = 20.sp, fontWeight = FontWeight.Black)
            HorizontalDivider(thickness = 1.5.dp, color = Sauce.Ink)
            if (items.isEmpty()) {
                Text("아직 비어 있어요", Modifier.padding(20.dp), fontSize = 18.sp, color = Sauce.Muted)
            }
            LazyColumn {
                items(items, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(s) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SauceCup(s.sauce, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${s.sauce.label} · ${s.minutes}분", fontSize = 15.sp, color = Sauce.Muted)
                        }
                        IconSquare(if (s.id in saved) "My Box 에서 빼기" else "My Box 에 담기", { onSave(s.id) }, size = 52.dp) {
                            Icon(
                                if (s.id in saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = null, tint = Sauce.Cherry,
                            )
                        }
                    }
                    HorizontalDivider(color = Sauce.Line)
                }
            }
        }
    }
}
