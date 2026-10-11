package com.hereliesaz.cleanunderwear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.hereliesaz.cleanunderwear.ui.MainViewModel
import com.hereliesaz.cleanunderwear.ui.CleanUnderwearApp
import com.hereliesaz.cleanunderwear.ui.SurveillanceDarkColorScheme
import com.hereliesaz.cleanunderwear.ui.SurveillanceLightColorScheme
import com.hereliesaz.cleanunderwear.util.GitHubCrashReporter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val contactsGranted = permissions[Manifest.permission.READ_CONTACTS] ?: false
        if (contactsGranted) {
            viewModel.sweepContacts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize GitHub Crash Reporting
        setupCrashReporting()

        checkAndRequestPermissions()

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsState()
            val colorScheme = if (isDarkTheme) SurveillanceDarkColorScheme else SurveillanceLightColorScheme

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LaunchedEffect(Unit) {
                        viewModel.scheduleDailyPanopticon()
                    }

                    CleanUnderwearApp(viewModel = viewModel)
                }
            }
        }
    }

    private fun setupCrashReporting() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // In production, you'd use a background service or WorkManager for this.
            // For now, we attempt a quick fire-and-forget.
            GitHubCrashReporter(this).reportCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            showPermissionRationale {
                requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            }
        } else {
            // Already have permissions, ensure contacts are swept if needed
            viewModel.sweepContacts()
        }
    }

    private fun showPermissionRationale(onConfirm: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Contacts & Notifications")
            .setMessage(
                "CleanUnderwear reads your contacts and checks their names against public arrest, " +
                    "inmate and obituary records. To do this, contact names and locations are sent to " +
                    "those records sites, and to people-search sites when you look up missing details. " +
                    "Results are saved to your contacts' notes. Notifications tell you when a status " +
                    "changes. Your contact list is never uploaded to a server of ours."
            )
            .setPositiveButton("Grant Access") { _, _ -> onConfirm() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
