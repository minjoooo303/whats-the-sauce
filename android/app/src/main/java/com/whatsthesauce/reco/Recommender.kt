package com.whatsthesauce.reco

import com.whatsthesauce.data.DriveSet
import com.whatsthesauce.data.Flavor
import com.whatsthesauce.data.Sauce
import com.whatsthesauce.data.SetType
import com.whatsthesauce.data.Story
import com.whatsthesauce.data.StoryRepository
import kotlin.math.ceil

/**
 * 소스 믹싱 추천 + 이동 시간 기반 세트 편성.
 * 사연 내용을 바꾸지 않고, 원하는 감정 조합에 맞는 사연을 골라 순서를 짠다.
 */
class Recommender(private val repo: StoryRepository) {

    /** mix 와 가까운 순. 최근 들은 사연은 뒤로, 고정 소스가 있으면 그 소스를 앞으로 */
    fun rank(mix: Flavor, exclude: Set<String> = emptySet(), preferSauce: Sauce? = null): List<Story> =
        repo.all.sortedByDescending { s ->
            var score = s.tags.similarity(mix)
            if (s.id in exclude) score -= 0.5
            if (preferSauce != null && s.sauce == preferSauce) score += 0.3
            score
        }

    /**
     * 남은 주행거리로 세트 편수를 정한다. 약 8km(평균 40km/h 기준 12분)마다 1편, 최대 8편.
     *  - 1편     : Single
     *  - 2~3편   : Combo
     *  - 4편 이상 : Large
     * 남은 시간을 알면 그 시간을 넘지 않도록 한 번 더 자른다. 거리를 모르면 3편(Combo).
     */
    fun buildSet(
        mix: Flavor,
        remainingMeters: Int?,
        remainingSec: Int?,
        exclude: Set<String> = emptySet(),
        preferSauce: Sauce? = null,
    ): DriveSet {
        val count = storiesFor(remainingMeters)
        val picked = fill(rank(mix, exclude, preferSauce), count, remainingSec?.let { (it * 0.9).toInt() })
        return DriveSet(SetType.of(picked.size), picked)
    }

    /** 지금 듣는 사연 뒤를 남은 거리에 맞게 다시 채운다 (대화로 시간이 늘었거나 경로가 바뀐 경우) */
    fun replan(mix: Flavor, remainingMeters: Int?, remainingSec: Int?, exclude: Set<String>): List<Story> {
        if (remainingSec != null && remainingSec < MIN_NEXT_SEC) return emptyList()
        val count = storiesFor(remainingMeters) - 1   // 지금 듣는 사연 몫을 뺀다
        if (count <= 0) return emptyList()
        return fill(rank(mix, exclude), count, remainingSec?.let { (it * 0.9).toInt() }).filter { it.id !in exclude }
    }

    fun storiesFor(remainingMeters: Int?): Int {
        val m = remainingMeters ?: return DEFAULT_COUNT
        return ceil(m / METERS_PER_STORY).toInt().coerceIn(1, MAX_COUNT)
    }

    /** 남은 시간 안에 들어가는 가장 잘 맞는 짧은 사연 하나 (도착 직전 "하나 더 어떠세요?") */
    fun oneMore(mix: Flavor, remainingSec: Int, exclude: Set<String>): Story? =
        rank(mix, exclude).firstOrNull { it.id !in exclude && it.durationSec <= remainingSec }

    /** 같은 소스가 연속되지 않게 count 편을 고른다. budgetSec 이 있으면 그 시간 안에서만. */
    private fun fill(ranked: List<Story>, count: Int, budgetSec: Int?): List<Story> {
        val out = mutableListOf<Story>()
        var used = 0
        val pool = ranked.toMutableList()
        val fits = { s: Story -> budgetSec == null || used + s.durationSec <= budgetSec }
        while (out.size < count && pool.isNotEmpty()) {
            val next = pool.firstOrNull { fits(it) && (out.isEmpty() || out.last().sauce != it.sauce) }
                ?: pool.firstOrNull { fits(it) } ?: break
            out += next
            used += next.durationSec
            pool.remove(next)
        }
        if (out.isEmpty()) out += ranked.minBy { it.durationSec }
        return out
    }

    companion object {
        const val METERS_PER_STORY = 8_000.0
        const val MAX_COUNT = 8
        const val DEFAULT_COUNT = 3
        const val MIN_NEXT_SEC = 90
    }
}
