package com.hereliesaz.cleanunderwear.data

import android.content.Context
import android.provider.ContactsContract
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which saved numbers currently have an account on a messaging app. WhatsApp, Signal, Telegram and
 * Viber each add a contact entry, under their own account type, for every saved number registered
 * with them, and drop it when the registration goes away. So a number with an entry is a line in
 * service right now; one without is, at best, weakly suspect (plenty of people use none of them).
 *
 * Read from the contacts provider under READ_CONTACTS, which the app already holds. It stands in
 * for the call log and SMS history, whose permissions Google Play restricts to default dialer and
 * SMS apps.
 *
 * @property appsOnDevice the messaging apps that have any contact entries at all; when empty there
 *   is nothing to compare against and the signal is skipped.
 */
class MessagingPresence(
    private val byPhone: Map<String, Set<String>>,
    val appsOnDevice: Set<String>
) {
    val available: Boolean get() = appsOnDevice.isNotEmpty()

    /** Messaging apps this number is registered with, by display name; empty when none. */
    fun appsFor(phone: String?): Set<String> =
        StringSimilarity.normalizePhone(phone)?.let { byPhone[it] }.orEmpty()

    class Builder {
        private val byPhone = HashMap<String, MutableSet<String>>()
        private val apps = HashSet<String>()

        /** [raw] is any text holding the number: a phone row, or a WhatsApp JID like "1504…@s.whatsapp.net". */
        fun add(accountType: String?, raw: String?) {
            val app = appFor(accountType) ?: return
            apps += app
            val digits = raw?.substringBefore('@')
            val key = StringSimilarity.normalizePhone(digits) ?: return
            byPhone.getOrPut(key) { mutableSetOf() } += app
        }

        fun build() = MessagingPresence(byPhone, apps)
    }

    companion object {
        /** Account type → name shown in evidence. */
        val ACCOUNT_TYPES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.thoughtcrime.securesms" to "Signal",
            "org.telegram.messenger" to "Telegram",
            "com.viber.voip" to "Viber"
        )

        fun appFor(accountType: String?): String? = accountType?.let { ACCOUNT_TYPES[it] }
    }
}

interface MessagingPresenceSource {
    suspend fun load(): MessagingPresence
}

@Singleton
class DeviceMessagingPresence @Inject constructor(
    @ApplicationContext private val context: Context
) : MessagingPresenceSource {

    override suspend fun load(): MessagingPresence = withContext(Dispatchers.IO) {
        val builder = MessagingPresence.Builder()
        val types = MessagingPresence.ACCOUNT_TYPES.keys.toList()
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} IN (${types.joinToString(",") { "?" }})"
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.Data.DATA1),
                selection,
                types.toTypedArray(),
                null
            )?.use { cursor ->
                val typeIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
                val dataIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                // Every row of these raw contacts: the phone row and the app's own action rows both
                // carry the number in DATA1, so either is enough.
                while (cursor.moveToNext()) builder.add(cursor.getString(typeIdx), cursor.getString(dataIdx))
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("Messaging presence scan failed: ${e.javaClass.simpleName}")
        }
        builder.build()
    }
}
