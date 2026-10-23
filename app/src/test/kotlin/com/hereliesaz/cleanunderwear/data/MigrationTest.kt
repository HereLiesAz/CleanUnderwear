package com.hereliesaz.cleanunderwear.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrades a database built at an older version and opens it with Room, which validates every
 * table against the entities: a migration that drifts from the schema fails here, not on a
 * user's phone. Older tables are the current exported schema minus the columns each later
 * migration added, and rows written at the old version must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    private val schema = JsonParser.parseString(
        File("schemas/com.hereliesaz.cleanunderwear.data.CleanUnderwearDatabase/15.json").readText()
    ).asJsonObject.getAsJsonObject("database")

    /** The current CREATE statements, with [dropColumns] removed from `targets`. */
    private fun ddl(dropColumns: List<String>): List<String> =
        schema.getAsJsonArray("entities").flatMap { e ->
            val entity = e.asJsonObject
            val table = entity.get("tableName").asString
            var create = entity.get("createSql").asString.replace("\${TABLE_NAME}", table)
            if (table == "targets") dropColumns.forEach { col -> create = create.replace(Regex(", `$col` [A-Z]+( NOT NULL)?"), "") }
            listOf(create) + (entity.getAsJsonArray("indices")?.map { it.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table) }.orEmpty())
        }

    private fun createAt(version: Int, dropColumns: List<String>) {
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            ddl(dropColumns).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO targets (display_name, phone_number, status, last_scraped_timestamp, check_frequency_hours, " +
                    "next_scheduled_check, last_status_change_timestamp, monitorability_state) " +
                    "VALUES ('Jane Doe', '504-555-0303', 'DECEASED', 0, 24, 0, 0, 'READY')"
            )
            db.version = version
        }
    }

    private fun openCurrent(): CleanUnderwearDatabase =
        Room.databaseBuilder(context, CleanUnderwearDatabase::class.java, name)
            .addMigrations(*CleanUnderwearDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private var db: CleanUnderwearDatabase? = null

    @After
    fun close() {
        db?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun from14_keepsRowsAndMatchesTheSchema() = runBlocking {
        createAt(14, listOf("status_before_ignore", "unread_sources", "match_record_key"))
        db = openCurrent()
        val row = db!!.targetDao().getTargetsPaged(10, 0).single()
        assertEquals("Jane Doe", row.displayName)
        assertEquals(TargetStatus.DECEASED, row.status)
        val full = db!!.targetDao().getTargetById(row.id)!!
        assertNull(full.statusBeforeIgnore)
        assertNull(full.matchRecordKey)
    }

    @Test
    fun from13_runsTheChain() = runBlocking {
        createAt(13, listOf("stale_phone", "status_before_ignore", "unread_sources", "match_record_key"))
        db = openCurrent()
        assertEquals("Jane Doe", db!!.targetDao().getTargetsPaged(10, 0).single().displayName)
    }
}
