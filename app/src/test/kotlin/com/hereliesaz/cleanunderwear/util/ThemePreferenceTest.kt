package com.hereliesaz.cleanunderwear.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferenceTest {

    @Test
    fun aSavedChoiceAlwaysWins() {
        assertFalse(ThemePreference.resolve(stored = false, onboarded = true, systemNight = true))
        assertTrue(ThemePreference.resolve(stored = true, onboarded = false, systemNight = false))
    }

    @Test
    fun aFirstRunFollowsThePhone() {
        assertTrue(ThemePreference.resolve(stored = null, onboarded = false, systemNight = true))
        assertFalse(ThemePreference.resolve(stored = null, onboarded = false, systemNight = false))
    }

    @Test
    fun anInstallThatNeverChoseKeepsTheDarkItHasBeenShowing() {
        assertTrue(ThemePreference.resolve(stored = null, onboarded = true, systemNight = false))
    }
}
