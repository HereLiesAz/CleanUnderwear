package com.hereliesaz.cleanunderwear.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The grim ledger's interface.
 *
 * Most reads should go through the lite projection — the full Target row carries large URL +
 * verification-snippet text that, multiplied across thousands of rows, blows past Android's 2 MB
 * CursorWindow. Use the full-row queries only for bounded subsets (single id, ready+due, by
 * monitorability state).
 */
@Dao
interface TargetDao {

    // -- Lite list queries (projection — UI + dedup + triage) ----------------------------------

    @Query(
        "SELECT id, display_name, phone_number, area_code, status, email, " +
            "last_status_change_timestamp, last_scraped_timestamp, match_kind, monitorability_state " +
            "FROM targets ORDER BY display_name ASC"
    )
    fun getAllTargetsLite(): Flow<List<TargetLite>>

    @Query(
        """
        SELECT id, display_name, phone_number, area_code, status, email,
               last_status_change_timestamp, last_scraped_timestamp, match_kind, monitorability_state
        FROM targets 
        WHERE (display_name LIKE '%' || :query || '%' ESCAPE '\' OR phone_number LIKE '%' || :query || '%' ESCAPE '\' OR email LIKE '%' || :query || '%' ESCAPE '\')
        AND (:showIgnored = 1 OR status != 'IGNORED')
        AND (
            (:googleF IS NULL OR (source_account LIKE '%Google%' AND :googleF = 1) OR (IFNULL(source_account, '') NOT LIKE '%Google%' AND :googleF = 0)) AND
            (:metaF IS NULL OR (source_account LIKE '%Meta%' AND :metaF = 1) OR (IFNULL(source_account, '') NOT LIKE '%Meta%' AND :metaF = 0)) AND
            (:appleF IS NULL OR (source_account LIKE '%Apple%' AND :appleF = 1) OR (IFNULL(source_account, '') NOT LIKE '%Apple%' AND :appleF = 0)) AND
            (:deviceF IS NULL OR ((source_account IS NULL OR source_account = '' OR source_account LIKE '%Device%') AND :deviceF = 1) OR (source_account LIKE '%Google%' OR source_account LIKE '%Meta%' OR source_account LIKE '%Apple%') AND :deviceF = 0)
        )
        AND (
            (:namelessF IS NULL OR (display_name LIKE 'Unnamed Entity%' AND :namelessF = 1) OR (display_name NOT LIKE 'Unnamed Entity%' AND :namelessF = 0)) AND
            (:emailOnlyF IS NULL OR (phone_number IS NULL AND email IS NOT NULL AND :emailOnlyF = 1) OR (phone_number IS NOT NULL AND :emailOnlyF = 0)) AND
            (:hasEmailF IS NULL OR (email IS NOT NULL AND :hasEmailF = 1) OR (email IS NULL AND :hasEmailF = 0)) AND
            (:hasAddressF IS NULL OR (residence_info IS NOT NULL AND residence_info != '' AND :hasAddressF = 1) OR (IFNULL(residence_info, '') = '' AND :hasAddressF = 0))
        )
        AND (
            :pendingEnrichF IS NULL OR 
            (:pendingEnrichF = 1 AND monitorability_state != 'READY') OR 
            (:pendingEnrichF = 0 AND monitorability_state = 'READY')
        )
        ORDER BY
            CASE WHEN :sort = 'STATUS' THEN
                CASE status
                    WHEN 'POSSIBLE_MATCH' THEN 0 WHEN 'WEAK_MATCH' THEN 1
                    WHEN 'INCARCERATED' THEN 2 WHEN 'DECEASED' THEN 3
                    WHEN 'MONITORING' THEN 4 WHEN 'UNKNOWN' THEN 5
                    WHEN 'UNVERIFIED' THEN 6 ELSE 7
                END
            END ASC,
            CASE WHEN :sort = 'DATE' THEN last_scraped_timestamp END DESC,
            display_name COLLATE NOCASE ASC
    """
    )
    fun searchTargets(
        query: String,
        showIgnored: Boolean,
        googleF: Boolean?,
        metaF: Boolean?,
        appleF: Boolean?,
        deviceF: Boolean?,
        namelessF: Boolean?,
        emailOnlyF: Boolean?,
        hasEmailF: Boolean?,
        hasAddressF: Boolean?,
        pendingEnrichF: Boolean?,
        sort: String
    ): PagingSource<Int, TargetLite>

