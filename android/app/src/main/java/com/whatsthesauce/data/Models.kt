package com.whatsthesauce.data

import androidx.annotation.DrawableRes
import com.whatsthesauce.R
import kotlin.math.sqrt

/** 맛의 축. 사연의 분위기를 실제 혀로 느끼는 맛으로 표현한다. */
enum class Taste(val label: String) {
    SWEET("단맛"),      // 달달한 설렘
    SPICY("매운맛"),    // 자극적인 갈등
    SAVORY("고소함"),   // 소소하고 따뜻한
    SOUR("새콤함"),     // 톡 쏘는 반전
    BITTER("씁쓸함"),   // 여운, 긴장
}

/** 사연의 맛 강도 (0~100). 소스 믹싱과 추천의 기준. */
data class Flavor(
    val sweet: Int = 50,
    val spicy: Int = 50,
    val savory: Int = 50,
    val sour: Int = 50,
    val bitter: Int = 50,
) {
    operator fun get(t: Taste): Int = when (t) {
        Taste.SWEET -> sweet; Taste.SPICY -> spicy; Taste.SAVORY -> savory; Taste.SOUR -> sour; Taste.BITTER -> bitter
    }

    fun with(t: Taste, v: Int): Flavor = when (t) {
        Taste.SWEET -> copy(sweet = v); Taste.SPICY -> copy(spicy = v); Taste.SAVORY -> copy(savory = v)
        Taste.SOUR -> copy(sour = v); Taste.BITTER -> copy(bitter = v)
    }

    /** 가장 강한 맛 두 개 ("단맛 70 · 새콤함 55") */
    fun summary(): String = Taste.entries.sortedByDescending { get(it) }.take(2).joinToString(" · ") { "${it.label} ${get(it)}" }

    private fun vec() = doubleArrayOf(sweet.toDouble(), spicy.toDouble(), savory.toDouble(), sour.toDouble(), bitter.toDouble())

    /** 코사인 유사도 0~1 */
    fun similarity(other: Flavor): Double {
        val a = vec()
        val b = other.vec()
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun clamped() = Flavor(
        sweet.coerceIn(0, 100), spicy.coerceIn(0, 100), savory.coerceIn(0, 100), sour.coerceIn(0, 100), bitter.coerceIn(0, 100),
    )

    /** 가장 강한 두 맛 (Mix 화면에서 소스컵 두 개로 표시) */
    fun topSauces(): List<Sauce> = Sauce.entries.sortedByDescending { it.profile.similarity(this) }.take(2)
}

enum class Sauce(
    val label: String,
    val mood: String,
    val tag: String,
    @DrawableRes val cup: Int,
    val profile: Flavor,
) {
    RANCH("Ranch", "담백하고 은은하게 고소한 맛", "담백", R.drawable.cup_ranch, Flavor(20, 5, 65, 10, 10)),
    SWEET_CHILI("Sweet Chili", "달콤하다가 끝맛이 매콤한 맛", "달콤", R.drawable.cup_sweetchili, Flavor(75, 35, 20, 55, 10)),
    HOT_SAUCE("Hot Sauce", "혀가 얼얼할 만큼 매운맛", "매움", R.drawable.cup_hotsauce, Flavor(10, 90, 10, 45, 40)),
    CHEESE_DIP("Cheese Dip", "진하고 짭짤하게 고소한 맛", "고소", R.drawable.cup_cheesedip, Flavor(45, 10, 90, 15, 10)),
    WASABI_MAYO("Wasabi Mayo", "코끝이 찡하게 톡 쏘는 새콤한 맛", "톡 쏨", R.drawable.cup_wasabimayo, Flavor(20, 45, 30, 75, 20)),
    BLACK_PEPPER("Black Pepper", "알싸하고 씁쓸한 맛", "씁쓸", R.drawable.cup_blackpepper, Flavor(10, 45, 15, 25, 80));
}

data class Story(
    val id: String,
    val sauce: Sauce,
    val title: String,
    val tags: Flavor,
    val sentences: List<String>,
) {
    /** 문장별 예상 낭독 시간(초). TTS 기준 약 6.5자/초 + 문장 사이 쉼 */
    val sentenceSec: List<Double> = sentences.map { it.length / CHARS_PER_SEC + PAUSE_SEC }
    val durationSec: Int = sentenceSec.sum().toInt()
    val minutes: Int get() = ((durationSec + 30) / 60).coerceAtLeast(1)

    fun secondsBefore(sentence: Int): Double = sentenceSec.take(sentence.coerceIn(0, sentences.size)).sum()

    companion object {
        const val CHARS_PER_SEC = 6.5
        const val PAUSE_SEC = 0.4
    }
}

enum class SetType(val label: String) {
    SINGLE("Single Set"),
    COMBO("Combo Set"),
    LARGE("Large Set");

    companion object {
        fun of(count: Int) = when {
            count <= 1 -> SINGLE
            count <= 3 -> COMBO
            else -> LARGE
        }
    }
}

/** 남은 주행거리에 맞춰 편성한 드라이브 세트 */
data class DriveSet(val type: SetType, val stories: List<Story>) {
    val totalSec: Int get() = stories.sumOf { it.durationSec }
}

data class ChatLine(val fromCrew: Boolean, val text: String)
