package com.whatsthesauce.crew

import android.util.Log
import com.whatsthesauce.data.ChatLine
import com.whatsthesauce.data.Flavor
import com.whatsthesauce.data.Sauce
import com.whatsthesauce.data.Story
import com.whatsthesauce.data.Taste
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OrderResult(val sauce: Sauce?, val mix: Flavor, val reply: String)

/**
 * CREW 백엔드(FastAPI) 클라이언트. 서버가 없거나 응답이 늦으면 같은 규칙의 로컬 대체 응답을 쓴다.
 */
class CrewClient(private val baseUrl: String) {

    suspend fun chat(story: Story, heardUntil: Int, question: String, history: List<ChatLine>): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("story_id", story.id)
                    .put("heard_until", heardUntil)
                    .put("question", question)
                    .put("history", JSONArray().apply {
                        history.takeLast(6).forEach {
                            put(JSONObject().put("role", if (it.fromCrew) "crew" else "user").put("text", it.text))
                        }
                    })
                post("/crew/chat", body).getString("answer")
            }.onFailure { Log.w(TAG, "chat fallback: $it") }
                .getOrElse { LocalCrew.answer(story, heardUntil, question) }
        }

    suspend fun order(utterance: String, current: Flavor): OrderResult = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("utterance", utterance)
                .put("current", current.toJson())
            val r = post("/crew/order", body)
            val m = r.getJSONObject("mix")
            OrderResult(
                sauce = r.optString("sauce").takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Sauce.valueOf(it) }.getOrNull() },
                mix = Flavor(m.getInt("sweet"), m.getInt("spicy"), m.getInt("savory"), m.getInt("sour"), m.getInt("bitter")),
                reply = r.getString("reply"),
            )
        }.onFailure { Log.w(TAG, "order fallback: $it") }
            .getOrElse { LocalCrew.order(utterance, current) }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 2500
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** 썰 제보 (Sauce Factory). 성공하면 접수 번호, 서버에 닿지 않으면 null (기기에 보관해 두었다가 다시 보냄) */
    suspend fun submitStory(text: String, sauce: Sauce?, nickname: String?): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("text", text)
                .put("sauce", sauce?.name ?: JSONObject.NULL)
                .put("nickname", nickname ?: JSONObject.NULL)
            post("/factory/submit", body).getString("id")
        }.onFailure { Log.w(TAG, "submit failed: $it") }.getOrNull()
    }

    private fun Flavor.toJson() = JSONObject()
        .put("sweet", sweet).put("spicy", spicy).put("savory", savory).put("sour", sour).put("bitter", bitter)

    companion object {
        private const val TAG = "SauceCrew"
    }
}

/** 서버 없이 동작하는 규칙 기반 CREW (backend/main.py 의 fallback 과 같은 규칙) */
object LocalCrew {

    private fun bigrams(text: String): Set<String> {
        val t = text.filter { it.isLetterOrDigit() }
        return (0 until (t.length - 1).coerceAtLeast(0)).map { t.substring(it, it + 2) }.toSet()
    }

    fun answer(story: Story, heardUntil: Int, question: String): String {
        val heard = story.sentences.take(heardUntil + 1)
        val q = bigrams(question)
        val best = heard.maxByOrNull { (bigrams(it) intersect q).size }
        val score = best?.let { (bigrams(it) intersect q).size } ?: 0
        return if (best != null && score >= 2) "사연에 따르면요, $best"
        else "그 부분은 사연에 아직 안 나왔어요. 조금만 더 들어보시면 나올지도 몰라요!"
    }

    /** 실제 맛 표현 + 예전 분위기 표현을 함께 받아준다 */
    private val keywords: List<Pair<Regex, Sauce>> = listOf(
        Regex("랜치|담백|잔잔|소소|일상|순한") to Sauce.RANCH,
        Regex("스위트|칠리|달달|달콤|단맛|달게|설레|설렘|연애") to Sauce.SWEET_CHILI,
        Regex("핫소스|매운|맵|얼얼|화끈|자극|갈등") to Sauce.HOT_SAUCE,
        Regex("치즈|고소|짭짤|짠|진한|따뜻|힐링|훈훈") to Sauce.CHEESE_DIP,
        Regex("와사비|마요|새콤|톡쏘|톡 쏘|시큼|황당|웃긴|웃기|반전") to Sauce.WASABI_MAYO,
        Regex("후추|페퍼|씁쓸|쌉싸|알싸|미스터리|무서|소름|긴장") to Sauce.BLACK_PEPPER,
    )

    private val tasteWords: List<Pair<Regex, Taste>> = listOf(
        Regex("단맛|달게|달달|달콤|달") to Taste.SWEET,
        Regex("매운|맵게|맵|매콤") to Taste.SPICY,
        Regex("고소|짭짤|짜게") to Taste.SAVORY,
        Regex("새콤|시게|톡") to Taste.SOUR,
        Regex("씁쓸|쓰게|쌉싸") to Taste.BITTER,
    )

    fun order(utterance: String, current: Flavor): OrderResult {
        val u = utterance.replace(" ", "")
        // "조금 더 맵게", "덜 달게" → 현재 맛에서 한 축만 조절
        val more = Regex("(더|좀더|조금더)").containsMatchIn(u)
        val less = "덜" in u
        if (more || less) {
            val hit = tasteWords.firstOrNull { it.first.containsMatchIn(u) }?.second
            if (hit != null) {
                val mix = current.with(hit, current[hit] + if (less) -20 else 20).clamped()
                return OrderResult(null, mix, "네, ${hit.label}을 ${if (less) "줄여서" else "더해서"} 다시 담아볼게요!")
            }
        }
        val hits = keywords.filter { it.first.containsMatchIn(u) }.map { it.second }.distinct()
        if (hits.isEmpty()) {
            return OrderResult(null, current, "어떤 맛이 좋을지 한 번만 더 말씀해주세요. 달콤한 맛, 매운맛, 고소한 맛이 있어요!")
        }
        val n = hits.size
        val mix = Flavor(
            hits.sumOf { it.profile.sweet } / n,
            hits.sumOf { it.profile.spicy } / n,
            hits.sumOf { it.profile.savory } / n,
            hits.sumOf { it.profile.sour } / n,
            hits.sumOf { it.profile.bitter } / n,
        ).clamped()
        val names = hits.take(2).joinToString(" + ") { it.label }
        return OrderResult(if (n == 1) hits[0] else null, mix, "그럼 $names 어떠세요?")
    }
}