    @Query(
        "SELECT id, display_name, phone_number, area_code, status, email, " +
            "last_status_change_timestamp, last_scraped_timestamp, match_kind, monitorability_state " +
            "FROM targets ORDER BY display_name ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getTargetsPaged(limit: Int, offset: Int): List<TargetLite>

    @Query(
        "SELECT id, display_name, phone_number, email, area_code, status, residence_info, " +
            "source_account, monitorability_state, last_scraped_timestamp, stale_phone, enrichment_provenance " +
            "FROM targets ORDER BY display_name ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getTargetWorkInfoPaged(limit: Int, offset: Int): List<TargetWorkInfo>

    @Query("SELECT id, area_code, residence_info, lockup_url, obituary_url FROM targets")
    suspend fun getAllTargetSourceInfo(): List<TargetSourceInfo>

    // -- Bounded full-row queries -------------------------------------------------------------

    @Query("SELECT * FROM targets WHERE id = :id LIMIT 1")
    suspend fun getTargetById(id: Int): Target?

    @Query("SELECT * FROM targets WHERE id = :id LIMIT 1")
    fun observeTargetById(id: Int): Flow<Target?>

    @Query("SELECT * FROM targets WHERE id IN (:ids)")
    suspend fun getTargetsByIds(ids: List<Int>): List<Target>

    @Query(
        "SELECT * FROM targets " +
            "WHERE monitorability_state = :state " +
            "ORDER BY display_name ASC"
    )
    suspend fun getTargetsByMonitorability(state: MonitorabilityState): List<Target>

    /**
     * Contacts due a check. With [limit] > 0 only the first [limit] monitorable contacts (by when
     * they were added) are ever checked, the user's Contact Limit; 0 means all of them.
     */
    @Query(
        "SELECT * FROM targets " +
            "WHERE monitorability_state = 'READY' " +
            "AND status != 'IGNORED' " +
            "AND next_scheduled_check <= :now " +
            "AND (:limit <= 0 OR id IN (SELECT id FROM targets WHERE monitorability_state = 'READY' " +
            "AND status != 'IGNORED' ORDER BY id ASC LIMIT :limit)) " +
            // Longest-waiting first, so a run cut short by the OS picks up where it stopped
            // instead of re-checking the start of the alphabet every day.
            "ORDER BY next_scheduled_check ASC, id ASC"
    )
    suspend fun getReadyDueTargets(now: Long, limit: Int = 0): List<Target>

    @Query("SELECT * FROM targets WHERE status = :status ORDER BY display_name ASC")
    fun getTargetsByStatus(status: TargetStatus): Flow<List<Target>>

    // -- Targeted updates (avoid loading + rewriting full rows for tiny changes) ---------------

    @Query("UPDATE targets SET monitorability_state = :state WHERE id = :id")
    suspend fun updateMonitorabilityState(id: Int, state: MonitorabilityState)

    @Query("UPDATE targets SET monitorability_state = :state WHERE id IN (:ids)")
    suspend fun updateMonitorabilityStateBatch(ids: List<Int>, state: MonitorabilityState)

    @Query(
        "UPDATE targets SET status = :status, last_status_change_timestamp = :timestamp " +
            "WHERE id = :id"
    )
    suspend fun updateStatusOnly(id: Int, status: TargetStatus, timestamp: Long)

    /** Ignore, remembering the status it replaces (unless it was already ignored). */
    @Query(
        "UPDATE targets SET status_before_ignore = CASE WHEN status = 'IGNORED' THEN status_before_ignore ELSE status END, " +
            "status = 'IGNORED', last_status_change_timestamp = :timestamp WHERE id = :id"
    )
    suspend fun ignore(id: Int, timestamp: Long)

    /**
     * Un-ignore: back to the status it had, or UNKNOWN (checked afresh) if none was kept. A
     * confirmed DECEASED/INCARCERATED survives the round trip.
     */
    @Query(
        "UPDATE targets SET status = COALESCE(status_before_ignore, 'UNKNOWN'), status_before_ignore = NULL, " +
            "last_status_change_timestamp = :timestamp WHERE id = :id AND status = 'IGNORED'"
    )
    suspend fun unignore(id: Int, timestamp: Long)

    @Query("UPDATE targets SET lockup_url = :lockupUrl, obituary_url = :obituaryUrl WHERE id = :id")
    suspend fun updateUrls(id: Int, lockupUrl: String?, obituaryUrl: String?)

    // -- Mutations ----------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTarget(target: Target)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTargets(targets: List<Target>)

    @Update
    suspend fun updateTarget(target: Target)

    @androidx.room.Delete
    suspend fun deleteTarget(target: Target)

    @Query("DELETE FROM targets")
    suspend fun wipeSlateClean()

    // -- Legacy full-row reads -----------------------------------------------------------------
    // Avoid using this on production-sized data — it can exceed the CursorWindow. Kept for
    // the rare callers that genuinely need every column.
    @Query("SELECT * FROM targets ORDER BY display_name ASC")
    fun getAllTargets(): Flow<List<Target>>
}
