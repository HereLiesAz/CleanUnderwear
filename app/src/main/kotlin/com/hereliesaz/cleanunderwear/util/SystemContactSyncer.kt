package com.hereliesaz.cleanunderwear.util

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a contact's settled status into its phone-book note, so a reinstall (or a new phone
 * syncing the same account) can restore it ([com.hereliesaz.cleanunderwear.data.ContactHarvester]
 * reads it back).
 *
 * The app owns one block in the note, from its "[Registry Status: …]" line to its signature
 * line ([ContactNote]). That block is rewritten when its content changes and nothing else in the
 * note is touched: whatever the user wrote stays, above it.
 *
 * Only settled statuses are written: Monitoring, and what the user confirmed or chose
 * (Incarcerated, Deceased, Archived). A possible match is never written: notes sync to the
 * contact's account (Google, Samsung…), and an unconfirmed "may have died" doesn't belong there.
 */
@Singleton
class SystemContactSyncer @Inject constructor(@ApplicationContext private val context: Context) {

    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US).withZone(ZoneId.systemDefault())

    fun syncToSystem(target: Target) {
        try {
            val block = ContactNote.block(target) { dateFormat.format(Instant.ofEpochMilli(it)) } ?: return
            // A stale number may now belong to a stranger's contact; never write this person's status there.
            val phone = target.usablePhone ?: return
            val contactId = findContactId(context.contentResolver, phone, target.displayName) ?: return
            upsertBlock(context.contentResolver, contactId, block)
        } catch (e: Exception) {
            Log.e("ContactSyncer", "Failed to sync a contact note", e)
        }
    }

    /**
     * Removes this app's block from every contact note, leaving the user's own note text (and
     * deleting a note that held nothing else). Returns how many notes were changed.
     */
    fun removeAllBlocks(): Int {
        val resolver = context.contentResolver
        val projection = arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Note.NOTE)
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Note.NOTE} LIKE ?"
        val args = arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "%[Registry Status:%")
        val ops = ArrayList<ContentProviderOperation>()
        resolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val rest = ContactNote.strip(cursor.getString(1).orEmpty())
                val sel = "${ContactsContract.Data._ID} = ?"
                ops += if (rest.isBlank()) {
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI).withSelection(sel, arrayOf(id)).build()
                } else {
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection(sel, arrayOf(id))
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, rest).build()
                }
            }
        }
        if (ops.isEmpty()) return 0
        return try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            ops.size
        } catch (e: Exception) {
            Log.e("ContactSyncer", "Removing note blocks failed", e)
            0
        }
    }

    /**
     * The phone-book contact that has [phone] *and* is the same person by name. A number shared
     * by a household, or recycled to someone else the user also knows, must not carry this
     * person's status, so a number alone is not enough.
     */
    private fun findContactId(resolver: ContentResolver, phone: String, displayName: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME)
        val wanted = nameKey(displayName)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                if (name != null && nameKey(name) == wanted) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID))
                }
            }
        }
        return null
    }

    private fun nameKey(name: String): String =
        StringSimilarity.foldDiacritics(name).lowercase().filter { it.isLetter() }

    private fun upsertBlock(resolver: ContentResolver, contactId: String, block: String) {
        val existing = findNote(resolver, contactId)
        val updated = ContactNote.merge(existing?.second, block)
        if (existing != null && updated == existing.second) return // unchanged: don't trigger a sync
        val op = if (existing != null) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(existing.first))
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, updated)
                .build()
        } else {
            val rawContactId = findRawContactId(resolver, contactId) ?: run {
                Log.w("ContactSyncer", "No raw contact for contact $contactId; cannot insert note")
                return
            }
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, updated)
                .build()
        }
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(listOf(op)))
        } catch (e: Exception) {
            Log.e("ContactSyncer", "Note write failed for contact $contactId", e)
        }
    }

    /** The note row's id and text, if the contact has a note. */
    private fun findNote(resolver: ContentResolver, contactId: String): Pair<String, String>? {
        val projection = arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Note.NOTE)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
        resolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) to (cursor.getString(1) ?: "")
            }
        }
        return null
    }

    private fun findRawContactId(resolver: ContentResolver, contactId: String): String? {
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
        resolver.query(ContactsContract.RawContacts.CONTENT_URI, projection, selection, arrayOf(contactId), null)
            ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        return null
    }
}

/**
 * The app's block in a contact note, as plain functions so writing and reading it can be tested
 * as a round trip.
 *
 * ~~~
 * [Registry Status: Deceased]
 * Status Since: 10/23/2026
 * Obit: https://www.echovita.com/us/obituaries/…
 * — CleanUnderwear
 * ~~~
 */
object ContactNote {
    const val SIGNATURE = "— CleanUnderwear"
    private val BLOCK = Regex("""\[Registry Status: [^\]]*][\s\S]*?(?:$SIGNATURE|(?=\n\s*\n)|$)""")

    /** The block for [target], or null when its status isn't one that belongs in a note. */
    fun block(target: Target, formatDate: (Long) -> String): String? {
        val status = when (target.status) {
            TargetStatus.MONITORING -> "Monitoring"
            TargetStatus.INCARCERATED -> "Incarcerated"
            TargetStatus.DECEASED -> "Deceased"
            TargetStatus.IGNORED -> "Archived"
            else -> return null
        }
        return buildString {
            appendLine("[Registry Status: $status]")
            if (target.lastStatusChangeTimestamp > 0) appendLine("Status Since: ${formatDate(target.lastStatusChangeTimestamp)}")
            if (target.status == TargetStatus.INCARCERATED) target.lockupUrl?.let { appendLine("Records: $it") }
            if (target.status == TargetStatus.DECEASED) target.obituaryUrl?.let { appendLine("Obit: $it") }
            append(SIGNATURE)
        }
    }

    /** [note] with the app's block replaced by [block], or [block] added after the user's text. */
    fun merge(note: String?, block: String): String {
        val text = note.orEmpty()
        if (BLOCK.containsMatchIn(text)) return BLOCK.replaceFirst(text, Regex.escapeReplacement(block))
        return if (text.isBlank()) block else text.trimEnd() + "\n\n" + block
    }

    /** [note] without the app's block: what the user wrote. */
    fun strip(note: String): String = BLOCK.replaceFirst(note, "").trim()

    /** Whether [note] holds a block this app wrote (signed), as opposed to text that only looks like one. */
    fun isSigned(note: String): Boolean = BLOCK.find(note)?.value?.trimEnd()?.endsWith(SIGNATURE) == true
}
