package com.hereliesaz.cleanunderwear.ui

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Alerts: the per-kind opt-outs hold, and the lock screen never shows a name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val prefs get() = context.getSharedPreferences(NotificationHelper.PREFS, Context.MODE_PRIVATE)
    private fun match(kind: String) =
        Target(id = 7, displayName = "Jane Doe", status = TargetStatus.POSSIBLE_MATCH, matchKind = kind)

    @Before
    fun allow() {
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        prefs.edit().clear().commit()
    }

    @Test
    fun aPossibleDeathIsAlerted_privatelyOnTheLockScreen() {
        NotificationHelper(context).notifyStatusChange(match("DEATH"), TargetStatus.MONITORING)
        val posted = shadowOf(manager).allNotifications.single()
        assertTrue(posted.extras.getString("android.title")!!.contains("Jane Doe"))
        val public = posted.publicVersion!!
        assertTrue(!(public.extras.getString("android.title") ?: "").contains("Jane"))
        assertTrue(!(public.extras.getString("android.text") ?: "").contains("Jane"))
    }

    @Test
    fun turningOffDeathAlertsSilencesDeathsOnly() {
        prefs.edit().putBoolean(NotificationHelper.PREF_NOTIFY_DEATH, false).commit()
        val helper = NotificationHelper(context)
        helper.notifyStatusChange(match("DEATH"), TargetStatus.MONITORING)
        assertEquals(0, shadowOf(manager).allNotifications.size)
        helper.notifyStatusChange(match("CUSTODY"), TargetStatus.MONITORING)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun turningOffCustodyAlertsSilencesArrestsAndCustody() {
        prefs.edit().putBoolean(NotificationHelper.PREF_NOTIFY_CUSTODY, false).commit()
        val helper = NotificationHelper(context)
        helper.notifyStatusChange(match("CUSTODY"), TargetStatus.MONITORING)
        helper.notifyStatusChange(match("ARREST"), TargetStatus.MONITORING)
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun anAlertDoesNotReplaceAnUntaggedNotificationWithTheSameId() {
        // The daily check's ongoing notification is untagged with a fixed id; a contact whose id
        // is the same must neither replace it nor be replaced by it.
        val ongoing = androidx.core.app.NotificationCompat.Builder(context, "checks_running")
            .setSmallIcon(com.hereliesaz.cleanunderwear.R.drawable.ic_stat_briefs)
            .setContentTitle("Checking records")
            .build()
        manager.notify(4101, ongoing)

        NotificationHelper(context).notifyStatusChange(
            Target(id = 4101, displayName = "Jane Doe", status = TargetStatus.POSSIBLE_MATCH, matchKind = "DEATH"),
            TargetStatus.MONITORING
        )

        assertEquals(2, shadowOf(manager).allNotifications.size)
        assertTrue(shadowOf(manager).getNotification(NotificationHelper.ALERT_TAG, 4101) != null)
        assertTrue(shadowOf(manager).getNotification(4101) != null)
    }
}
