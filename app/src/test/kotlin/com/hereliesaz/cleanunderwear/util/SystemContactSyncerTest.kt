package com.hereliesaz.cleanunderwear.util

import android.content.ContentResolver
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
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
}
