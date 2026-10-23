package com.hereliesaz.cleanunderwear.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.cleanunderwear.data.CleanUnderwearDatabase
import com.hereliesaz.cleanunderwear.data.OfflineTargetRepository
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.match.CandidateRecord
import com.hereliesaz.cleanunderwear.match.EventKind
import com.hereliesaz.cleanunderwear.network.IdentityVerifier
import com.hereliesaz.cleanunderwear.network.SearchResult
import com.hereliesaz.cleanunderwear.network.SourceCatalog
import com.hereliesaz.cleanunderwear.network.SourceSearch
import com.hereliesaz.cleanunderwear.ui.NotificationHelper
import com.hereliesaz.cleanunderwear.util.SystemContactSyncer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The daily check's decisions: what a failed source, a clean miss, a strong match and a
 * concurrent user action do to a contact, and when the user is told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrapeTargetsUseCaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CleanUnderwearDatabase
    private lateinit var repo: OfflineTargetRepository
    private val search = mockk<SourceSearch>()
    private val notifications = mockk<NotificationHelper>(relaxed = true)
    private val syncer = mockk<SystemContactSyncer>(relaxed = true)
    private val year = java.time.Year.now().value

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, CleanUnderwearDatabase::class.java).allowMainThreadQueries().build()
        repo = OfflineTargetRepository(db.targetDao(), db)
        context.getSharedPreferences("clean_underwear_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun close() = db.close()

    private fun useCase() = ScrapeTargetsUseCase(context, repo, IdentityVerifier(), SourceCatalog(context), notifications, syncer, search)

    private suspend fun seed(status: TargetStatus, vararg extra: (Target) -> Target): Int {
        var t = Target(
            displayName = "Jane Doe", phoneNumber = "504-555-0303", areaCode = "504", residenceInfo = "Gretna, LA",
            dateOfBirth = "1970", status = status, nextScheduledCheck = 0,
            lockupUrl = null, obituaryUrl = if (status == TargetStatus.POSSIBLE_MATCH) "https://www.echovita.com/us/obituaries/la/gretna/x" else null,
            matchKind = if (status == TargetStatus.POSSIBLE_MATCH) "DEATH" else null
        )
        extra.forEach { t = it(t) }
        repo.insertTarget(t)
        return repo.getTargetsPaged(1, 0).single().id
    }

    private fun answer(result: SearchResult) {
        coEvery { search.search(any(), any(), any(), any(), any(), any()) } returns result
    }

    private val obituary = CandidateRecord(
        sourceId = "echovita_obit", sourceLabel = "Echovita", kind = EventKind.DEATH,
        firstName = "Jane", lastName = "Doe", age = year - 1970, city = "Gretna", state = "LA",
        recordKey = "echovita|jane|doe", evidenceUrl = "https://www.echovita.com/us/obituaries/la/gretna/jane-doe-1"
    )

    @Test
    fun unreadableSources_leaveAPendingMatchAndItsEvidenceAlone() = runBlocking {
        val id = seed(TargetStatus.POSSIBLE_MATCH)
        answer(SearchResult.FAILED)
        useCase().invoke()
        val t = repo.getTargetById(id)!!
        assertEquals(TargetStatus.POSSIBLE_MATCH, t.status)
        assertNotNull(t.obituaryUrl)
        assertNotNull(t.unreadSources)
        assertTrue(t.nextScheduledCheck - System.currentTimeMillis() <= 6 * 3600_000L)
        verify(exactly = 0) { notifications.notifyStatusChange(any(), any()) }
    }

    @Test
    fun aStrongMatchOnAFirstCheckIsNotified() = runBlocking {
        val id = seed(TargetStatus.UNKNOWN)
        answer(SearchResult(listOf(obituary)))
        useCase().invoke()
        val t = repo.getTargetById(id)!!
        assertEquals(TargetStatus.POSSIBLE_MATCH, t.status)
        assertEquals("echovita|jane|doe", t.matchRecordKey)
        verify(exactly = 1) { notifications.notifyStatusChange(any(), TargetStatus.UNKNOWN) }
    }

    @Test
    fun aFirstCheckFindingNothingIsSilent() = runBlocking {
        val id = seed(TargetStatus.UNKNOWN)
        answer(SearchResult(emptyList()))
        useCase().invoke()
        assertEquals(TargetStatus.MONITORING, repo.getTargetById(id)!!.status)
        verify(exactly = 0) { notifications.notifyStatusChange(any(), any()) }
    }

    @Test
    fun aMatchGoneFromEveryReadSourceClearsAndSaysSo() = runBlocking {
        val id = seed(TargetStatus.POSSIBLE_MATCH)
        answer(SearchResult(emptyList()))
        useCase().invoke()
        val t = repo.getTargetById(id)!!
        assertEquals(TargetStatus.MONITORING, t.status)
        assertNull(t.obituaryUrl)
        verify(exactly = 1) { notifications.notifyStatusChange(any(), TargetStatus.POSSIBLE_MATCH) }
    }

    @Test
    fun aDismissedRecordIsNotRaisedAgain() = runBlocking {
        val id = seed(TargetStatus.MONITORING, { it.copy(dismissedMatchKeys = "echovita|jane|doe") })
        answer(SearchResult(listOf(obituary)))
        useCase().invoke()
        assertEquals(TargetStatus.MONITORING, repo.getTargetById(id)!!.status)
    }

    @Test
    fun theUsersChangeDuringACheckWins() = runBlocking {
        val id = seed(TargetStatus.MONITORING)
        coEvery { search.search(any(), any(), any(), any(), any(), any()) } coAnswers {
            repo.ignore(id, System.currentTimeMillis())
            SearchResult(listOf(obituary))
        }
        useCase().invoke()
        assertEquals(TargetStatus.IGNORED, repo.getTargetById(id)!!.status)
        verify(exactly = 0) { notifications.notifyStatusChange(any(), any()) }
    }

    @Test
    fun aSiteThatNeverAnswersDoesNotHoldANewContactUnchecked() = runBlocking {
        val id = seed(TargetStatus.UNKNOWN)
        answer(SearchResult.FAILED)
        useCase().invoke()
        val t = repo.getTargetById(id)!!
        assertEquals(TargetStatus.MONITORING, t.status)
        assertNotNull(t.unreadSources)
        verify(exactly = 0) { notifications.notifyStatusChange(any(), any()) }
    }

    @Test
    fun aStatusRestoredFromANoteWaitsForTheUser() = runBlocking {
        val id = seed(TargetStatus.POSSIBLE_MATCH, { it.copy(matchRecordKey = com.hereliesaz.cleanunderwear.util.ContactNote.RESTORED_KEY) })
        answer(SearchResult(emptyList()))
        useCase().invoke()
        assertEquals(TargetStatus.POSSIBLE_MATCH, repo.getTargetById(id)!!.status)
        verify(exactly = 0) { notifications.notifyStatusChange(any(), any()) }
    }
}
