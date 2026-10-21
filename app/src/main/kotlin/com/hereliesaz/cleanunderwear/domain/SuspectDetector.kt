package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.HistoryStats
import com.hereliesaz.cleanunderwear.match.FieldContribution
import com.hereliesaz.cleanunderwear.match.MatchRecord
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SuspicionKind { BAD_MERGE, STALE_NUMBER }

/** Which way the evidence on a case tips. */
enum class Lean { LIKELY_PROBLEM, TOSS_UP, LIKELY_FINE }

/** One reason, in plain words, with how much it counts (roughly 1 = hint, 3+ = strong). */
data class Evidence(val text: String, val weight: Double)

/** One side of the comparison shown on a case: an entry the contact is (or was) made of. */
data class IdentityLine(
    val label: String,
    val name: String?,
    val phone: String?,
    val email: String?,
    val history: String?
)

/**
 * A contact the app suspects is wrong, with the case for and against. The review screen shows both
 * lists; [suspicion] is why it looks bad, [defense] is why it might be perfectly fine.
 */
data class SuspectCase(
    /** Stable id, also the key under which a decision is remembered. */
    val key: String,
    val kind: SuspicionKind,
    val targetId: Int,
    /** The merge to undo, for [SuspicionKind.BAD_MERGE]; null otherwise. */
    val ledgerId: Int?,
    val title: String,
    val headline: String,
    val identities: List<IdentityLine>,
    val suspicion: List<Evidence>,
    val defense: List<Evidence>,
    /** A number that looks like the live replacement for a stale one, if the merge ledger knows one. */
    val replacementPhone: String? = null
) {
    val suspicionScore: Double get() = suspicion.sumOf { it.weight }
    val defenseScore: Double get() = defense.sumOf { it.weight }
    val net: Double get() = suspicionScore - defenseScore
    val lean: Lean
        get() = when {
            net >= SuspectDetector.LEAN_MARGIN -> Lean.LIKELY_PROBLEM
            net <= -SuspectDetector.LEAN_MARGIN -> Lean.LIKELY_FINE
            else -> Lean.TOSS_UP
        }
}

/** Everything the detector needs to judge one merge. */
data class MergeCaseInput(
    val ledgerId: Int,
    val survivorId: Int,
    val survivorName: String,
    val survivorPhone: String?,
    val survivorEmail: String?,
    val absorbedName: String,
    val absorbedPhone: String?,
    val absorbedEmail: String?,
    val probability: Double,
    val contributions: List<FieldContribution>,
    val mergedAtMillis: Long,
    val survivorHistory: HistoryStats?,
    val absorbedHistory: HistoryStats?,
    /** False when call/SMS history cannot be read; history signals are then skipped, not guessed. */
    val historyAvailable: Boolean,
    /** How many other contacts carry the survivor's number. */
    val survivorPhoneSharers: Int,
    /** Messaging apps each number is registered with right now ([com.hereliesaz.cleanunderwear.data.MessagingPresence]). */
    val survivorApps: Set<String> = emptySet(),
    val absorbedApps: Set<String> = emptySet(),
    /** False when no messaging app on the device lists any contact; presence is then skipped. */
    val presenceAvailable: Boolean = false
)

/** Another contact sharing a number under review. */
data class NumberSharer(val name: String)

/** A number merged into this contact that seems to have replaced the one on file. */
data class ReplacementCandidate(
    val phone: String,
    val name: String,
    val history: HistoryStats?,
    val apps: Set<String> = emptySet()
)

/** Everything the detector needs to judge whether one contact's number has gone stale. */
data class StaleCaseInput(
    val targetId: Int,
    val name: String,
    val phone: String,
    val email: String?,
    val lookupFailed: Boolean,
    val history: HistoryStats?,
    val historyAvailable: Boolean,
    /** How far back the device history reaches; null when unknown or unavailable. */
    val historyCoverageMillis: Long?,
    val sharers: List<NumberSharer>,
    val replacement: ReplacementCandidate?,
    /** Messaging apps this number is registered with right now. */
    val apps: Set<String> = emptySet(),
    /** Messaging apps that list any contacts on this device; empty skips the presence signal. */
    val appsOnDevice: Set<String> = emptySet(),
    /** Name a people-search lookup on this number returned when it wasn't this contact. */
    val otherHolder: String? = null
)

