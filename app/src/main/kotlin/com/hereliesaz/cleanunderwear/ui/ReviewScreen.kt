package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzLoad
import com.hereliesaz.cleanunderwear.domain.AlternateInfo
import com.hereliesaz.cleanunderwear.domain.Evidence
import com.hereliesaz.cleanunderwear.domain.IdentityLine
import com.hereliesaz.cleanunderwear.domain.Lean
import com.hereliesaz.cleanunderwear.domain.SuspectCase
import com.hereliesaz.cleanunderwear.domain.SuspicionKind

/**
 * The correction step. Lists contacts that look wrongly combined or carry an out-of-date number.
 * Each card collapses to a verdict and expands to the evidence on both sides: why it looks wrong,
 * and why it might be fine. The verdict is the user's; the buttons only act on it, and say first
 * what they will do. Only this app's list changes; the phone's own contacts are never edited here.
 */
@Composable
fun ReviewScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.reviewState.collectAsState()
    val alternates by viewModel.alternates.collectAsState()
    var kindFilter by rememberSaveable { mutableStateOf<SuspicionKind?>(null) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshSuspects() }

    alternates?.let { info ->
        AlternatesDialog(
            info = info,
            onApply = { viewModel.applyAlternates(info) },
            onDismiss = { viewModel.dismissAlternates() }
        )
    }

    val report = state.report
    val cases = report?.cases.orEmpty().filter { kindFilter == null || it.kind == kindFilter }

    Scaffold(
        topBar = { AppTopBar(title = "Review", onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                Header(
                    total = report?.cases?.size ?: 0,
                    merges = report?.cases?.count { it.kind == SuspicionKind.BAD_MERGE } ?: 0,
                    stale = report?.cases?.count { it.kind == SuspicionKind.STALE_NUMBER } ?: 0,
                    filter = kindFilter,
                    onFilter = { kindFilter = if (kindFilter == it) null else it }
                )
            }

            if (state.loading && report == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        LoadingCaption("Looking for contacts that may be wrong…")
                    }
                }
            } else if (cases.isEmpty()) {
                item { EmptyState(filtered = report?.cases?.isNotEmpty() == true) }
            }

            items(cases, key = { it.key }) { case ->
                SuspectCard(
                    case = case,
                    expanded = expandedKey == case.key,
                    onToggle = { expandedKey = if (expandedKey == case.key) null else case.key },
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun Header(
    total: Int,
    merges: Int,
    stale: Int,
    filter: SuspicionKind?,
    onFilter: (SuspicionKind) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(if (total == 0) "Nothing to review" else "$total to review", style = MaterialTheme.typography.titleLarge)
        AppCaption(
            "When the app finds two entries for one person it combines them. Here are the ones that may " +
                "be two different people, or a number that's out of date. Tap one to see why, and why it might be fine."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = filter == SuspicionKind.BAD_MERGE,
                onClick = { onFilter(SuspicionKind.BAD_MERGE) },
                label = { Text("Combined wrongly ($merges)") }
            )
            FilterChip(
                selected = filter == SuspicionKind.STALE_NUMBER,
                onClick = { onFilter(SuspicionKind.STALE_NUMBER) },
                label = { Text("Old numbers ($stale)") }
            )
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(if (filtered) "Nothing in this group." else "Nothing looks wrong.", style = MaterialTheme.typography.titleMedium)
        if (!filtered) {
            AppCaption(
                "Combined entries are recorded from now on. Ones combined by earlier versions weren't, so " +
                    "they can't be reviewed or separated; old numbers are checked across everything."
            )
        }
    }
}

private enum class Pending { SPLIT, KEEP, ADOPT, STALE, LOOK_UP, FINE }

@Composable
private fun SuspectCard(
    case: SuspectCase,
    expanded: Boolean,
    onToggle: () -> Unit,
    viewModel: MainViewModel
) {
    var pending by remember { mutableStateOf<Pending?>(null) }
    AppCard(emphasis = case.lean == Lean.LIKELY_PROBLEM) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle, role = Role.Button, onClickLabel = if (expanded) "Hide the reasons" else "Show the reasons")
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(case.title, style = MaterialTheme.typography.titleMedium)
                    AppCaption(
                        when (case.kind) {
                            SuspicionKind.BAD_MERGE -> "May be two people combined"
                            SuspicionKind.STALE_NUMBER -> "Number may be out of date"
                        }
                    )
                }
                LeanTag(case)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            Text(case.headline, style = MaterialTheme.typography.bodyMedium)
            BalanceBar(case.suspicion.size, case.defense.size, case.suspicionScore, case.defenseScore)
        }

        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IdentityComparison(case.identities)
            EvidenceList("Why it looks wrong", case.suspicion, Icons.Filled.Warning, "Nothing.")
            EvidenceList("Why it might be fine", case.defense, Icons.Filled.Info, "Nothing speaks for it.")
            when (case.kind) {
                SuspicionKind.BAD_MERGE -> {
                    AppAction("Separate them", { pending = Pending.SPLIT })
                    AppTextAction("They're the same person", { pending = Pending.KEEP })
                }
                SuspicionKind.STALE_NUMBER -> {
                    case.replacementPhone?.let { AppAction("Use $it instead", { pending = Pending.ADOPT }) }
                    AppAction("Mark the number out of date", { pending = Pending.STALE })
                    AppTextAction("Look for a current number", { pending = Pending.LOOK_UP })
                    AppTextAction("The number is fine", { pending = Pending.FINE })
                }
            }
        }
    }

    val name = case.title
    val oldNumber = case.identities.firstOrNull()?.phone
    when (pending) {
        Pending.SPLIT -> AppDialog("Separate these entries?", "Separate", { pending = null; viewModel.splitMerge(case) }, { pending = null }) {
            Text("The entry that was combined into $name comes back as its own contact in this app, and the two won't be combined again. Your phone's contacts aren't changed.")
        }
        Pending.KEEP -> AppDialog("Keep them combined?", "Keep combined", { pending = null; viewModel.keepMerge(case) }, { pending = null }) {
            Text("$name stays one contact and won't be raised here again.")
        }
        Pending.ADOPT -> AppDialog("Use ${case.replacementPhone}?", "Use it", { pending = null; viewModel.adoptReplacement(case) }, { pending = null }) {
            Text("$name's number in this app becomes ${case.replacementPhone}. Your phone's contacts aren't changed.")
        }
        Pending.STALE -> AppDialog("Mark ${oldNumber ?: "the number"} out of date?", "Mark out of date", { pending = null; viewModel.markStale(case) }, { pending = null }) {
            Text("It won't be used to check or look up $name. The app then searches CyberBackgroundChecks for a current number, which sends $name's name there.")
        }
        Pending.LOOK_UP -> AppDialog("Look for a current number?", "Look it up", { pending = null; viewModel.findAlternates(case.targetId, oldNumber) }, { pending = null }) {
            Text("CyberBackgroundChecks, a people-search site, is searched with $name's name and ${oldNumber ?: "number"}. You may be asked to solve a CAPTCHA.")
        }
        Pending.FINE -> AppDialog("Keep the number?", "Keep it", { pending = null; viewModel.dismissSuspect(case) }, { pending = null }) {
            Text("${oldNumber ?: "The number"} stays in use for $name and won't be raised here again.")
        }
        null -> Unit
    }
}

