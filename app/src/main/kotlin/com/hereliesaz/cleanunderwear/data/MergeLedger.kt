package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.match.FieldContribution
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the dedup pass needs from the ledger: somewhere to write each merge, and the list of pairs
 * the user has already ruled "different people". Kept as an interface so dedup stays testable
 * without Room.
 */
interface MergeLedger {
    suspend fun record(entry: MergeRecord)

    /** Pairs the user split, as [MergeSignature.vetoKey] values. */
    suspend fun vetoedPairs(): Set<String>

    /** A survivor was itself absorbed: its merges now belong to the row that absorbed it. */
    suspend fun reassignSurvivor(oldId: Int, newId: Int)
}

class RoomMergeLedger(private val dao: MergeLedgerDao) : MergeLedger {
    override suspend fun record(entry: MergeRecord) {
        withContext(Dispatchers.IO) { dao.insert(entry) }
    }

    override suspend fun reassignSurvivor(oldId: Int, newId: Int) {
        withContext(Dispatchers.IO) { dao.reassignSurvivor(oldId, newId) }
    }

    override suspend fun vetoedPairs(): Set<String> = withContext(Dispatchers.IO) {
        dao.getVetoes().mapTo(HashSet()) { MergeSignature.vetoKey(it.a, it.b) }
    }
}

/**
 * Identity fingerprint of a row, used to remember "these two are different people" across the row
 * churn of re-harvesting (every sweep inserts fresh rows with new ids, so ids cannot carry a
 * veto). Two rows with the same name, phone and email are the same entry for this purpose.
 *
 * Known limit: if enrichment later rewrites a row's name, phone or email, its signature changes
 * and the veto no longer recognises it.
 */
object MergeSignature {
    fun of(t: Target): String = listOf(
        t.displayName.trim().lowercase(),
        StringSimilarity.normalizePhone(t.phoneNumber).orEmpty(),
        StringSimilarity.normalizeEmail(t.email).orEmpty()
    ).joinToString("|")

    /** Order-independent key for a pair of signatures. */
    fun vetoKey(a: String, b: String): String =
        if (a <= b) "$a\u0001$b" else "$b\u0001$a"
}

/** Text form of a scorer audit trail: one `field|LEVEL|bits` line per field. */
object ContributionCodec {
    fun encode(contributions: List<FieldContribution>): String =
        contributions.joinToString("\n") { "${it.field}|${it.level}|${it.weightBits}" }

    fun decode(text: String): List<FieldContribution> =
        text.lines().mapNotNull { line ->
            val parts = line.split("|")
            val bits = parts.getOrNull(2)?.toDoubleOrNull()
            if (parts.size == 3 && bits != null) FieldContribution(parts[0], parts[1], bits) else null
        }
}