/**
 * Judges merges and phone numbers. Pure: no I/O, so every rule can be tested and tuned in
 * isolation. Each rule adds a reason to the suspicion or the defense list; a case surfaces only
 * when the suspicion alone is substantial, and the defense is shown beside it so the user weighs
 * both rather than trusting a score.
 *
 * Call/SMS history is the strongest signal either way. Two numbers on one contact that were both
 * live in the same stretch of time argue for two people. Numbers whose traffic is strictly
 * sequential argue for one person who changed numbers, and the older one is then stale. A number
 * nobody has called or texted in years is stale on its own.
 */
object SuspectDetector {
    const val LEAN_MARGIN = 1.5
    const val MIN_SUSPICION = 2.0

    private const val DAY_MS = 86_400_000L
    private const val MONTH_MS = 30L * DAY_MS
    private const val THIN_MARGIN_PROBABILITY = 0.99
    private const val MIN_OVERLAP_MS = 30L * DAY_MS
    private const val MIN_EVENTS_FOR_OVERLAP = 3
    private const val RECENT_MS = 90L * DAY_MS
    private const val STALE_MS = 18L * MONTH_MS
    private const val VERY_STALE_MS = 36L * MONTH_MS
    private const val SPARSE_EVENTS = 3
    private const val MIN_COVERAGE_MS = 24L * MONTH_MS
    private const val SURNAME_DIFFERS_BELOW = 0.85

    private val monthYear: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM yyyy", Locale.US).withZone(ZoneOffset.UTC)

    fun mergeKey(ledgerId: Int) = "merge:$ledgerId"

    fun staleKey(targetId: Int, phone: String) =
        "stale:$targetId:${StringSimilarity.normalizePhone(phone) ?: phone}"

    // ---------------------------------------------------------------------------------------
    // Bad merges
    // ---------------------------------------------------------------------------------------

    fun assessMerge(i: MergeCaseInput, now: Long): SuspectCase? {
        val suspicion = mutableListOf<Evidence>()
        val defense = mutableListOf<Evidence>()

        val survivorNumber = StringSimilarity.normalizePhone(i.survivorPhone)
        val absorbedNumber = StringSimilarity.normalizePhone(i.absorbedPhone)
        val differentNumbers = survivorNumber != null && absorbedNumber != null && survivorNumber != absorbedNumber
        val sameNumber = survivorNumber != null && survivorNumber == absorbedNumber
        fun level(field: String) = i.contributions.firstOrNull { it.field == field }?.level

        if (differentNumbers) {
            suspicion += Evidence(
                "Two different numbers ended up on one contact: ${i.survivorPhone} and ${i.absorbedPhone}.", 2.0
            )
        }

        when (level("name")) {
            "CONFLICT" -> suspicion += Evidence(
                "The surnames disagreed (\"${i.survivorName}\" vs \"${i.absorbedName}\"); other fields outvoted them.", 3.0
            )
            "PARTIAL" -> suspicion += Evidence(
                "The names matched only partly (an initial or a first name).", 1.0
            )
            "AGREE" -> defense += Evidence(
                "The names agree, allowing for nicknames (\"${i.survivorName}\" / \"${i.absorbedName}\").", 2.0
            )
        }
        if (sameNumber) defense += Evidence("Both entries had the same phone number.", 1.5)
        if (level("email") == "AGREE") defense += Evidence("Both entries had the same email address.", 2.0)
        when (level("address")) {
            "AGREE" -> defense += Evidence("The addresses agree.", 2.0)
            "PARTIAL" -> defense += Evidence("The addresses partly agree (same street or same ZIP).", 1.0)
            "CONFLICT" -> suspicion += Evidence("The addresses point to different places.", 1.0)
        }
        when (level("dob")) {
            "AGREE" -> defense += Evidence("The dates of birth agree.", 4.0)
            "DISAGREE" -> suspicion += Evidence("The dates of birth disagree.", 4.0)
        }

        if (i.probability < THIN_MARGIN_PROBABILITY) {
            suspicion += Evidence(
                "It cleared the merge bar narrowly (${percent(i.probability)} confidence).", 1.0
            )
        }
        val agreeing = i.contributions.filter { it.level == "AGREE" && it.field != "areaCode" }
        if (agreeing.size == 1) {
            suspicion += Evidence("Only the ${agreeing.single().field} agreed; nothing else backed the merge.", 1.5)
        }
        if (i.survivorPhoneSharers > 0) {
            suspicion += Evidence(
                "${i.survivorPhoneSharers} other contact${plural(i.survivorPhoneSharers)} share" +
                    "${if (i.survivorPhoneSharers == 1) "s" else ""} the number ${i.survivorPhone}; shared lines are how strangers get merged.",
                1.0
            )
        }

        addHistoryEvidence(i, differentNumbers, now, suspicion, defense)
        addPresenceEvidence(i, differentNumbers, suspicion, defense)

        if (suspicion.sumOf { it.weight } < MIN_SUSPICION) return null

        return SuspectCase(
            key = mergeKey(i.ledgerId),
            kind = SuspicionKind.BAD_MERGE,
            targetId = i.survivorId,
            ledgerId = i.ledgerId,
            title = i.survivorName,
            headline = "\"${i.absorbedName}\" was merged into this contact on ${day(i.mergedAtMillis)}.",
            identities = listOf(
                IdentityLine(
                    "Kept", i.survivorName, i.survivorPhone, i.survivorEmail,
                    status(describe(i.survivorHistory, i.historyAvailable), i.survivorApps, i.presenceAvailable)
                ),
                IdentityLine(
                    "Absorbed", i.absorbedName, i.absorbedPhone, i.absorbedEmail,
                    status(describe(i.absorbedHistory, i.historyAvailable), i.absorbedApps, i.presenceAvailable)
                )
            ),
            suspicion = suspicion.sortedByDescending { it.weight },
            defense = defense.sortedByDescending { it.weight }
        )
    }

