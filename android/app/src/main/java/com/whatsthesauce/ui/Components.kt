package com.whatsthesauce.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import com.whatsthesauce.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whatsthesauce.data.Sauce as SauceKind
import com.whatsthesauce.ui.theme.LogoFont
import com.whatsthesauce.ui.theme.Sauce

enum class Tab(val label: String) {
    HOME("Home"), MENU("Sauce Menu"), MIX("Mix"), NOW("Now Serving"), BOX("My Box"), FACTORY("Sauce Factory")
}

/** 위: 로고 + 상태 칩 (남은 거리, 주행 상태) */
@Composable
fun TopBar(right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("What's the Sauce?", fontFamily = LogoFont, fontSize = 40.sp, color = Sauce.Cherry)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { right() }
    }
}

/** 아래 가운데 메뉴. 지금 화면은 케첩 자국(R.drawable.ketchup) 위에 흰 글씨로 표시 */
@Composable
fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(84.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val sel = t == tab
            Box(
                Modifier
                    .height(72.dp)
                    .widthIn(min = 150.dp)
                    .clickable(onClick = { onTab(t) })
                    .semantics { contentDescription = t.label + if (sel) " (현재 화면)" else "" },
                contentAlignment = Alignment.Center,
            ) {
                if (sel) {
                    Image(
                        painterResource(R.drawable.ketchup),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Text(
                    t.label,
                    // 케첩 몸통(위쪽 80%) 가운데에 오도록 살짝 위로
                    modifier = Modifier.padding(horizontal = 28.dp).offset(y = if (sel) (-5).dp else 0.dp),
                    color = if (sel) Sauce.OnCherry else Sauce.Ink,
                    fontSize = 19.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun Tag(text: String, bg: Color = Sauce.Cream, outlined: Boolean = true, onClick: (() -> Unit)? = null) {
    var m = Modifier.clip(RoundedCornerShape(12.dp)).background(bg)
    if (outlined) m = m.border(1.5.dp, Sauce.Ink, RoundedCornerShape(12.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Sauce.Ink)
    }
}

@Composable
fun OutlineCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Sauce.Card)
            .border(1.5.dp, Sauce.Ink, RoundedCornerShape(16.dp))
    ) { content() }
}

@Composable
fun SauceCup(sauce: SauceKind, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(sauce.cup),
        contentDescription = "${sauce.label} 소스",
        modifier = modifier.size(size).shadow(14.dp, CircleShape).clip(CircleShape),
    )
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Sauce.Cherry,
    content: Color = Sauce.OnCherry,
    enabled: Boolean = true,
    height: Dp = 72.dp,
    leading: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, Sauce.Ink),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 22.dp),
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

/** 아이콘만 있는 큰 버튼 (터치 타깃 ≥ 64dp) */
@Composable
fun IconSquare(
    label: String,
    onClick: () -> Unit,
    size: Dp = 72.dp,
    bg: Color = Sauce.Card,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .border(1.5.dp, Sauce.Ink, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

// ---------------------------------------------------------------- 아이콘 (선 아이콘, 직접 그림)

@Composable
fun MicIcon(size: Dp = 30.dp, color: Color = Sauce.Ink) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    drawRoundRect(color, Offset(w * 0.36f, w * 0.1f), Size(w * 0.28f, w * 0.48f), CornerRadius(w * 0.14f), style = stroke)
    drawArc(color, 0f, 180f, false, Offset(w * 0.2f, w * 0.22f), Size(w * 0.6f, w * 0.52f), style = stroke)
    drawLine(color, Offset(w * 0.5f, w * 0.74f), Offset(w * 0.5f, w * 0.9f), w * 0.09f, StrokeCap.Round)
}

@Composable
fun PlayIcon(size: Dp = 28.dp, color: Color = Sauce.Ink) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    drawPath(Path().apply { moveTo(w * 0.25f, w * 0.15f); lineTo(w * 0.85f, w * 0.5f); lineTo(w * 0.25f, w * 0.85f); close() }, color)
}

@Composable
fun PauseIcon(size: Dp = 28.dp, color: Color = Sauce.Ink) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    drawRoundRect(color, Offset(w * 0.22f, w * 0.18f), Size(w * 0.18f, w * 0.64f), CornerRadius(w * 0.04f))
    drawRoundRect(color, Offset(w * 0.6f, w * 0.18f), Size(w * 0.18f, w * 0.64f), CornerRadius(w * 0.04f))
}

@Composable
fun SkipIcon(forward: Boolean, size: Dp = 26.dp, color: Color = Sauce.Ink) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val p = Path()
    if (forward) {
        p.moveTo(w * 0.2f, w * 0.18f); p.lineTo(w * 0.66f, w * 0.5f); p.lineTo(w * 0.2f, w * 0.82f); p.close()
        drawPath(p, color)
        drawRect(color, Offset(w * 0.7f, w * 0.18f), Size(w * 0.1f, w * 0.64f))
    } else {
        p.moveTo(w * 0.8f, w * 0.18f); p.lineTo(w * 0.34f, w * 0.5f); p.lineTo(w * 0.8f, w * 0.82f); p.close()
        drawPath(p, color)
        drawRect(color, Offset(w * 0.2f, w * 0.18f), Size(w * 0.1f, w * 0.64f))
    }
}

@Composable
fun SpeechBubble(fromCrew: Boolean, text: String, modifier: Modifier = Modifier) {
    val shape = if (fromCrew) RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp) else RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (fromCrew) Sauce.Cream else Sauce.Teal)
            .then(if (fromCrew) Modifier else Modifier.border(1.5.dp, Sauce.Ink, shape))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) { Text(text, fontSize = 18.sp, lineHeight = 26.sp, color = Sauce.Ink) }
}

@Composable
fun RowScope.Gap(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun Labeled(label: String, value: String) {
    Column {
        Text(label, fontSize = 14.sp, color = Sauce.Muted)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
