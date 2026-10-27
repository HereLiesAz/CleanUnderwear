package com.hereliesaz.cleanunderwear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hereliesaz.aznavrail.AzRoller
import com.hereliesaz.aznavrail.AzToggle
import com.hereliesaz.cleanunderwear.update.UpdateChannel
import com.hereliesaz.cleanunderwear.update.UpdateResult

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val showDiagnosticLog by viewModel.showDiagnosticLog.collectAsState()
    val globalLimit by viewModel.globalTargetLimit.collectAsState()
    val notifyDeath by viewModel.notifyDeath.collectAsState()
    val notifyCustody by viewModel.notifyCustody.collectAsState()
    val checkHour by viewModel.checkHour.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    var confirmDelete by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    // Permission status is re-read whenever the screen comes back, e.g. from system settings.
    var contactsGranted by remember { mutableStateOf(false) }
    var notificationsGranted by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Settings", onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(Spacing.md)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            AppSection("Alerts")
            if (!rememberNotificationsAllowed()) {
                Text(ALERTS_OFF, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                AppAction("Turn on alerts", { openNotificationSettings(context) })
            }
            SettingToggle("Possible deaths", "Tell me when an obituary may be about one of my contacts.", notifyDeath, viewModel::setNotifyDeath)
            SettingToggle("Possible arrests and custody", "Tell me when an arrest, court or jail record may be about one of my contacts.", notifyCustody, viewModel::setNotifyCustody)
            AppCaption("With an alert off, matches still appear in the app.")

            AppSection("Daily check")
            SettingLabel("Time of day", "The check runs around this time, when your phone is online and its battery isn't low.")
            val hours = linkedMapOf(6 to "6 AM", 9 to "9 AM", 12 to "Noon", 18 to "6 PM", 21 to "9 PM")
            AzRoller(
                options = hours.values.toList(),
                selectedOption = hours[checkHour] ?: "9 AM",
                onOptionSelected = { label -> viewModel.setCheckHour(hours.entries.first { it.value == label }.key) }
            )
            SettingLabel("Contact limit", "Only this many contacts, the ones added first, are checked or looked up.")
            val limits = linkedMapOf(0 to "No limit", 50 to "50", 100 to "100", 250 to "250", 500 to "500", 1000 to "1000")
            AzRoller(
                options = limits.values.toList(),
                selectedOption = limits[globalLimit] ?: globalLimit.toString(),
                onOptionSelected = { label -> viewModel.setGlobalTargetLimit(limits.entries.first { it.value == label }.key) }
            )

            AppSection("Import")
            AppCaption("Each opens the service in the app's browser, where you sign in if asked. Only names are read; messages are not.")
            ImportAction("Facebook friends", "Reads your friends list.", viewModel::harvestFacebook)
            ImportAction("WhatsApp chats", "Reads the names on your chat list. Scan the QR code with your phone.", viewModel::harvestWhatsApp)
            ImportAction("Instagram followers", "Reads the names of your followers.", viewModel::harvestInstagram)
            ImportAction("Google Contacts", "Reads your Google contact list.", viewModel::harvestGoogleContacts)
            AppTextAction("Add an account to this phone", {
                safeStart(context, Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                    putExtra(Settings.EXTRA_AUTHORITIES, arrayOf("com.android.contacts"))
                })
            })

            AppSection("Permissions")
            PermissionStatusItem("Contacts", contactsGranted)
            PermissionStatusItem("Notifications", notificationsGranted)
            AppTextAction("Change in system settings", {
                safeStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            })

            AppSection("Your data")
            SettingLabel("Sign out of sites", "Removes your sign-ins to Facebook, Google, Instagram, WhatsApp and CyberBackgroundChecks in the app's browser.")
            AppTextAction("Sign out of sites", viewModel::signOutOfSites)
            SettingLabel("Delete everything", "Deletes the app's contact list and history, the notes it added to your phone's contacts, and site sign-ins.")
            AppTextAction("Delete everything", { confirmDelete = true })

            AppSection("Appearance")
            SettingToggle("Dark theme", "Light grey on black. Off for black on white.", isDarkTheme, viewModel::setDarkTheme)
            SettingToggle("Activity log", "Shows what the app is doing, step by step, above your contacts. For troubleshooting.", showDiagnosticLog, viewModel::setShowDiagnosticLog)

            AppSection("Updates")
            SettingLabel("Version ${viewModel.installedVersion}", "Updates come from ${viewModel.updateChannel.label}.")
            val result = updateState.lastResult
            AppCaption(
                when {
                    updateState.checking -> "Checking…"
                    result is UpdateResult.Available -> "${result.version} is available on ${result.channel.label}."
                    result is UpdateResult.UpToDate -> "You're up to date."
                    result is UpdateResult.Failed -> "Couldn't check: ${result.reason}."
                    else -> "Not checked yet."
                }
            )
            if (result is UpdateResult.Available && !updateState.checking) {
                AppAction(if (result.channel == UpdateChannel.PLAY) "Open Google Play" else "Open the GitHub release", {
                    openUpdate(context, result, viewModel.playWebUrl())
                })
            } else {
                AppAction("Check for updates", { viewModel.checkForUpdates(manual = true) })
            }
        }
    }

    if (confirmDelete) {
        AppDialog(
            title = "Delete everything?",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; viewModel.deleteEverything() },
            onDismiss = { confirmDelete = false }
        ) {
            Text("The app's contact list, its merge and review history, the notes it added to your phone's contacts, and your site sign-ins are deleted. Your phone's contacts themselves stay. This can't be undone.")
        }
    }
}

@Composable
private fun SettingLabel(title: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        AppCaption(caption)
    }
}

@Composable
private fun SettingToggle(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(modifier = Modifier.weight(1f)) { SettingLabel(title, caption) }
        AzToggle(isChecked = checked, onToggle = onChange, toggleOnText = "On", toggleOffText = "Off")
    }
}

@Composable
private fun ImportAction(what: String, caption: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AppAction("Import $what", onClick)
        AppCaption(caption)
    }
}

private fun safeStart(context: android.content.Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        // Nothing on this device handles it.
    }
}

@Composable
fun PermissionStatusItem(label: String, isGranted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
            Text(
                text = if (isGranted) "Allowed" else "Not allowed",
                style = MaterialTheme.typography.labelMedium,
                color = if (isGranted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
        }
    }
}
