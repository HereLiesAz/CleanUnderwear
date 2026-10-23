package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.match.StringSimilarity
import javax.inject.Inject
import javax.inject.Singleton

/** When you were in touch with one number: how often, and the first and last time. */
data class HistoryStats(val count: Int, val firstMillis: Long, val lastMillis: Long)

/**
 * Per-number call/SMS summary. Only counts and first/last timestamps are kept, and only in memory
 * for the duration of a review scan; no message text, contact or call detail is read or stored.
 *
 * @property oldestMillis the oldest event of any number — how far back the device history reaches,
 *   which bounds what silence can prove (many phones prune call logs after a year or so).
 */
class HistoryIndex(
    private val byPhone: Map<String, HistoryStats>,
    val oldestMillis: Long?
) {
    fun statsFor(phone: String?): HistoryStats? =
        StringSimilarity.normalizePhone(phone)?.let { byPhone[it] }

    class Builder {
        private val counts = HashMap<String, Int>()
        private val firsts = HashMap<String, Long>()
        private val lasts = HashMap<String, Long>()
        private var oldest: Long? = null

        fun add(number: String?, millis: Long) {
            if (millis <= 0L) return
            oldest = oldest?.let { minOf(it, millis) } ?: millis
            val key = StringSimilarity.normalizePhone(number) ?: return
            counts.merge(key, 1, Int::plus)
            firsts.merge(key, millis) { a, b -> minOf(a, b) }
            lasts.merge(key, millis) { a, b -> maxOf(a, b) }
        }

        fun build() = HistoryIndex(
            counts.mapValues { (k, c) -> HistoryStats(c, firsts.getValue(k), lasts.getValue(k)) },
            oldest
        )
    }
}

/** Source of [HistoryIndex]; null when the device history cannot be read. */
interface CommsHistory {
    suspend fun load(): HistoryIndex?

    /** Whether the app declares the permissions at all, so asking the user for them can work. */
    fun requestable(): Boolean = false
}

/**
 * No device history. The call log and SMS permissions are restricted on Google Play and the app
 * doesn't declare them, so nothing reads them: the review runs without history signals, and the
 * code that read them is gone rather than left armed for a permission that might be re-added.
 */
@Singleton
class NoCommsHistory @Inject constructor() : CommsHistory {
    override suspend fun load(): HistoryIndex? = null
}
