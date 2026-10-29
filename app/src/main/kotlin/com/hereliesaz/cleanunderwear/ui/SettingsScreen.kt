package com.hereliesaz.cleanunderwear.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import com.hereliesaz.aznavrail.AzRoller
import com.hereliesaz.aznavrail.AzToggle
import com.hereliesaz.cleanunderwear.R
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
    var confirmSignOut by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val noteWriteBack by viewModel.noteWriteBack.collectAsState()
    val bottomBar = LocalBottomBarHeight.current

    // One source for "can the app alert / read contacts", read at once and again whenever the
    // screen comes back (e.g. from system settings): the Alerts section and the Permissions rows
    // used to read it two ways and could disagree.
    val contactsGranted = rememberContactsAllowed()
    val notificationsGranted = rememberNotificationsAllowed()

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.settings_title), onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = screenInsets()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .contentWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            AppSection(stringResource(R.string.settings_section_alerts))
            if (!notificationsGranted) {
                Text(stringResource(R.string.alerts_off), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                AppAction(stringResource(R.string.turn_on_alerts), { openNotificationSettings(context) })
            }
            SettingToggle(stringResource(R.string.settings_alert_death), stringResource(R.string.settings_alert_death_caption), notifyDeath, viewModel::setNotifyDeath)
            SettingToggle(stringResource(R.string.settings_alert_custody), stringResource(R.string.settings_alert_custody_caption), notifyCustody, viewModel::setNotifyCustody)
            AppCaption(stringResource(R.string.settings_alert_off_note))

            AppSection(stringResource(R.string.settings_section_daily))
            SettingLabel(stringResource(R.string.settings_time_of_day), stringResource(R.string.settings_time_of_day_caption))
            val hours = linkedMapOf(
                6 to stringResource(R.string.hour_6am),
                9 to stringResource(R.string.hour_9am),
                12 to stringResource(R.string.hour_noon),
                18 to stringResource(R.string.hour_6pm),
                21 to stringResource(R.string.hour_9pm)
            )
            AzRoller(
                options = hours.values.toList(),
                selectedOption = hours[checkHour] ?: hours.getValue(9),
                onOptionSelected = { label -> viewModel.setCheckHour(hours.entries.first { it.value == label }.key) }
            )
            SettingLabel(stringResource(R.string.settings_limit), stringResource(R.string.settings_limit_caption))
            val limits = linkedMapOf(0 to stringResource(R.string.settings_no_limit), 50 to "50", 100 to "100", 250 to "250", 500 to "500", 1000 to "1000")
            AzRoller(
                options = limits.values.toList(),
                selectedOption = limits[globalLimit] ?: globalLimit.toString(),
                onOptionSelected = { label -> viewModel.setGlobalTargetLimit(limits.entries.first { it.value == label }.key) }
            )

            AppSection(stringResource(R.string.settings_section_import))
            AppCaption(stringResource(R.string.settings_import_caption))
            ImportAction(stringResource(R.string.settings_import_facebook), stringResource(R.string.settings_import_facebook_caption), viewModel::harvestFacebook)
            ImportAction(stringResource(R.string.settings_import_whatsapp), stringResource(R.string.settings_import_whatsapp_caption), viewModel::harvestWhatsApp)
            ImportAction(stringResource(R.string.settings_import_instagram), stringResource(R.string.settings_import_instagram_caption), viewModel::harvestInstagram)
            ImportAction(stringResource(R.string.settings_import_google), stringResource(R.string.settings_import_google_caption), viewModel::harvestGoogleContacts)
            AppTextAction(stringResource(R.string.settings_add_account), {
                safeStart(context, Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                    putExtra(Settings.EXTRA_AUTHORITIES, arrayOf("com.android.contacts"))
                })
            })

            AppSection(stringResource(R.string.settings_section_permissions))
            PermissionStatusItem(stringResource(R.string.settings_perm_contacts), contactsGranted)
            PermissionStatusItem(stringResource(R.string.settings_perm_notifications), notificationsGranted)
            AppTextAction(stringResource(R.string.settings_change_in_system), {
                safeStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            })

            AppSection(stringResource(R.string.settings_section_data))
            SettingToggle(stringResource(R.string.settings_note_write_back), stringResource(R.string.settings_note_write_back_caption), noteWriteBack, viewModel::setNoteWriteBack)
            // The label says what is kept; the button says what it does, so neither repeats the other.
            SettingLabel(stringResource(R.string.settings_site_signins), stringResource(R.string.settings_sign_out_caption))
            AppTextAction(stringResource(R.string.settings_sign_out), { confirmSignOut = true })
            SettingLabel(stringResource(R.string.settings_app_data), stringResource(R.string.settings_delete_caption))
            AppTextAction(stringResource(R.string.settings_delete), { confirmDelete = true })

            AppSection(stringResource(R.string.settings_section_appearance))
            SettingToggle(stringResource(R.string.settings_dark_theme), stringResource(R.string.settings_dark_theme_caption), isDarkTheme, viewModel::setDarkTheme)
            SettingToggle(stringResource(R.string.settings_activity_log), stringResource(R.string.settings_activity_log_caption), showDiagnosticLog, viewModel::setShowDiagnosticLog)

            AppSection(stringResource(R.string.settings_section_updates))
            SettingLabel(
                stringResource(R.string.settings_version, viewModel.installedVersion),
                stringResource(R.string.settings_updates_from, viewModel.updateChannel.label)
            )
            val result = updateState.lastResult
            AppCaption(
                when {
                    updateState.checking -> stringResource(R.string.settings_checking)
                    result is UpdateResult.Available -> stringResource(R.string.settings_update_available, result.version, result.channel.label)
                    result is UpdateResult.UpToDate -> stringResource(R.string.settings_up_to_date)
                    result is UpdateResult.Failed -> stringResource(R.string.settings_check_failed, result.reason)
                    else -> stringResource(R.string.settings_not_checked)
                }
            )
            if (result is UpdateResult.Available && !updateState.checking) {
                AppAction(stringResource(if (result.channel == UpdateChannel.PLAY) R.string.settings_open_play else R.string.settings_open_release), {
                    openUpdate(context, result, viewModel.playWebUrl())
                })
            } else {
                AppAction(stringResource(R.string.settings_check_updates), { viewModel.checkForUpdates(manual = true) })
            }

            AppSection(stringResource(R.string.settings_section_privacy))
            AppCaption(stringResource(R.string.settings_privacy_caption))
            AppTextAction(stringResource(R.string.settings_privacy_policy), {
                safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
            })
            // Clear of the bar of an operation put aside.
            Spacer(Modifier.height(bottomBar))
        }
    }

    if (confirmSignOut) {
        AppDialog(
            title = stringResource(R.string.settings_sign_out_title),
            confirm = stringResource(R.string.settings_sign_out_confirm),
            onConfirm = { confirmSignOut = false; viewModel.signOutOfSites() },
            onDismiss = { confirmSignOut = false }
        ) {
            Text(stringResource(R.string.settings_sign_out_body))
        }
    }

    if (confirmDelete) {
        AppDialog(
            title = stringResource(R.string.settings_delete_title),
            confirm = stringResource(R.string.settings_delete_confirm),
            onConfirm = { confirmDelete = false; viewModel.deleteEverything() },
            onDismiss = { confirmDelete = false }
        ) {
            Text(stringResource(R.string.settings_delete_body))
        }
    }
}

