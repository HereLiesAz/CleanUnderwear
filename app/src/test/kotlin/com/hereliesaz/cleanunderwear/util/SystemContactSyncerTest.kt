package com.hereliesaz.cleanunderwear.util

import android.content.ContentResolver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Delete everything's note removal must report, never throw, when it can't see the contacts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemContactSyncerTest {

    @Test
    fun withoutContactsAccess_removalSaysSoInsteadOfCrashing() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } throws SecurityException("no READ_CONTACTS")
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver

        val result = SystemContactSyncer(context).removeAllBlocks()

        assertTrue(result.noAccess)
        assertEquals(0, result.removed)
    }

    private val real: Context = ApplicationProvider.getApplicationContext()

    private fun contextWithPrefs(resolver: ContentResolver): Context {
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } answers { real.getSharedPreferences(firstArg(), secondArg()) }
        return context
    }

    private fun setWriteBack(on: Boolean?) {
        val edit = real.getSharedPreferences("clean_underwear_prefs", Context.MODE_PRIVATE).edit()
        if (on == null) edit.remove(SystemContactSyncer.PREF_NOTE_WRITE_BACK_ENABLED) else edit.putBoolean(SystemContactSyncer.PREF_NOTE_WRITE_BACK_ENABLED, on)
        edit.commit()
    }

    private val monitored = Target(displayName = "Jane Doe", phoneNumber = "504-555-0303", status = TargetStatus.MONITORING)

    @Test
    fun noteWriteBackIsOnUnlessTheUserTurnsItOff() {
        setWriteBack(null)
        assertTrue(SystemContactSyncer.noteWriteBackEnabled(real))
        setWriteBack(false)
        assertFalse(SystemContactSyncer.noteWriteBackEnabled(real))
        setWriteBack(true)
        assertTrue(SystemContactSyncer.noteWriteBackEnabled(real))
    }

    @Test
    fun withWriteBackOff_theContactBookIsNeverTouched() {
        setWriteBack(false)
        val resolver = mockk<ContentResolver>()
        val context = contextWithPrefs(resolver)

        SystemContactSyncer(context).syncToSystem(monitored)

        verify(exactly = 0) { context.contentResolver }
    }

    @Test
    fun withWriteBackOn_theContactBookIsLookedAt() {
        setWriteBack(true)
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        val context = contextWithPrefs(resolver)

        SystemContactSyncer(context).syncToSystem(monitored)

        verify(atLeast = 1) { context.contentResolver }
    }
}
