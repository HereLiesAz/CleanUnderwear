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
 * @param deceased the site lists this person as deceased: shown plainly, never acted on by itself.
 */
data class LookupOption(
    val name: String?,
    val middleName: String?,
    val dob: String?,
    val address: String?,
    val phone: String?,
    val foundBy: List<String>,
    val fits: List<String>,
    val differs: List<String>,
    /** The page the person was found on lists them as deceased. Saved choices from before have it false. */
    val deceased: Boolean = false,
    /** The address of the person's own page, whose details follow. */
    val detailUrl: String? = null,
    // What the person's own page added. Null, not empty, when it wasn't read, and for saved
    // choices from before (which are read back without their defaults).
    val phones: List<String>? = null,
    val emails: List<String>? = null,
    val otherAddresses: List<String>? = null,
    val relatives: List<String>? = null
)

/**
 * A person the searches found, as the results list showed them: held, not yet put to the user
 * or opened, until the ones before them have been ruled out.
 */
data class PooledPerson(
    val name: String?,
    val middleName: String?,
    val dob: String?,
    val address: String?,
    val phone: String?,
    val deceased: Boolean,
    val detailUrl: String?,
    val foundBy: List<String>
)

/** A page of a search's results not read yet: [url], and the search it belongs to ("phone", "name"). */
data class MorePage(val url: String, val by: String)

/**
 * Everyone a lookup found for one contact, waiting for the user to say which, if any, is them.
 *
 * [options] is the batch in front of the user. The rest of what the searches found is held until
 * the user names the right person, so that "none of these" can go on to the next batch without
 * searching again and without offering anyone twice: [pool] (found, ranked, not yet shown),
 * [seen] (a key for everyone found so far, shown or pooled) and [morePages] (later pages of the
 * searches' result lists, opened only when the pool runs short). These are null for choices
 * saved before they existed, which are read back without their defaults.
 */
data class LookupChoice(
    val targetId: Int,
    val contactName: String,
    val options: List<LookupOption>,
    val pool: List<PooledPerson>? = null,
    val seen: List<String>? = null,
    val morePages: List<MorePage>? = null,
    /** How many batches the user has been through, this one included. */
    val rounds: Int = 1,
    /**
     * The user ruled this batch out ("none of these") and the contact waits for the next run of
     * Fill in details, which goes on to the next batch. Such a choice is not put to the user.
     */
    val wantsMore: Boolean = false
) {
    /** More people can still be shown: some are held, or more result pages could be opened. */
    val hasMore: Boolean get() = !pool.isNullOrEmpty() || !morePages.isNullOrEmpty()

    /** Everyone found so far, shown or held. */
    val foundSoFar: Int get() = maxOf(seen?.size ?: 0, options.size)
}

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