/** Where the privacy policy is published: the repository's copy of docs/PRIVACY_POLICY.md. */
private const val PRIVACY_POLICY_URL = "https://github.com/HereLiesAz/CleanUnderwear/blob/main/docs/PRIVACY_POLICY.md"

@Composable
private fun SettingLabel(title: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        AppCaption(caption)
    }
}

/**
 * One switch, as one control: the whole row toggles, and TalkBack reads the title, the caption
 * and the state together as a single switch. The AzToggle's own semantics are hidden so it is not
 * announced a second time; touching it still toggles.
 */
@Composable
private fun SettingToggle(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Column(modifier = Modifier.weight(1f)) { SettingLabel(title, caption) }
        Box(Modifier.clearAndSetSemantics { }) {
            AzToggle(
                isChecked = checked,
                onToggle = onChange,
                toggleOnText = stringResource(R.string.on),
                toggleOffText = stringResource(R.string.off)
            )
        }
    }
}

@Composable
private fun ImportAction(label: String, caption: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AppAction(label, onClick)
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
        // One announcement: "Contacts, Allowed".
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
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
                text = stringResource(if (isGranted) R.string.settings_allowed else R.string.settings_not_allowed),
                style = MaterialTheme.typography.labelMedium,
                color = if (isGranted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
        }
    }
}