    /**
     * Messaging-app registration stands in for call history: it says which lines are live now,
     * though not since when. Two live numbers argue for two people; one live, one not, for a
     * number change.
     */
    private fun addPresenceEvidence(
        i: MergeCaseInput,
        differentNumbers: Boolean,
        suspicion: MutableList<Evidence>,
        defense: MutableList<Evidence>
    ) {
        if (!differentNumbers || !i.presenceAvailable) return
        val kept = i.survivorApps
        val absorbed = i.absorbedApps
        when {
            kept.isNotEmpty() && absorbed.isNotEmpty() -> {
                suspicion += Evidence(
                    "Both numbers are registered on messaging apps right now (${i.survivorPhone}: ${kept.joinToString()}; " +
                        "${i.absorbedPhone}: ${absorbed.joinToString()}), so both lines are live: two people, or one person with two phones.",
                    2.0
                )
                defense += Evidence("Plenty of people keep a work phone and a personal one.", 1.0)
            }
            kept.isNotEmpty() || absorbed.isNotEmpty() -> {
                val live = if (kept.isNotEmpty()) i.survivorPhone else i.absorbedPhone
                val dead = if (kept.isNotEmpty()) i.absorbedPhone else i.survivorPhone
                defense += Evidence(
                    "Only $live is on a messaging app; $dead isn't, which fits an old number retired for a new one.",
                    1.5
                )
            }
        }
    }

