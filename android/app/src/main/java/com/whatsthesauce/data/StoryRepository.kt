package com.whatsthesauce.data

import android.content.Context
import org.json.JSONObject

/** assets/stories.json (백엔드 backend/data/stories.json 과 같은 파일) 을 읽는다. */
class StoryRepository(context: Context) {

    val all: List<Story> = run {
        val json = context.assets.open("stories.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(json).getJSONArray("stories")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.getJSONObject("tags")
            val s = o.getJSONArray("sentences")
            Story(
                id = o.getString("id"),
                sauce = Sauce.valueOf(o.getString("sauce")),
                title = o.getString("title"),
                tags = Flavor(t.getInt("sweet"), t.getInt("spicy"), t.getInt("savory"), t.getInt("sour"), t.getInt("bitter")),
                sentences = (0 until s.length()).map { s.getString(it) },
            )
        }
    }

    private val byId = all.associateBy { it.id }

    fun get(id: String): Story? = byId[id]
    fun bySauce(sauce: Sauce): List<Story> = all.filter { it.sauce == sauce }
}
