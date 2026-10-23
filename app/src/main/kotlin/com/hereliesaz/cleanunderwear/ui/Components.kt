package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.AzLoad
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.TargetStatus

/*
 * The app's few building blocks, so every screen draws a header, a card, a dialog, an action
 * and a status the same way. Screens use these instead of raw Material components.
 */

/** A screen's top bar: its title and a back arrow labelled for what back does there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)? = null, backLabel: String = "Back") {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
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
    AzButton(onClick = onClick, text = text, modifier = modifier.fillMaxWidth(), shape = AzButtonShape.RECTANGLE, enabled = enabled)
}

/** A secondary action: dismiss, cancel, an alternative. */
@Composable
fun AppTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AzButton(onClick = onClick, text = text, modifier = modifier, shape = AzButtonShape.NONE)
}

/**
 * A dialog: a title, what will happen, one action and a way out ("Cancel"). [confirm] says
 * what the action is ("Mark as deceased"), never just "OK".
 */
@Composable
fun AppDialog(
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
    body: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = body) },
        confirmButton = { AppTextAction(confirm, onConfirm) },
        dismissButton = { AppTextAction(dismiss, onDismiss) }
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
data class StatusSpec(val label: String, val icon: ImageVector, val needsUser: Boolean = false, val muted: Boolean = false)

/**
 * The single source of status wording. [matchKind] names what a possible match is about, so the
 * list never shows a bare "match" next to someone's name; [monitorability] tells a contact that
 * can only be checked by hand from one the daily check covers.
 */
fun statusSpec(status: TargetStatus, matchKind: String?, monitorability: MonitorabilityState?): StatusSpec = when (status) {
    TargetStatus.POSSIBLE_MATCH -> StatusSpec(
        when (matchKind) {
            "DEATH" -> "Possible obituary"
            "ARREST" -> "Possible arrest record"
            else -> "Possible custody record"
        },
        Icons.Filled.Warning, needsUser = true
    )
    TargetStatus.WEAK_MATCH -> StatusSpec("Weak match", Icons.Filled.Info)
    TargetStatus.DECEASED -> StatusSpec("Confirmed deceased", Icons.Filled.Done)
    TargetStatus.INCARCERATED -> StatusSpec("Confirmed in custody", Icons.Filled.Done)
    TargetStatus.IGNORED -> StatusSpec("Archived", Icons.Filled.Archive, muted = true)
    TargetStatus.UNVERIFIED -> StatusSpec("Needs a full name", Icons.Filled.Person, muted = true)
    TargetStatus.UNKNOWN -> StatusSpec("Not checked yet", Icons.Filled.HourglassEmpty, muted = true)
    TargetStatus.MONITORING -> when (monitorability) {
        MonitorabilityState.NO_AUTOMATED_SOURCE -> StatusSpec("Manual checks only", Icons.Filled.Search, muted = true)
        MonitorabilityState.NEEDS_ENRICHMENT, MonitorabilityState.ENRICHMENT_FAILED ->
            StatusSpec("Needs more details", Icons.Filled.Person, muted = true)
        else -> StatusSpec("Checked daily", Icons.Filled.Check, muted = true)
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
    Surface(
        modifier = modifier.semantics { contentDescription = "Status: ${spec.label}" },
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
            Text(spec.label, style = MaterialTheme.typography.labelMedium, color = fg)
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
