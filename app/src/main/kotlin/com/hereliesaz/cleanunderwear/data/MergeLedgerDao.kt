package com.hereliesaz.cleanunderwear.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MergeLedgerDao {

    @Insert
    suspend fun insert(record: MergeRecord): Long

    /** Unjudged merges, newest first. Snapshots stay out of the cursor window. */
    @Query(
        "SELECT id, survivor_id, absorbed_name, absorbed_phone, absorbed_email, probability, " +
            "weight_bits, best_case_bits, contributions, origin, merged_at " +
            "FROM merge_ledger WHERE state = 'ACTIVE' ORDER BY merged_at DESC"
    )
    suspend fun getActiveSummaries(): List<MergeSummary>

    @Query("SELECT * FROM merge_ledger WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): MergeRecord?

    @Query("UPDATE merge_ledger SET survivor_id = :newId WHERE survivor_id = :oldId")
    suspend fun reassignSurvivor(oldId: Int, newId: Int)

    @Query("UPDATE merge_ledger SET state = :state WHERE id = :id")
    suspend fun setState(id: Int, state: MergeState)

    @Query("UPDATE merge_ledger SET state = 'SPLIT', veto_a = :vetoA, veto_b = :vetoB WHERE id = :id")
    suspend fun markSplit(id: Int, vetoA: String, vetoB: String)

    @Query(
        "SELECT veto_a AS a, veto_b AS b FROM merge_ledger " +
            "WHERE state = 'SPLIT' AND veto_a IS NOT NULL AND veto_b IS NOT NULL"
    )
    suspend fun getVetoes(): List<MergeVeto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecision(decision: SuspectDecision)

    @Query("SELECT suspect_key FROM suspect_decisions")
    suspend fun getDecidedKeys(): List<String>
}
