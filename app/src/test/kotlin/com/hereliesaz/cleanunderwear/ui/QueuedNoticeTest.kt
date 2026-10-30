package com.hereliesaz.cleanunderwear.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A job that has to queue says so. The notice is words from the resources, so it is resolved
 * through the app's real strings (Robolectric supplies them; the default locale is English).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueuedNoticeTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun queuedNoticeNamesWhatIsWaitingAndWhatItWaitsFor() {
        assertEquals(
            "Delete everything will start once this is done: Looking up Dana Reyes (3 of 12)",
            queuedNotice("Delete everything", "Looking up Dana Reyes (3 of 12)", resources)
        )
    }

    @Test
    fun queuedNoticeWithoutADescriptionStillSaysSomething() {
        assertEquals(
            "Importing contacts will start once the job now running is done.",
            queuedNotice("Importing contacts", null, resources)
        )
        assertEquals(
            "Importing contacts will start once the job now running is done.",
            queuedNotice("Importing contacts", "  ", resources)
        )
    }
}
