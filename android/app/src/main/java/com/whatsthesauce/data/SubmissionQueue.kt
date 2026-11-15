package com.whatsthesauce.data

import android.content.Context
import com.whatsthesauce.crew.CrewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Submission(
    val localId: Long,
    val text: String,
    val sauce: Sauce?,
    val nickname: String?,
    val serverId: String?,   // null 이면 아직 전송 대기
) {
    val sent: Boolean get() = serverId != null
}

/** 썰 제보 (Sauce Factory). 서버에 닿지 않으면 기기에 보관했다가 다음에 다시 보낸다. */
class SubmissionQueue(context: Context, private val crew: CrewClient) {
    private val prefs = context.getSharedPreferences("sauce_factory", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Submission>> = _items.asStateFlow()

    init {
        retryPending()
    }

    fun submit(text: String, sauce: Sauce?, nickname: String?, onDone: (Submission) -> Unit = {}) {
        val sub = Submission(System.currentTimeMillis(), text.trim(), sauce, nickname?.trim()?.ifBlank { null }, null)
        update(listOf(sub) + _items.value)
        scope.launch {
            val id = crew.submitStory(sub.text, sub.sauce, sub.nickname)
            val done = sub.copy(serverId = id)
            if (id != null) update(_items.value.map { if (it.localId == sub.localId) done else it })
            onDone(done)
        }
    }

    fun retryPending() {
        _items.value.filter { !it.sent }.forEach { sub ->
            scope.launch {
                val id = crew.submitStory(sub.text, sub.sauce, sub.nickname) ?: return@launch
                update(_items.value.map { if (it.localId == sub.localId) it.copy(serverId = id) else it })
            }
        }
    }

    private fun update(list: List<Submission>) {
        _items.value = list.take(30)
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(
                JSONObject()
                    .put("localId", it.localId)
                    .put("text", it.text)
                    .put("sauce", it.sauce?.name ?: "")
                    .put("nickname", it.nickname ?: "")
                    .put("serverId", it.serverId ?: "")
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun load(): List<Submission> {
        val arr = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Submission(
                localId = o.getLong("localId"),
                text = o.getString("text"),
                sauce = o.optString("sauce").takeIf { s -> s.isNotBlank() }?.let { s -> runCatching { Sauce.valueOf(s) }.getOrNull() },
                nickname = o.optString("nickname").ifBlank { null },
                serverId = o.optString("serverId").ifBlank { null },
            )
        }
    }
}
