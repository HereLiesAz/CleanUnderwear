package com.hereliesaz.cleanunderwear.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One identity the dedup pass folded into another. Dedup used to delete the absorbed row without a
 * trace, so a wrong merge could never be noticed, let alone undone. The ledger keeps everything
 * needed to review the merge (who, on what evidence) and to reverse it (the absorbed row and the
 * survivor fields it overwrote).
 *
 * Merges made before the ledger existed are not recorded and cannot be recovered.
 */
@Entity(tableName = "merge_ledger", indices = [Index(value = ["survivor_id"])])
data class MergeRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** The row that kept living. */
    @ColumnInfo(name = "survivor_id")
    val survivorId: Int,
    /** Full [Target] snapshot of the absorbed row, as JSON ([TargetSnapshot]). */
    @ColumnInfo(name = "absorbed_json")
    val absorbedJson: String,
    /** Survivor fields this merge changed, as `{field: {before, after}}` ([TargetSnapshot.changes]). */
    @ColumnInfo(name = "changes_json")
    val changesJson: String,
    // Denormalised so the review list never has to parse (or load) the snapshot.
    @ColumnInfo(name = "absorbed_name")
    val absorbedName: String,
    @ColumnInfo(name = "absorbed_phone")
    val absorbedPhone: String?,
    @ColumnInfo(name = "absorbed_email")
    val absorbedEmail: String?,
    /** Scorer verdict at merge time: P(same person), match weight, and the best case it could reach. */
    @ColumnInfo(name = "probability")
    val probability: Double,
    @ColumnInfo(name = "weight_bits")
    val weightBits: Double,
    @ColumnInfo(name = "best_case_bits")
    val bestCaseBits: Double,
    /** Per-field audit lines, `field|LEVEL|bits`, newline-separated ([ContributionCodec]). */
    @ColumnInfo(name = "contributions")
    val contributions: String,
    @ColumnInfo(name = "origin")
    val origin: String,
    @ColumnInfo(name = "merged_at")
    val mergedAt: Long,
    @ColumnInfo(name = "state")
    val state: MergeState = MergeState.ACTIVE,
    /**
     * Set when the merge is split: signatures ([MergeSignature]) of the survivor and the restored
     * row. Dedup refuses to fuse rows carrying this pair of signatures again, otherwise the next
     * sweep would simply re-merge what the user just separated.
     */
    @ColumnInfo(name = "veto_a")
    val vetoA: String? = null,
    @ColumnInfo(name = "veto_b")
    val vetoB: String? = null
)

enum class MergeState {
    /** Merged and not yet judged. Eligible for review. */
    ACTIVE,
    /** The user split it; the absorbed row was restored. */
    SPLIT,
    /** The user confirmed the merge was right. */
    KEPT
}

/** Review-list projection of [MergeRecord]: everything but the snapshots. */
data class MergeSummary(
    @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "survivor_id") val survivorId: Int,
    @ColumnInfo(name = "absorbed_name") val absorbedName: String,
    @ColumnInfo(name = "absorbed_phone") val absorbedPhone: String?,
    @ColumnInfo(name = "absorbed_email") val absorbedEmail: String?,
    @ColumnInfo(name = "probability") val probability: Double,
    @ColumnInfo(name = "weight_bits") val weightBits: Double,
    @ColumnInfo(name = "best_case_bits") val bestCaseBits: Double,
    @ColumnInfo(name = "contributions") val contributions: String,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "merged_at") val mergedAt: Long
)

/** A signature pair of a split merge, see [MergeRecord.vetoA]. */
data class MergeVeto(
    @ColumnInfo(name = "a") val a: String,
    @ColumnInfo(name = "b") val b: String
)

/**
 * A review decision on a case that has no ledger row of its own (stale numbers). The key embeds the
 * number it was made about, so a different number on the same contact is reviewed afresh.
 */
@Entity(tableName = "suspect_decisions")
data class SuspectDecision(
    @PrimaryKey
    @ColumnInfo(name = "suspect_key")
    val key: String,
    @ColumnInfo(name = "decision")
    val decision: String,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long
)
