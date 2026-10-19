package com.hereliesaz.cleanunderwear.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The physical manifestation of your localized surveillance state.
 */
@Database(
    entities = [Target::class, MergeRecord::class, SuspectDecision::class],
    version = 14,
    exportSchema = false
)
abstract class CleanUnderwearDatabase : RoomDatabase() {
    abstract fun targetDao(): TargetDao
    abstract fun mergeLedgerDao(): MergeLedgerDao

    companion object {
        @Volatile
        private var Instance: CleanUnderwearDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN residence_info TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN lockup_url TEXT")
                database.execSQL("ALTER TABLE targets ADD COLUMN obituary_url TEXT")
                database.execSQL("ALTER TABLE targets ADD COLUMN check_frequency_hours INTEGER NOT NULL DEFAULT 24")
                database.execSQL("ALTER TABLE targets ADD COLUMN next_scheduled_check INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN last_status_change_timestamp INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN last_verification_snippet TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Create the new table with correct nullability and the new 'email' column
                database.execSQL("""
                    CREATE TABLE targets_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        display_name TEXT NOT NULL,
                        phone_number TEXT,
                        area_code TEXT,
                        jurisdiction TEXT,
                        status TEXT NOT NULL,
                        last_scraped_timestamp INTEGER NOT NULL,
                        source_account TEXT,
                        residence_info TEXT,
                        lockup_url TEXT,
                        obituary_url TEXT,
                        check_frequency_hours INTEGER NOT NULL,
                        next_scheduled_check INTEGER NOT NULL,
                        last_status_change_timestamp INTEGER NOT NULL,
                        last_verification_snippet TEXT,
                        email TEXT
                    )
                """.trimIndent())

                // 2. Copy data from the old table
                database.execSQL("""
                    INSERT INTO targets_new (
                        id, display_name, phone_number, area_code, jurisdiction, status, 
                        last_scraped_timestamp, source_account, residence_info, lockup_url, 
                        obituary_url, check_frequency_hours, next_scheduled_check, 
                        last_status_change_timestamp, last_verification_snippet
                    )
                    SELECT 
                        id, display_name, phone_number, area_code, jurisdiction, status, 
                        last_scraped_timestamp, source_account, residence_info, lockup_url, 
                        obituary_url, check_frequency_hours, next_scheduled_check, 
                        last_status_change_timestamp, last_verification_snippet
                    FROM targets
                """.trimIndent())

                // 3. Drop the old table
                database.execSQL("DROP TABLE targets")

                // 4. Rename the new table
                database.execSQL("ALTER TABLE targets_new RENAME TO targets")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE targets ADD COLUMN monitorability_state TEXT NOT NULL DEFAULT 'READY'"
                )
            }
        }

        /**
         * Source-catalog redesign migration. Strips search-engine URLs that
         * earlier versions persisted as "evidence", and resets any status flip
         * those URLs caused so the contact re-confirms against a real catalog
         * source on the next pipeline run.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val poisonedClause = listOf(
                    "url LIKE 'https://www.google.com/search%'",
                    "url LIKE 'https://google.com/search%'",
                    "url LIKE 'https://duckduckgo.com/%'",
                    "url LIKE 'https://html.duckduckgo.com/%'",
                    "url LIKE 'https://www.bing.com/search%'",
                    "url LIKE 'https://news.google.com/%'"
                ).joinToString(" OR ")

                database.execSQL(
                    "UPDATE targets SET lockup_url = NULL WHERE " +
                        poisonedClause.replace("url", "lockup_url")
                )
                database.execSQL(
                    "UPDATE targets SET obituary_url = NULL WHERE " +
                        poisonedClause.replace("url", "obituary_url")
                )

                // We can't tell which prior INCARCERATED/DECEASED rows came
                // from real evidence vs the Google fallback. Reset everything
                // and let the new pipeline re-confirm against curated sources.
                database.execSQL(
                    "UPDATE targets SET " +
                        "status = 'MONITORING', " +
                        "last_verification_snippet = NULL, " +
                        "last_status_change_timestamp = 0 " +
                    "WHERE status IN ('INCARCERATED', 'DECEASED')"
                )

                database.execSQL("UPDATE targets SET next_scheduled_check = 0")
            }
        }

        /**
         * Backfill missed by MIGRATION_6_7: rows that existed before
         * monitorability_state was introduced were defaulted to 'READY' even
         * when they had no phone, no email, or a placeholder name — meaning
         * the daily vigil would try to scrape them with no usable identity
         * and the auto-pipeline would never queue them for enrichment.
         * Mark those rows NEEDS_ENRICHMENT so the user-initiated CBC flow
         * can pick them up.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "UPDATE targets SET monitorability_state = 'NEEDS_ENRICHMENT' " +
                        "WHERE monitorability_state = 'READY' AND (" +
                        "(phone_number IS NULL AND email IS NULL) OR " +
                        "display_name = 'Unnamed Entity' OR " +
                        "display_name LIKE 'Unnamed Entity (%'" +
                        ")"
                )
            }
        }

        /**
         * Adds enrichment_provenance, the human-readable record of which CBC
         * search mode(s) sourced a contact's data and whether the candidate
         * identity was verified before merge. Nullable; existing rows backfill
         * to NULL (no provenance recorded for pre-verification enrichments).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN enrichment_provenance TEXT")
            }
        }

        /**
         * Adds the corroboration + review columns: middle_name and date_of_birth
         * (captured from CyberBackgroundChecks detail pages, used to corroborate
         * a same-name roster hit) and dismissed_match_keys (record ids the user
         * marked "not a match", so they don't resurface). All nullable; existing
         * rows backfill to NULL.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN middle_name TEXT")
                database.execSQL("ALTER TABLE targets ADD COLUMN date_of_birth TEXT")
                database.execSQL("ALTER TABLE targets ADD COLUMN dismissed_match_keys TEXT")
            }
        }

        /** Match verification columns: confidence, event kind and reasons. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN match_confidence REAL")
                database.execSQL("ALTER TABLE targets ADD COLUMN match_kind TEXT")
                database.execSQL("ALTER TABLE targets ADD COLUMN match_reasons TEXT")
            }
        }

        /**
         * Merge ledger and review decisions. Merges made before this version were not recorded, so
         * the ledger starts empty; only stale-number review works on existing data.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS merge_ledger (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        survivor_id INTEGER NOT NULL,
                        absorbed_json TEXT NOT NULL,
                        changes_json TEXT NOT NULL,
                        absorbed_name TEXT NOT NULL,
                        absorbed_phone TEXT,
                        absorbed_email TEXT,
                        probability REAL NOT NULL,
                        weight_bits REAL NOT NULL,
                        best_case_bits REAL NOT NULL,
                        contributions TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        merged_at INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        veto_a TEXT,
                        veto_b TEXT
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_merge_ledger_survivor_id ON merge_ledger (survivor_id)"
                )
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS suspect_decisions (
                        suspect_key TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        decided_at INTEGER NOT NULL,
                        PRIMARY KEY(suspect_key)
                    )
                """.trimIndent())
            }
        }

        /** The number the user marked stale in Review; null when none. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE targets ADD COLUMN stale_phone TEXT")
            }
        }

        fun getDatabase(context: Context): CleanUnderwearDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    CleanUnderwearDatabase::class.java,
                    "cleanunderwear_database"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14
                )
                .build()
                .also { Instance = it }
            }
        }
    }
}

