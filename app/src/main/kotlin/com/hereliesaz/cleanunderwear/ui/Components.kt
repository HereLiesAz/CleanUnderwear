package com.hereliesaz.cleanunderwear.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.AzLoad
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.TargetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The app's few building blocks, so every screen draws a header, a card, a dialog, an action
 * and a status the same way. Screens use these instead of raw Material components.
 */

/** The widest a screen's content grows. Beyond it (a tablet, a phone on its side) the content is centred. */
val ContentMaxWidth = 640.dp

/**
 * Fills the width up to [ContentMaxWidth] and centres there, so lines don't stretch across a wide
 * screen. Put it ahead of any scrolling or padding the content does.
 */
fun Modifier.contentWidth(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth)

/**
 * The height of what the app draws over the bottom of every screen (the bar of an operation put
 * aside), so a list can end above it and the snackbar can sit on top of it. Zero when there is none.
 */
val LocalBottomBarHeight = compositionLocalOf { 0.dp }

/**
 * Shows a one-line message, with an optional action ("Undo"), in the app's snackbar from any
 * screen. It runs in the app's own scope, so the message outlives the screen that raised it: Undo
 * stays available after Back. A new message replaces the one showing.
 */
class AppSnackbars(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/** The app's [AppSnackbars]; null where no shell provides one (a preview, a test). */
val LocalAppSnackbars = staticCompositionLocalOf<AppSnackbars?> { null }

/**
 * The insets a screen's Scaffold keeps its content out of: the system bars (Scaffold's own
 * default) and a camera cut-out, which sits beside the content when the phone is on its side.
 */
@Composable
fun screenInsets(): WindowInsets =
    androidx.compose.material3.ScaffoldDefaults.contentWindowInsets.union(WindowInsets.displayCutout)

/** A screen's top bar: its title and a back arrow labelled for what back does there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)? = null, backLabel: String = stringResource(R.string.back)) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        // A camera cut-out beside the bar (a phone on its side) must not cover the back arrow.
        windowInsets = TopAppBarDefaults.windowInsets.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/** A section header inside a screen. */
@Composable
fun AppSection(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.sm)
    )
}

/** A caption under a setting or an action: what it does, in one line. */
@Composable
fun AppCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A card. [emphasis] outlines it, for the one thing on a screen that needs the user. */
@Composable
fun AppCard(modifier: Modifier = Modifier, emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (emphasis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
    }
}

/** The main action of a card or screen. */
@Composable
fun AppAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    // minimumInteractiveComponentSize keeps the touch target at 48 dp whatever the button draws.
    AzButton(
        onClick = onClick,
        text = text,
        modifier = modifier.minimumInteractiveComponentSize().fillMaxWidth(),
        shape = AzButtonShape.RECTANGLE,
        enabled = enabled
    )
}

/** A secondary action: dismiss, cancel, an alternative. */
@Composable
fun AppTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AzButton(onClick = onClick, text = text, modifier = modifier.minimumInteractiveComponentSize(), shape = AzButtonShape.NONE)
}

/**
 * A dialog: a title, what will happen, one action and a way out ("Cancel"). [confirm] says
 * what the action is ("Mark as deceased"), never just "OK". A dialog that only informs passes
 * `dismiss = null`, so it has the one button instead of two that do the same. The body scrolls,
 * so it still reads whole at a large font size, on its side, or with the keyboard up.
 */
@Composable
fun AppDialog(
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String? = stringResource(R.string.cancel),
    body: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                content = body
            )
        },
        confirmButton = { AppTextAction(confirm, onConfirm) },
        dismissButton = if (dismiss != null) {
            { AppTextAction(dismiss, onDismiss) }
        } else null
    )
}

/** The one busy indicator: AzLoad with what is happening under it. */
@Composable
fun LoadingCaption(text: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AzLoad()
        if (!text.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.xl)
            )
        }
    }
}

/** How a status looks wherever it appears: its words, its icon, and whether it needs the user. */
data class StatusSpec(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val needsUser: Boolean = false,
    val muted: Boolean = false
)

/**
 * The single source of status wording (the labels are string resources, resolved where the badge
 * is drawn). [matchKind] names what a possible match is about, so the list never shows a bare
 * "match" next to someone's name; [monitorability] tells a contact that can only be checked by
 * hand from one the daily check covers.
 */
fun statusSpec(status: TargetStatus, matchKind: String?, monitorability: MonitorabilityState?): StatusSpec = when (status) {
    TargetStatus.POSSIBLE_MATCH -> StatusSpec(
        when (matchKind) {
            "DEATH" -> R.string.status_possible_obituary
            "ARREST" -> R.string.status_possible_arrest
            else -> R.string.status_possible_custody
        },
        Icons.Filled.Warning, needsUser = true
    )
    TargetStatus.WEAK_MATCH -> StatusSpec(R.string.status_weak_match, Icons.Filled.Info)
    TargetStatus.DECEASED -> StatusSpec(R.string.status_confirmed_deceased, Icons.Filled.Done)
    TargetStatus.INCARCERATED -> StatusSpec(R.string.status_confirmed_in_custody, Icons.Filled.Done)
    TargetStatus.IGNORED -> StatusSpec(R.string.status_archived, Icons.Filled.Archive, muted = true)
    TargetStatus.UNVERIFIED -> StatusSpec(R.string.status_needs_full_name, Icons.Filled.Person, muted = true)
    TargetStatus.UNKNOWN -> StatusSpec(R.string.status_not_checked, Icons.Filled.HourglassEmpty, muted = true)
    TargetStatus.MONITORING -> when (monitorability) {
        MonitorabilityState.NO_AUTOMATED_SOURCE -> StatusSpec(R.string.status_manual_only, Icons.Filled.Search, muted = true)
        MonitorabilityState.NEEDS_ENRICHMENT, MonitorabilityState.ENRICHMENT_FAILED ->
            StatusSpec(R.string.status_needs_details, Icons.Filled.Person, muted = true)
        else -> StatusSpec(R.string.status_checked_daily, Icons.Filled.Check, muted = true)
    }
}

/**
 * A status as a small label with an icon. Something needing the user is filled in the alert
 * colour; everything else is an outline, so status never rests on hue alone.
 */
@Composable
fun StatusBadge(spec: StatusSpec, modifier: Modifier = Modifier) {
    val alert = MaterialTheme.colorScheme.error
    val fg = when {
        spec.needsUser -> MaterialTheme.colorScheme.onError
        spec.muted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = stringResource(spec.labelRes)
    val description = stringResource(R.string.status_description, label)
    Surface(
        // One announcement: the description replaces the label text inside, which would
        // otherwise be read a second time.
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = if (spec.needsUser) alert else Color.Transparent,
        border = if (spec.needsUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(spec.icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

/** A label above a value, for a contact's details. */
@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Whether this app may show notifications now: the Android 13+ permission and the app's own
 * notification switch in system settings. Re-read whenever the screen comes back (from settings).
 */
@Composable
fun rememberNotificationsAllowed(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun read() = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    val allowed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(read()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { allowed.value = read() }
    }
    return allowed.value
}

/**
 * Whether the app may read contacts now. Read at once (so a granted permission never shows as
 * missing for a frame) and again whenever the screen comes back, e.g. from system settings.
 */
@Composable
fun rememberContactsAllowed(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun read() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val allowed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(read()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { allowed.value = read() }
    }
    return allowed.value
}

/** Opens this app's notification settings, where alerts can be allowed whatever was answered before. */
fun openNotificationSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
        )
    }
}

