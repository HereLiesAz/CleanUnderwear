package com.hereliesaz.cleanunderwear.domain

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One person a details lookup turned up for a contact, as the user sees it when choosing.
 *
 * @param foundBy the searches that returned this person ("phone", "name").
 * @param fits what about them matches the contact ("Same phone number").
 * @param differs what doesn't ("A different age").
 */
data class LookupOption(
    val name: String?,
    val middleName: String?,
    val dob: String?,
    val address: String?,
    val phone: String?,
    val foundBy: List<String>,
    val fits: List<String>,
    val differs: List<String>
)

/** Everyone a lookup found for one contact, waiting for the user to say which, if any, is them. */
data class LookupChoice(
    val targetId: Int,
    val contactName: String,
    val options: List<LookupOption>
)

/**
 * The choices a run of "Fill in details" leaves for the user. A run looks everyone up first and
 * asks afterwards, so nobody waits on a question mid-run; what it found is written here as it
 * goes, and survives the app closing until the user answers. Kept out of backups (no-backup
 * storage): it is people-search data about the user's contacts, and stale on another phone.
 */
@Singleton
class LookupChoiceStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.noBackupFilesDir, "lookup_choices.json")
    private val gson = Gson()
    private val type = object : TypeToken<List<LookupChoice>>() {}.type

    @Synchronized fun load(): List<LookupChoice> = try {
        if (file.exists()) gson.fromJson<List<LookupChoice>>(file.readText(), type).orEmpty() else emptyList()
    } catch (e: Exception) {
        DiagnosticLogger.log("Lookup choices unreadable; starting over: ${e.javaClass.simpleName}", DiagnosticLogger.LogEntry.LogLevel.WARN)
        emptyList()
    }

    /** Adds or replaces the contact's entry; returns the whole list. */
    @Synchronized fun put(choice: LookupChoice): List<LookupChoice> =
        (load().filter { it.targetId != choice.targetId } + choice).also(::save)

    /** Drops the contacts' entries; returns what is left. */
    @Synchronized fun remove(targetIds: Set<Int>): List<LookupChoice> =
        load().filter { it.targetId !in targetIds }.also(::save)

    @Synchronized fun clear() { file.delete() }

    private fun save(choices: List<LookupChoice>) {
        if (choices.isEmpty()) { file.delete(); return }
        // Written whole and swapped in, so a crash mid-write can't leave half a list.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(gson.toJson(choices, type))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