@Composable
private fun LeanTag(case: SuspectCase) {
    val problem = if (case.kind == SuspicionKind.BAD_MERGE) "Likely two people" else "Likely out of date"
    val spec = when (case.lean) {
        Lean.LIKELY_PROBLEM -> StatusSpec(problem, Icons.Filled.Warning, needsUser = true)
        Lean.TOSS_UP -> StatusSpec("Could go either way", Icons.Filled.Info)
        Lean.LIKELY_FINE -> StatusSpec("Probably fine", Icons.Filled.Info, muted = true)
    }
    StatusBadge(spec, Modifier.padding(end = Spacing.xs))
}

/** The weight of the reasons against (solid) and for (outlined), with the counts in words. */
@Composable
private fun BalanceBar(against: Int, inFavour: Int, suspicion: Double, defense: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .semantics { contentDescription = "$against reasons against, $inFavour in its favour" }
        ) {
            Box(Modifier.weight(suspicion.coerceAtLeast(0.1).toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
            Box(
                Modifier.weight(defense.coerceAtLeast(0.1).toFloat()).fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            AppCaption("$against against")
            AppCaption("$inFavour in its favour")
        }
    }
}

@Composable
private fun IdentityComparison(identities: List<IdentityLine>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
        identities.forEach { line ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line.name ?: "—", style = MaterialTheme.typography.titleMedium)
                Text(line.phone ?: "no number", style = MaterialTheme.typography.bodyMedium)
                line.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                line.history?.let { AppCaption(it) }
            }
        }
    }
}

@Composable
private fun EvidenceList(title: String, items: List<Evidence>, icon: androidx.compose.ui.graphics.vector.ImageVector, emptyText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 2.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (items.isEmpty()) AppCaption(emptyText)
        items.forEach { e ->
            Column {
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
                AppCaption(
                    when {
                        e.weight >= 2.5 -> "Counts strongly"
                        e.weight >= 1.5 -> "Counts"
                        else -> "Counts a little"
                    }
                )
            }
        }
    }
}

@Composable
private fun AlternatesDialog(info: AlternateInfo, onApply: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        title = "What the lookup found for ${info.name}",
        confirm = if (info.hasReplacement) "Use these" else "Close",
        onConfirm = if (info.hasReplacement) onApply else onDismiss,
        onDismiss = onDismiss,
        dismiss = if (info.hasReplacement) "Cancel" else "Close"
    ) {
        if (info.stalePhone != null && info.holderName != null) {
            Text(
                if (info.holderDiffers) {
                    "${info.stalePhone} now belongs to \"${info.holderName}\", someone else. The number was likely given to a new person."
                } else {
                    "${info.stalePhone} still belongs to \"${info.holderName}\", which fits. It may not be out of date after all."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        info.phone?.let { Text("Possible current number: $it", style = MaterialTheme.typography.titleMedium) }
        info.address?.let { Text("Possible address: $it", style = MaterialTheme.typography.titleMedium) }
        if (!info.hasReplacement) Text("Nothing found that could be confirmed as ${info.name}.", style = MaterialTheme.typography.bodyMedium)
        AppCaption(info.basis)
    }
}