    private fun addHistoryEvidence(
        i: MergeCaseInput,
        differentNumbers: Boolean,
        now: Long,
        suspicion: MutableList<Evidence>,
        defense: MutableList<Evidence>
    ) {
        if (!differentNumbers || !i.historyAvailable) return
        val kept = i.survivorHistory
        val absorbed = i.absorbedHistory

        if (kept != null && absorbed != null) {
            val overlap = overlapMillis(kept, absorbed)
            val busyEnough = kept.count >= MIN_EVENTS_FOR_OVERLAP && absorbed.count >= MIN_EVENTS_FOR_OVERLAP
            if (overlap >= MIN_OVERLAP_MS && busyEnough) {
                suspicion += Evidence(
                    "You were in touch with both numbers over the same ${months(overlap)} months " +
                        "(${span(kept)} and ${span(absorbed)}). One person rarely runs two numbers at once.",
                    3.0
                )
                if (now - kept.lastMillis <= RECENT_MS && now - absorbed.lastMillis <= RECENT_MS) {
                    suspicion += Evidence("Both numbers were active in the last 90 days.", 1.0)
                }
                defense += Evidence(
                    "People do carry two numbers (work and personal, a second SIM), so overlap alone doesn't prove two people.",
                    1.0
                )
            } else {
                val keptQuiet = kept.lastMillis <= absorbed.lastMillis
                val quiet = if (keptQuiet) kept else absorbed
                val quietNumber = if (keptQuiet) i.survivorPhone else i.absorbedPhone
                val liveNumber = if (keptQuiet) i.absorbedPhone else i.survivorPhone
                val gap = (if (keptQuiet) absorbed.firstMillis else kept.firstMillis) - quiet.lastMillis
                defense += Evidence(
                    "The histories don't overlap: $quietNumber went quiet in ${monthYear.format(Instant.ofEpochMilli(quiet.lastMillis))}" +
                        (if (gap > 0) ", ${months(gap)} months before $liveNumber took over" else ", as $liveNumber took over") +
                        ". That fits one person changing numbers, and $quietNumber is likely the stale one.",
                    2.5
                )
            }
        } else if (kept != null || absorbed != null) {
            val live = if (kept != null) i.survivorPhone else i.absorbedPhone
            val silent = if (kept != null) i.absorbedPhone else i.survivorPhone
            defense += Evidence(
                "Only $live has any call or text history. $silent looks like a spare or retired number, not a second person.",
                1.0
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Stale numbers
    // ---------------------------------------------------------------------------------------

    fun assessStale(i: StaleCaseInput, now: Long): SuspectCase? {
        val suspicion = mutableListOf<Evidence>()
        val defense = mutableListOf<Evidence>()

        if (i.historyAvailable) {
            val history = i.history
            var silent = false
            if (history == null) {
                silent = true
                suspicion += Evidence("No call or text with ${i.phone} appears in your history.", 2.0)
            } else {
                val quietFor = now - history.lastMillis
                when {
                    quietFor >= VERY_STALE_MS -> {
                        silent = true
                        suspicion += Evidence(
                            "Last call or text was ${monthYear.format(Instant.ofEpochMilli(history.lastMillis))}, over three years ago.", 3.0
                        )
                    }
                    quietFor >= STALE_MS -> {
                        silent = true
                        suspicion += Evidence(
                            "Last call or text was ${monthYear.format(Instant.ofEpochMilli(history.lastMillis))}, ${months(quietFor)} months ago.", 2.0
                        )
                    }
                    else -> defense += Evidence(
                        "Last call or text was ${monthYear.format(Instant.ofEpochMilli(history.lastMillis))}; the number is in use.", 4.0
                    )
                }
                if (silent && history.count < SPARSE_EVENTS) {
                    defense += Evidence(
                        "Only ${history.count} call or text on record. Occasional contacts look stale even when current.", 1.0
                    )
                }
            }
            val coverage = i.historyCoverageMillis
            if (silent && coverage != null && coverage < MIN_COVERAGE_MS) {
                defense += Evidence(
                    "Your call and text history only reaches back ${months(coverage)} months, so silence proves little.", 1.5
                )
            }
        }

        val different = i.sharers.filter { surnameDiffers(i.name, it.name) }
        val same = i.sharers.filter { surnameSame(i.name, it.name) }
        if (different.isNotEmpty()) {
            suspicion += Evidence(
                "${i.phone} is also on ${list(different)} with a different surname. Either the number was recycled or one entry is out of date.",
                2.5
            )
        }
        if (same.isNotEmpty()) {
            defense += Evidence("Shared with ${list(same)}, same surname: probably a household line.", 1.5)
        }

        if (i.appsOnDevice.isNotEmpty()) {
            if (i.apps.isNotEmpty()) {
                defense += Evidence(
                    "${i.phone} is registered on ${i.apps.joinToString()} right now, so the line is in service. " +
                        "That alone doesn't prove it's still theirs.",
                    2.5
                )
            } else {
                suspicion += Evidence(
                    "${i.phone} isn't registered on ${i.appsOnDevice.joinToString(" or ")}. Many people use none of them, so this is a weak sign.",
                    1.0
                )
            }
        }
        i.otherHolder?.let {
            suspicion += Evidence(
                "A people-search lookup on this number came back to \"$it\", not this contact: it has likely been reassigned.",
                3.0
            )
        }

        if (i.lookupFailed) suspicion += Evidence("A people-search lookup found no one at this number.", 1.0)
        if (!i.email.isNullOrBlank()) {
            defense += Evidence("There's an email address too, so reaching them doesn't hinge on this number.", 1.0)
        }

        i.replacement?.let { r ->
            val history = r.history
            suspicion += if (history != null) {
                Evidence(
                    "${r.phone} (merged in from \"${r.name}\") has been in contact since " +
                        "${monthYear.format(Instant.ofEpochMilli(history.firstMillis))}, most recently " +
                        "${monthYear.format(Instant.ofEpochMilli(history.lastMillis))}. It looks like the live line.",
                    3.0
                )
            } else {
                Evidence(
                    "${r.phone} (merged in from \"${r.name}\") is registered on ${r.apps.joinToString()} right now " +
                        "and the number on file isn't. It looks like the live line.",
                    2.5
                )
            }
        }

        if (suspicion.sumOf { it.weight } < MIN_SUSPICION) return null

        return SuspectCase(
            key = staleKey(i.targetId, i.phone),
            kind = SuspicionKind.STALE_NUMBER,
            targetId = i.targetId,
            ledgerId = null,
            title = i.name,
            headline = "The number ${i.phone} may be out of date.",
            identities = listOf(
                IdentityLine(
                    "On file", i.name, i.phone, i.email,
                    status(describe(i.history, i.historyAvailable), i.apps, i.appsOnDevice.isNotEmpty())
                )
            ),
            suspicion = suspicion.sortedByDescending { it.weight },
            defense = defense.sortedByDescending { it.weight },
            replacementPhone = i.replacement?.phone
        )
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** How long the two numbers' histories ran side by side. Zero when one ended before the other began. */
    fun overlapMillis(a: HistoryStats, b: HistoryStats): Long =
        (minOf(a.lastMillis, b.lastMillis) - maxOf(a.firstMillis, b.firstMillis)).coerceAtLeast(0L)

    private fun surnameDiffers(a: String, b: String): Boolean {
        val x = MatchRecord.fromDisplayName(a).surnameKey ?: return false
        val y = MatchRecord.fromDisplayName(b).surnameKey ?: return false
        return StringSimilarity.jaroWinkler(x, y) < SURNAME_DIFFERS_BELOW
    }

    private fun surnameSame(a: String, b: String): Boolean {
        val x = MatchRecord.fromDisplayName(a).surnameKey ?: return false
        val y = MatchRecord.fromDisplayName(b).surnameKey ?: return false
        return StringSimilarity.jaroWinkler(x, y) >= SURNAME_DIFFERS_BELOW
    }

    private fun describe(stats: HistoryStats?, available: Boolean): String? = when {
        !available -> null
        stats == null -> "No calls or texts on record"
        else -> "${stats.count} call${plural(stats.count)}/text${plural(stats.count)}, " +
            "${monthYear.format(Instant.ofEpochMilli(stats.firstMillis))} to ${monthYear.format(Instant.ofEpochMilli(stats.lastMillis))}"
    }

    /** History summary plus messaging registration, for the side-by-side identity lines. */
    private fun status(history: String?, apps: Set<String>, presenceAvailable: Boolean): String? {
        val presence = when {
            !presenceAvailable -> null
            apps.isEmpty() -> "Not on a messaging app"
            else -> "On ${apps.joinToString()}"
        }
        return listOfNotNull(history, presence).joinToString(" · ").ifBlank { null }
    }

    private fun span(s: HistoryStats) =
        "${monthYear.format(Instant.ofEpochMilli(s.firstMillis))} to ${monthYear.format(Instant.ofEpochMilli(s.lastMillis))}"

    private fun list(sharers: List<NumberSharer>): String {
        val names = sharers.map { "\"${it.name}\"" }
        return if (names.size <= 2) names.joinToString(" and ") else "${names.take(2).joinToString(", ")} and ${names.size - 2} more"
    }

    private fun day(millis: Long) = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis))

    private fun months(millis: Long) = (millis / MONTH_MS).coerceAtLeast(0L)

    private fun percent(p: Double) = String.format(Locale.US, "%.1f%%", p * 100)

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
