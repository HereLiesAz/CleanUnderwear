package com.hereliesaz.cleanunderwear.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
}

/**
 * Reads the device call log and SMS inbox/outbox. Both permissions are optional and requested only
 * from the review screen: without them the review still works, minus the history signals.
 */
@Singleton
class DeviceCommsHistory @Inject constructor(
    @ApplicationContext private val context: Context
) : CommsHistory {

    fun hasAccess(): Boolean = granted(Manifest.permission.READ_CALL_LOG) || granted(Manifest.permission.READ_SMS)

    override suspend fun load(): HistoryIndex? = withContext(Dispatchers.IO) {
        val builder = HistoryIndex.Builder()
        var readAnything = false
        if (granted(Manifest.permission.READ_CALL_LOG)) {
            readAnything = scan(CallLog.Calls.CONTENT_URI, CallLog.Calls.NUMBER, CallLog.Calls.DATE, builder) || readAnything
        }
        if (granted(Manifest.permission.READ_SMS)) {
            readAnything = scan(Telephony.Sms.CONTENT_URI, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, builder) || readAnything
        }
        if (readAnything) builder.build() else null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** @return whether the provider could be queried at all. */
    private fun scan(uri: Uri, numberColumn: String, dateColumn: String, builder: HistoryIndex.Builder): Boolean {
        return try {
            val cursor = context.contentResolver.query(uri, arrayOf(numberColumn, dateColumn), null, null, null)
                ?: return false
            cursor.use {
                val numberIdx = it.getColumnIndexOrThrow(numberColumn)
                val dateIdx = it.getColumnIndexOrThrow(dateColumn)
                while (it.moveToNext()) builder.add(it.getString(numberIdx), it.getLong(dateIdx))
            }
            true
        } catch (e: Exception) {
            DiagnosticLogger.log("History scan of $uri failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
