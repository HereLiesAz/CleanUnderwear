package com.hereliesaz.cleanunderwear.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * Whether the app starts dark. The launch window and the Android 12+ system splash are drawn
 * from the phone's night mode before any code runs (res/values/themes.xml), so a first run
 * follows the phone too, and the first frame and the app agree.
 *
 * Installs from before that were all dark by default; one that finished onboarding without ever
 * choosing keeps what it has been showing. [persistChoiceAtOnboarding] pins whatever a new
 * install is showing at that point, so the app never changes colour on its own afterwards.
 */
object ThemePreference {
    const val KEY = "dark_theme"
    private const val ONBOARDING_KEY = "onboarding_done"

    /** [stored] is the saved choice, if any. */
    fun resolve(stored: Boolean?, onboarded: Boolean, systemNight: Boolean): Boolean =
        stored ?: if (onboarded) true else systemNight

    fun isDark(prefs: SharedPreferences, context: Context): Boolean = resolve(
        stored = if (prefs.contains(KEY)) prefs.getBoolean(KEY, true) else null,
        onboarded = prefs.getBoolean(ONBOARDING_KEY, false),
        systemNight = systemIsNight(context)
    )

    /** Saves the theme in use if the user has not chosen one, so it stops following the phone. */
    fun persistChoiceAtOnboarding(prefs: SharedPreferences, showingDark: Boolean) {
        if (!prefs.contains(KEY)) prefs.edit().putBoolean(KEY, showingDark).apply()
    }

    private fun systemIsNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
