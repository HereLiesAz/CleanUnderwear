package com.hereliesaz.cleanunderwear.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.cleanunderwear.data.CleanUnderwearDatabase
import com.hereliesaz.cleanunderwear.data.MessagingPresenceSource
import com.hereliesaz.cleanunderwear.data.NoCommsHistory
import com.hereliesaz.cleanunderwear.data.OfflineTargetRepository
import com.hereliesaz.cleanunderwear.data.RoomMergeLedger
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A merge recorded in the ledger can be undone, giving back the absorbed contact whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergeSplitRoundTripTest {

    private lateinit var db: CleanUnderwearDatabase

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CleanUnderwearDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun splitRestoresTheAbsorbedRowAndTheSurvivor() = runBlocking {
        val repo = OfflineTargetRepository(db.targetDao(), db)
        val ledger = RoomMergeLedger(db.mergeLedgerDao())
        repo.insertTargets(
            listOf(
                Target(displayName = "Jane Doe", phoneNumber = "555-123-4567", email = "jane@a.com", status = TargetStatus.MONITORING),
                Target(
                    displayName = "Jane Doe", phoneNumber = "(555) 123-4567", residenceInfo = "Austin, TX",
                    status = TargetStatus.DECEASED, dismissedMatchKeys = "bop:1"
                )
            )
        )

        DeduplicateTargetsUseCase(repo, ledger = ledger).invoke()
        assertEquals(1, repo.getTargetsPaged(10, 0).size)
        val merge = db.mergeLedgerDao().getActiveSummaries().single()

        val review = SuspectReview(repo, db.mergeLedgerDao(), NoCommsHistory(), mockk<MessagingPresenceSource>(relaxed = true))
        assertTrue(review.split(merge.id))

        val rows = repo.getTargetsPaged(10, 0).map { repo.getTargetById(it.id)!! }
        assertEquals(2, rows.size)
        val restored = rows.single { it.residenceInfo == "Austin, TX" && it.email == null }
        assertEquals(TargetStatus.DECEASED, restored.status)
        assertEquals("bop:1", restored.dismissedMatchKeys)

        // The pair is vetoed: the next dedup leaves them apart.
        DeduplicateTargetsUseCase(repo, ledger = ledger).invoke()
        assertEquals(2, repo.getTargetsPaged(10, 0).size)
    }
}
