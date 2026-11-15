package com.whatsthesauce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whatsthesauce.data.Submission
import com.whatsthesauce.ui.theme.Sauce
import com.whatsthesauce.data.Sauce as SauceKind

/**
 * Sauce Factory: 썰 제보.
 *  - 주행 중: 말로만 제보 (마이크로 받아쓰기, 글자 입력 잠금)
 *  - 정차 중: 받아쓴 글을 고치거나 직접 입력
 * 보낸 글은 검수 후 AI 가 문장을 다듬고 개인정보를 지워 메뉴에 올린다.
 */
@Composable
fun FactoryScreen(
    draft: String,
    partial: String,
    listening: Boolean,
    driving: Boolean,
    items: List<Submission>,
    onDraft: (String) -> Unit,
    onDictate: () -> Unit,
    onSubmit: (SauceKind?, String?) -> Unit,
) {
    var sauce by remember { mutableStateOf<SauceKind?>(null) }
    var nickname by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(0.62f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("새 소스 제조", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Tag(if (driving) "주행 중 · 말로만 제보할 수 있어요" else "정차 중 · 직접 고칠 수 있어요", bg = Sauce.Cream)
            }

            // 어떤 맛? (모르면 CREW가 골라줌)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SauceChoice(null, sauce == null) { sauce = null }
                SauceKind.entries.forEach { s -> SauceChoice(s, sauce == s) { sauce = s } }
            }

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Sauce.Ink, unfocusedBorderColor = Sauce.Ink, disabledBorderColor = Sauce.Ink,
                focusedContainerColor = Sauce.Card, unfocusedContainerColor = Sauce.Card, disabledContainerColor = Sauce.Card,
                disabledTextColor = Sauce.Ink, cursorColor = Sauce.Cherry,
            )
            OutlinedTextField(
                value = if (listening && partial.isNotBlank()) listOf(draft, partial).filter { it.isNotBlank() }.joinToString(" ") else draft,
                onValueChange = onDraft,
                enabled = !driving && !listening,
                placeholder = { Text("마이크를 누르고 있었던 일을 편하게 말해주세요", fontSize = 18.sp, color = Sauce.Muted) },
                textStyle = TextStyle(fontSize = 19.sp, lineHeight = 28.sp, color = Sauce.Ink),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().height(190.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton(
                    if (listening) "듣고 있어요…" else "말로 제보하기", onDictate,
                    container = Sauce.Teal, content = Sauce.Ink, height = 64.dp, enabled = !listening,
                    leading = { MicIcon(26.dp) },
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(20) },
                    enabled = !driving,
                    singleLine = true,
                    placeholder = { Text("닉네임 (선택)", fontSize = 17.sp, color = Sauce.Muted) },
                    textStyle = TextStyle(fontSize = 18.sp, color = Sauce.Ink),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
            }
            val ready = draft.trim().length >= AppViewModel.MIN_DRAFT && !listening
            BigButton(
                "제보 보내기", { onSubmit(sauce, nickname.ifBlank { null }) }, Modifier.fillMaxWidth(),
                height = 64.dp, enabled = ready,
            )
        }

        // 내 제보
        OutlineCard(Modifier.weight(0.38f).fillMaxHeight()) {
            Column {
                Text("내 제보", Modifier.padding(horizontal = 20.dp, vertical = 14.dp), fontSize = 20.sp, fontWeight = FontWeight.Black)
                HorizontalDivider(thickness = 1.5.dp, color = Sauce.Ink)
                if (items.isEmpty()) {
                    Text(
                        "보낸 썰은 검수 후 AI가 다듬어서\n소스 메뉴에 올라가요.",
                        Modifier.padding(20.dp), fontSize = 17.sp, lineHeight = 25.sp, color = Sauce.Muted,
                    )
                }
                LazyColumn {
                    items(items, key = { it.localId }) { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            s.sauce?.let { SauceCup(it, 36.dp) }
                            Text(s.text, Modifier.weight(1f), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Tag(if (s.sent) "접수됨" else "전송 대기", bg = if (s.sent) Sauce.Cream else Sauce.Bg)
                        }
                        HorizontalDivider(color = Sauce.Line)
                    }
                }
            }
        }
    }
}

@Composable
private fun SauceChoice(s: SauceKind?, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) Sauce.Cherry else androidx.compose.ui.graphics.Color.Transparent
    Box(
        Modifier
            .size(60.dp)
            .clip(CircleShape)
            .border(3.dp, ring, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = (s?.label ?: "CREW가 골라줘") + if (selected) " 선택됨" else "" },
        contentAlignment = Alignment.Center,
    ) {
        if (s != null) SauceCup(s, 50.dp)
        else Box(
            Modifier.size(50.dp).clip(CircleShape).background(Sauce.Cream).border(1.5.dp, Sauce.Ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("?", fontSize = 22.sp, fontWeight = FontWeight.Black) }
    }
}
