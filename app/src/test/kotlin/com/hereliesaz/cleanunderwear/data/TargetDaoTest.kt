package com.hereliesaz.cleanunderwear.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The DAO's own SQL, on a real (in-memory) Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetDaoTest {

    private lateinit var db: CleanUnderwearDatabase
    private lateinit var dao: TargetDao

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CleanUnderwearDatabase::class.java).allowMainThreadQueries().build()
        dao = db.targetDao()
    }

    @After
    fun close() = db.close()

    private fun t(name: String, next: Long, status: TargetStatus = TargetStatus.MONITORING, state: MonitorabilityState = MonitorabilityState.READY) =
        Target(displayName = name, status = status, monitorabilityState = state, nextScheduledCheck = next)

    @Test
    fun dueContacts_longestWaitingFirst_skippingIgnoredAndUnready() = runBlocking {
        dao.insertTargets(
            listOf(
                t("Zed Last", next = 1), t("Amy First", next = 5), t("Ignored One", next = 0, status = TargetStatus.IGNORED),
                t("No Source", next = 0, state = MonitorabilityState.NO_AUTOMATED_SOURCE), t("Not Due", next = 1_000)
            )
        )
        assertEquals(listOf("Zed Last", "Amy First"), dao.getReadyDueTargets(now = 100).map { it.displayName })
    }

    @Test
    fun contactLimit_checksOnlyTheFirstAdded() = runBlocking {
        dao.insertTargets(listOf(t("First Added", next = 9), t("Second Added", next = 1), t("Third Added", next = 0)))
        assertEquals(listOf("Second Added", "First Added"), dao.getReadyDueTargets(now = 100, limit = 2).map { it.displayName })
        assertEquals(3, dao.getReadyDueTargets(now = 100, limit = 0).size)
    }

    @Test
    fun ignoreThenRestore_keepsAConfirmedStatus() = runBlocking {
        dao.insertTarget(t("Jane Doe", next = 0, status = TargetStatus.DECEASED))
        val id = dao.getTargetsPaged(1, 0).single().id
        dao.ignore(id, 1)
        dao.ignore(id, 2) // twice: the remembered status must not become IGNORED
        assertEquals(TargetStatus.IGNORED, dao.getTargetById(id)!!.status)
        dao.unignore(id, 3)
        assertEquals(TargetStatus.DECEASED, dao.getTargetById(id)!!.status)
    }

    @Test
    fun sortOrders_doWhatTheySay() = runBlocking {
        dao.insertTargets(
            listOf(
                Target(displayName = "zoe Adams", status = TargetStatus.MONITORING, lastScrapedTimestamp = 30, lastStatusChangeTimestamp = 99),
                Target(displayName = "Adam Zed", status = TargetStatus.POSSIBLE_MATCH, lastScrapedTimestamp = 10),
                Target(displayName = "Mia Mid", status = TargetStatus.DECEASED, lastScrapedTimestamp = 20)
            )
        )
        suspend fun order(sort: String): List<String> {
            val page = dao.searchTargets("", false, null, null, null, null, null, null, null, null, null, sort)
                .load(androidx.paging.PagingSource.LoadParams.Refresh(null, 10, false))
            return (page as androidx.paging.PagingSource.LoadResult.Page).data.map { it.displayName }
        }
        assertEquals(listOf("Adam Zed", "Mia Mid", "zoe Adams"), order("NAME"))
        assertEquals(listOf("Adam Zed", "Mia Mid", "zoe Adams"), order("STATUS"))
        assertEquals(listOf("zoe Adams", "Mia Mid", "Adam Zed"), order("DATE"))
    }
}
