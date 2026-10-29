package com.hereliesaz.cleanunderwear.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of MainViewModel's browser waits, queue notices and launch-time contact read. */
class MainViewModelHelpersTest {

    // --- waits on the user are limited ---

    @Test
    fun anAnswerInTimeIsReturnedAndTheTimeoutNeverRuns() = runTest {
        val deferred = CompletableDeferred<String>()
        deferred.complete("html")
        var timedOut = false

        val result = awaitWithin(deferred, 1_000L) { timedOut = true; "gave up" }

        assertEquals("html", result)
        assertFalse(timedOut)
    }

    @Test
    fun aWaitThatOutlastsTheLimitGivesUp() = runTest {
        // Never completed: the user walked away from the CAPTCHA.
        val deferred = CompletableDeferred<String?>()
        var timedOut = false

        val result = awaitWithin(deferred, USER_WAIT_IMPORT_MS) { timedOut = true; null }

        assertTrue(timedOut)
        assertEquals(null, result)
        // The deferred is left alone; a late answer is simply nobody's business.
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun theLimitsAreMinutesNotSecondsAndImportsGetLonger() {
        assertTrue(USER_WAIT_LOOKUP_MS >= 5 * 60 * 1000L)
        assertTrue(USER_WAIT_IMPORT_MS >= USER_WAIT_LOOKUP_MS)
    }

    // --- a job that has to queue says so ---

    @Test
    fun queuedNoticeNamesWhatIsWaitingAndWhatItWaitsFor() {
        assertEquals(
            "Delete everything will start once this is done: Looking up Dana Reyes (3 of 12)",
            queuedNotice("Delete everything", "Looking up Dana Reyes (3 of 12)")
        )
    }

    @Test
    fun queuedNoticeWithoutADescriptionStillSaysSomething() {
        assertEquals("Importing contacts will start once the job now running is done.", queuedNotice("Importing contacts", null))
        assertEquals("Importing contacts will start once the job now running is done.", queuedNotice("Importing contacts", "  "))
    }

    // --- the contact read at launch stays out of the way ---

    @Test
    fun theFirstReadRightAfterStartIsTheLaunchOne() {
        assertTrue(isLaunchSweep(createdAt = 1_000L, now = 1_050L, alreadyRequested = false))
        assertTrue(isLaunchSweep(createdAt = 1_000L, now = 1_000L + LAUNCH_SWEEP_WINDOW_MS, alreadyRequested = false))
    }

    @Test
    fun aReadTheUserAsksForLaterIsNotTheLaunchOne() {
        // Past the window: nobody gets to Import contacts in the rail that fast, but a later tap is theirs.
        assertFalse(isLaunchSweep(createdAt = 1_000L, now = 1_001L + LAUNCH_SWEEP_WINDOW_MS, alreadyRequested = false))
        // A second request, even inside the window, is not the launch read.
        assertFalse(isLaunchSweep(createdAt = 1_000L, now = 1_050L, alreadyRequested = true))
    }

    @Test
    fun aClockThatReadsEarlierThanTheCreationIsNotTheLaunchRead() {
        assertFalse(isLaunchSweep(createdAt = 5_000L, now = 4_000L, alreadyRequested = false))
    }
}
