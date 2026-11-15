package com.whatsthesauce.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val storyId: String, val at: Long, val completed: Boolean)

data class Library(val saved: Set<String> = emptySet(), val history: List<HistoryEntry> = emptyList()) {
    /** 최근에 끝까지 들은 사연 (추천에서 제외) */
    fun recentlyHeard(limit: Int = 6): Set<String> =
        history.filter { it.completed }.take(limit).map { it.storyId }.toSet()
}

/** My Box: 저장한 사연 + 청취 기록. 기기 로컬 SharedPreferences. */
class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("my_box", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Library> = _state.asStateFlow()

    fun toggleSaved(id: String) {
        val cur = _state.value
        val saved = if (id in cur.saved) cur.saved - id else cur.saved + id
        update(cur.copy(saved = saved))
    }

    fun record(id: String, completed: Boolean) {
        val cur = _state.value
        val entry = HistoryEntry(id, System.currentTimeMillis(), completed)
        update(cur.copy(history = (listOf(entry) + cur.history).take(50)))
    }

    private fun update(lib: Library) {
        _state.value = lib
        val h = JSONArray()
        lib.history.forEach { h.put(JSONObject().put("id", it.storyId).put("at", it.at).put("done", it.completed)) }
        prefs.edit()
            .putStringSet("saved", lib.saved)
            .putString("history", h.toString())
            .apply()
    }

    private fun load(): Library {
        val saved = prefs.getStringSet("saved", emptySet()) ?: emptySet()
        val raw = prefs.getString("history", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val history = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(o.getString("id"), o.getLong("at"), o.optBoolean("done"))
        }
        return Library(saved.toSet(), history)
    }
}
