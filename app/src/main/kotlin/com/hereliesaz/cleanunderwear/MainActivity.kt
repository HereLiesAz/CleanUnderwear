package com.hereliesaz.cleanunderwear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.hereliesaz.cleanunderwear.ui.AppDialog
import com.hereliesaz.cleanunderwear.ui.AppTypography
import com.hereliesaz.cleanunderwear.ui.CleanUnderwearApp
import com.hereliesaz.cleanunderwear.ui.MainViewModel
import com.hereliesaz.cleanunderwear.ui.NotificationHelper
import com.hereliesaz.cleanunderwear.ui.SurveillanceDarkColorScheme
import com.hereliesaz.cleanunderwear.ui.SurveillanceLightColorScheme
import com.hereliesaz.cleanunderwear.util.CrashReport
import com.hereliesaz.cleanunderwear.util.ThemePreference
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app's own theme, not the phone's, decides the window and system-bar colours from
        // here on. What came before this line is the manifest's launch theme: the starting window
        // and, on Android 12+, the system splash, both drawn before any code runs. They can't
        // read this preference, so they follow the phone's night mode (values/themes.xml); when
        // the app's choice differs from the phone's, that first frame is the other colour.
        val darkAtStart = ThemePreference.isDark(getSharedPreferences("clean_underwear_prefs", MODE_PRIVATE), this)
        if (!darkAtStart) setTheme(R.style.Theme_CleanUnderwear_Light)
        applySystemBars(darkAtStart)
        super.onCreate(savedInstanceState)

        // Only on a fresh start: after a rotation the intent is the same, and the user may have left that contact.
        if (savedInstanceState == null) openTargetFrom(intent)

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsState()
            val onboarded by viewModel.isOnboardingCompleted.collectAsState()
            val colorScheme = if (isDarkTheme) SurveillanceDarkColorScheme else SurveillanceLightColorScheme

            LaunchedEffect(isDarkTheme) { applySystemBars(isDarkTheme) }

            MaterialTheme(colorScheme = colorScheme, typography = AppTypography) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LaunchedEffect(Unit) { viewModel.scheduleDailyPanopticon() }
                    // Permissions are asked for at the end of onboarding, after the app has
                    // explained itself. Contacts are read on the first creation (not a rotation)
                    // once onboarding is done and only if the user allowed it.
                    LaunchedEffect(onboarded) {
                        if (onboarded && savedInstanceState == null && contactsAllowed()) viewModel.sweepContacts()
                    }
                    CleanUnderwearApp(viewModel = viewModel)

                    var crash by remember(onboarded) { mutableStateOf(if (onboarded) CrashReport.pending(this) else null) }
                    crash?.let { report ->
                        AppDialog(
                            title = "The app closed unexpectedly",
                            confirm = "Send report",
                            onConfirm = {
                                crash = null
                                CrashReport.discard(this)
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, CrashReport.issueUrl(report)))
                                } catch (e: android.content.ActivityNotFoundException) {
                                    // No browser.
                                }
                            },
                            onDismiss = { crash = null; CrashReport.discard(this) },
                            dismiss = "Don't send"
                        ) {
                            Text(
                                "A report opens on GitHub in your browser, where you can read it before posting. " +
                                    "It holds the app and Android versions and where in the code it failed; no " +
                                    "contact names, numbers or other details."
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTargetFrom(intent)
    }

    /**
     * Edge to edge, with status- and navigation-bar icons for the app's theme: light icons on the
     * dark theme, dark on the light one. Left to itself the bar follows the phone's setting, which
     * on a light phone puts dark icons on the black app.
     */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(if (dark) 0xFF0A0A0A.toInt() else 0xFFF7F7F7.toInt())
        )
    }

    private fun contactsAllowed() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** A notification names the contact it is about; open that contact. */
    private fun openTargetFrom(intent: Intent?) {
        val id = intent?.getIntExtra(NotificationHelper.EXTRA_TARGET_ID, -1) ?: -1
        if (id >= 0) viewModel.requestOpenTarget(id)
    }
}
