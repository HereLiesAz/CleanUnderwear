package com.hereliesaz.cleanunderwear.util

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemContactSyncer @Inject constructor(@ApplicationContext private val context: Context) {

    // Thread-safe replacement for SimpleDateFormat. syncToSystem() runs on
    // arbitrary worker threads, where shared SimpleDateFormat would race.
    private val lastCheckFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)
            .withZone(ZoneId.systemDefault())

    fun syncToSystem(target: Target) {
        try {
            val phone = target.phoneNumber ?: return
            val contactId = findContactIdByPhone(context.contentResolver, phone) ?: return

            val statusText = when (target.status) {
                TargetStatus.MONITORING -> "Monitoring"
                TargetStatus.UNVERIFIED -> "Unverified"
                TargetStatus.POSSIBLE_MATCH -> "Possible match — review"
                TargetStatus.INCARCERATED -> "Incarcerated"
                TargetStatus.DECEASED -> "Deceased"
                TargetStatus.IGNORED -> "Archived"
                TargetStatus.UNKNOWN -> "Checking..."
            }

            // Status + last-check stamp only — URLs live in the app DB, not in
            // the system contact note. Earlier versions echoed lockup_url /
            // obituary_url here, but that round-tripped search-engine URLs
            // back into the registry on the next harvest. The harvester now
            // whitelists URLs via SourceCatalog as a defense; not emitting in
            // the first place is the cleaner end of the same fix.
            val lastCheck = lastCheckFormat.format(Instant.ofEpochMilli(target.lastScrapedTimestamp))
            val note = """
                [Registry Status: $statusText]
                Last Check: $lastCheck
            """.trimIndent()

            upsertContactNote(context.contentResolver, contactId, note)
        } catch (e: Exception) {
            Log.e("ContactSyncer", "Failed to sync ${target.displayName} to system contacts", e)
        }
    }

    private fun findContactIdByPhone(resolver: ContentResolver, phone: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phone)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup._ID)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID))
            }
        }
        return null
    }

    private fun upsertContactNote(resolver: ContentResolver, contactId: String, note: String) {
        val existingNoteRowId = findNoteRowId(resolver, contactId)
        val ops = ArrayList<ContentProviderOperation>()

        if (existingNoteRowId != null) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data._ID} = ?",
                        arrayOf(existingNoteRowId)
                    )
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                    .build()
            )
        } else {
            val rawContactId = findRawContactId(resolver, contactId) ?: run {
                Log.w("ContactSyncer", "No raw contact for contact $contactId; cannot insert note")
                return
            }
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                    .build()
            )
        }

        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Log.e("ContactSyncer", "Note upsert batch failed for contact $contactId", e)
        }
    }

    private fun findNoteRowId(resolver: ContentResolver, contactId: String): String? {
        val projection = arrayOf(ContactsContract.Data._ID)
        val selection =
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
        resolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data._ID))
                }
            }
        return null
    }

    private fun findRawContactId(resolver: ContentResolver, contactId: String): String? {
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
        val args = arrayOf(contactId)
        resolver.query(ContactsContract.RawContacts.CONTENT_URI, projection, selection, args, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                    )
                }
            }
        return null
    }
}
