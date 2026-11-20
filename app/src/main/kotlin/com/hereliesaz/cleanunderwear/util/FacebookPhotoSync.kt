package com.hereliesaz.cleanunderwear.util

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-confirmed Facebook-to-Android-contact links. Never guesses a person from their name.
 * The first successful write backs up the original photo. Later writes require the photo to be
 * unchanged since our last write; a user edit takes precedence. All state remains on the device.
 */
@Singleton
class FacebookPhotoSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val repository: TargetRepository
) {
    private val prefs = context.getSharedPreferences("facebook_photo_links", Context.MODE_PRIVATE)
    private val photoDir get() = java.io.File(context.filesDir, "facebook_photo_backups").also { it.mkdirs() }

    data class Link(
        val targetId: Int,
        val profile: String,
        val lookupKey: String,
        val contactName: String,
        val lastWrittenHash: String
    )

    sealed class Outcome {
        data class Updated(val contactName: String) : Outcome()
        data class Unchanged(val contactName: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    fun linkedIds(): Set<Int> = prefs.all.keys.filter { it.startsWith("target_") }
        .mapNotNull { it.removePrefix("target_").toIntOrNull() }.toSet()

    fun linked(targetId: Int): Link? = prefs.getString("target_$targetId", null)?.let {
        try {
            val obj = JSONObject(it)
            Link(
                targetId,
                obj.getString("profile"),
                obj.getString("lookup"),
                obj.getString("name"),
                obj.getString("hash")
            )
        } catch (_: Exception) { null }
    }

    private fun save(link: Link) {
        prefs.edit().putString("target_${link.targetId}", JSONObject()
            .put("profile", link.profile)
            .put("lookup", link.lookupKey)
            .put("name", link.contactName)
            .put("hash", link.lastWrittenHash)
            .toString()).apply()
    }

    /** Available for explicit UI linking only. */
    suspend fun link(target: Target, contactUri: Uri): Outcome = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext Outcome.Failed("Contacts permission is required")
        val profile = target.facebookProfileUrl ?: return@withContext Outcome.Failed("No Facebook profile")
        if (repository.getTargetById(target.id)?.facebookProfileUrl != profile) return@withContext Outcome.Failed("Facebook profile changed")
        val url = target.facebookPhotoUrl ?: return@withContext Outcome.Failed("No Facebook photo was available in the import")
        if (!approved(url)) return@withContext Outcome.Failed("Untrusted Facebook photo URL")
        val picked = readPickedContact(contactUri) ?: return@withContext Outcome.Failed("Could not read the selected phone contact")
        if (linked(target.id) != null) return@withContext Outcome.Failed("Restore the existing photo before linking again")
        if (prefs.all.values.any { raw ->
                (raw as? String)?.let { value ->
                    try { JSONObject(value).getString("lookup") == picked.second } catch (_: Exception) { false }
                } == true
            }) return@withContext Outcome.Failed("That phone contact already has a Facebook photo link")

        val downloaded = download(url) ?: return@withContext Outcome.Failed("Facebook did not provide a usable photo")
        val row = findPhotoRow(picked.first)
        val original = row?.second
        val backup = java.io.File(photoDir, "target_${target.id}.backup")
        if (backup.exists()) return@withContext Outcome.Failed("Existing backup must be restored first")
        if (original != null) backup.writeBytes(original) else backup.writeBytes(ByteArray(0))
        try {
            writePhoto(picked.first, row?.first, downloaded)
            val written = findPhotoRow(picked.first)?.second
                ?: throw IllegalStateException("Contacts did not retain the new image")
            save(Link(target.id, profile, picked.second, picked.third, digest(written)))
            Outcome.Updated(picked.third)
        } catch (e: Exception) {
            // If no change occurred, the backup is safe to discard and link can be retried.
            val after = findPhotoRow(picked.first)?.second
            if (after?.contentEquals(original ?: ByteArray(0)) == true || (after == null && original == null)) backup.delete()
            Outcome.Failed("Contacts could not save the photo: ${e.javaClass.simpleName}")
        }
    }

    /** WorkManager and the manual button use the same external-edit-safe refresh. */
    suspend fun refresh(targetId: Int): Outcome = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext Outcome.Failed("Contacts permission is required")
        val link = linked(targetId) ?: return@withContext Outcome.Failed("No linked contact")
        val target = repository.getTargetById(targetId)
            ?: return@withContext Outcome.Failed("The Facebook import is no longer present")
        if (target.facebookProfileUrl != link.profile) return@withContext Outcome.Failed("Facebook profile changed; relink required")
        val url = target.facebookPhotoUrl ?: return@withContext Outcome.Failed("No current Facebook image URL")
        val contactId = resolveContact(link.lookupKey) ?: return@withContext Outcome.Failed("Linked phone contact no longer exists")
        val row = findPhotoRow(contactId)
        if (row == null || digest(row.second) != link.lastWrittenHash) {
            return@withContext Outcome.Failed("Phone photo was changed elsewhere; automatic updates stopped")
        }
        val downloaded = download(url) ?: return@withContext Outcome.Failed("Facebook image unavailable")
        val hash = digest(downloaded)
        if (hash == link.lastWrittenHash) return@withContext Outcome.Unchanged(link.contactName)
        try {
            writePhoto(contactId, row.first, downloaded)
            val written = findPhotoRow(contactId)?.second
                ?: throw IllegalStateException("Contacts did not retain the refreshed image")
            save(link.copy(lastWrittenHash = digest(written)))
            Outcome.Updated(link.contactName)
        } catch (e: Exception) {
            Outcome.Failed("Could not update the phone photo: ${e.javaClass.simpleName}")
        }
    }

    suspend fun refreshAll(): Int {
        val ids = prefs.all.keys.mapNotNull { it.removePrefix("target_").toIntOrNull() }
        var updated = 0
        for (id in ids) {
            if (refresh(id) is Outcome.Updated) updated++
        }
        return updated
    }

    suspend fun restore(targetId: Int): Outcome = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext Outcome.Failed("Contacts permission is required")
        val link = linked(targetId) ?: return@withContext Outcome.Failed("No linked contact")
        val contactId = resolveContact(link.lookupKey) ?: return@withContext Outcome.Failed("Linked contact no longer exists")
        val row = findPhotoRow(contactId)
        if (row == null || digest(row.second) != link.lastWrittenHash) {
            return@withContext Outcome.Failed("Phone photo was edited elsewhere; cannot safely restore")
        }
        val backup = java.io.File(photoDir, "target_${targetId}.backup")
        if (!backup.exists()) return@withContext Outcome.Failed("Original photo backup is missing")
        try {
            val original = backup.readBytes()
            if (original.isEmpty()) {
                val id = row.first
                context.contentResolver.delete(ContactsContract.Data.CONTENT_URI, "${ContactsContract.Data._ID} = ?", arrayOf(id.toString()))
            } else {
                writePhoto(contactId, row.first, original)
            }
            prefs.edit().remove("target_$targetId").apply()
            backup.delete()
            Outcome.Updated(link.contactName)
        } catch (e: Exception) {
            Outcome.Failed("Could not restore the original photo: ${e.javaClass.simpleName}")
        }
    }

    /** Stop future refreshes without overwriting a photo the user has edited manually. */
    fun unlink(targetId: Int): Boolean {
        if (linked(targetId) == null) return false
        prefs.edit().remove("target_$targetId").apply()
        java.io.File(photoDir, "target_${targetId}.backup").delete()
        return true
    }

    /** Called when the user explicitly deletes the app's entire private registry. */
    fun clearRecords() {
        prefs.edit().clear().apply()
        photoDir.listFiles()?.forEach { it.delete() }
    }

    private fun canWrite() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun readPickedContact(uri: Uri): Triple<Long, String, String>? {
        val resolved = ContactsContract.Contacts.lookupContact(context.contentResolver, uri) ?: uri
        context.contentResolver.query(
            resolved,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) return Triple(c.getLong(0), c.getString(1) ?: return null, c.getString(2) ?: "")
        }
        return null
    }

    private fun resolveContact(lookup: String): Long? {
        // A lookup URI survives aggregation and contact ID changes.
        val uri = ContactsContract.Contacts.getLookupUri(0, lookup)
        val found = ContactsContract.Contacts.lookupContact(context.contentResolver, uri) ?: return null
        return ContentUris.parseId(found)
    }

    private fun findPhotoRow(contactId: Long): Pair<Long, ByteArray>? {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Photo.PHOTO),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0) to (c.getBlob(1) ?: ByteArray(0))
        }
        return null
    }

    private fun writePhoto(contactId: Long, existingId: Long?, bytes: ByteArray) {
        val resolver = context.contentResolver
        val operation = if (existingId != null) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existingId.toString()))
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes).build()
        } else {
            val rawId = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(contactId.toString()), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                ?: throw IllegalStateException("No writable raw contact")
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .build()
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
    }

    private fun approved(url: String): Boolean = try {
        val u = Uri.parse(url)
        val h = u.host?.lowercase().orEmpty()
        u.scheme == "https" && u.port == -1 &&
            (h == "fbcdn.net" || h.endsWith(".fbcdn.net") ||
                h == "facebook.com" || h.endsWith(".facebook.com"))
    } catch (_: Exception) { false }

    private fun download(url: String): ByteArray? {
        if (!approved(url)) return null
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful || !approved(response.request.url.toString()) ||
                    response.header("Content-Type").orEmpty().substringBefore(';').lowercase() !in
                    setOf("image/jpeg", "image/png", "image/webp")) return null
                val body = response.body ?: return null
                val input = body.byteStream()
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (bytes.size() + n > 2 * 1024 * 1024) return null
                    bytes.write(buffer, 0, n)
                }
                val source = bytes.toByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
                if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512)
                }
                val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
                val scaled = if (bitmap.width > 512 || bitmap.height > 512) {
                    val scale = 512f / maxOf(bitmap.width, bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else bitmap
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    output.toByteArray()
                }
            }
        } catch (_: Exception) { null }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
