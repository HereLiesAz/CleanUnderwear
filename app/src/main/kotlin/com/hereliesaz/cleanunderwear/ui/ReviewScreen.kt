package com.hereliesaz.cleanunderwear.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.cleanunderwear.domain.AlternateInfo
import com.hereliesaz.cleanunderwear.domain.Evidence
import com.hereliesaz.cleanunderwear.domain.IdentityLine
import com.hereliesaz.cleanunderwear.domain.Lean
import com.hereliesaz.cleanunderwear.domain.SuspectCase
import com.hereliesaz.cleanunderwear.domain.SuspicionKind
import kotlin.math.roundToInt

/**
 * The correction step. Lists contacts that look wrongly merged or carry a stale number. Each card
 * collapses to a verdict and expands to the evidence on both sides: why it looks wrong, and why it
 * might be fine. The verdict is the user's; the buttons only act on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.reviewState.collectAsState()
    val alternates by viewModel.alternates.collectAsState()
    var kindFilter by rememberSaveable { mutableStateOf<SuspicionKind?>(null) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }

    val historyPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshSuspects() }

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
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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

            if (report != null && !report.historyAvailable) {
                item {
                    HistoryBanner(onAllow = {
                        historyPermissions.launch(
                            arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS)
                        )
                    })
                }
            }

            if (state.loading && report == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (total == 0) "Nothing to review" else "$total to review",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Tap a contact to see why it looks wrong, and why it might not be.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == SuspicionKind.BAD_MERGE,
                onClick = { onFilter(SuspicionKind.BAD_MERGE) },
                label = { Text("Bad merges ($merges)") }
            )
            FilterChip(
                selected = filter == SuspicionKind.STALE_NUMBER,
                onClick = { onFilter(SuspicionKind.STALE_NUMBER) },
                label = { Text("Stale numbers ($stale)") }
            )
        }
    }
}

@Composable
private fun HistoryBanner(onAllow: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Call and text history is off", fontWeight = FontWeight.Bold)
            Text(
                "Whether you were in touch with two numbers at the same time is the best way to tell " +
                    "a bad merge from a changed number. It's read on this device, only as counts and " +
                    "first/last dates, and never stored or sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onAllow) { Text("Allow access") }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (filtered) "Nothing in this group." else "Nothing looks wrong.",
            style = MaterialTheme.typography.titleMedium
        )
        if (!filtered) {
            Text(
                "Merges are recorded from now on. Ones made by earlier versions weren't, so they can't " +
                    "be reviewed or undone; stale numbers are checked across everything.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuspectCard(
    case: SuspectCase,
    expanded: Boolean,
    onToggle: () -> Unit,
    viewModel: MainViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.clickable(onClick = onToggle).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(case.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when (case.kind) {
                            SuspicionKind.BAD_MERGE -> "Possible bad merge"
                            SuspicionKind.STALE_NUMBER -> "Possible stale number"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LeanTag(case)
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
            }
            Text(case.headline, style = MaterialTheme.typography.bodyMedium)
            BalanceBar(case.suspicionScore, case.defenseScore)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${case.suspicion.size} against",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarningRed
                )
                Text(
                    "${case.defense.size} in its favour",
                    style = MaterialTheme.typography.labelSmall,
                    color = VerifiedGreen
                )
            }
        }

        if (expanded) {
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                IdentityComparison(case.identities)
                EvidenceList(
                    title = "Why it looks wrong",
                    items = case.suspicion,
                    color = WarningRed,
                    emptyText = "Nothing."
                )
                EvidenceList(
                    title = "Why it might be fine",
                    items = case.defense,
                    color = VerifiedGreen,
                    emptyText = "Nothing speaks for it."
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (case.kind) {
                        SuspicionKind.BAD_MERGE -> {
                            Button(onClick = { viewModel.splitMerge(case) }) { Text("Split them") }
                            OutlinedButton(onClick = { viewModel.keepMerge(case) }) { Text("Keep merged") }
                        }
                        SuspicionKind.STALE_NUMBER -> {
                            case.replacementPhone?.let {
                                Button(onClick = { viewModel.adoptReplacement(case) }) { Text("Use $it") }
                            }
                            OutlinedButton(onClick = { viewModel.markStale(case) }) { Text("Mark stale") }
                            TextButton(onClick = {
                                viewModel.findAlternates(case.targetId, case.identities.firstOrNull()?.phone)
                            }) { Text("Look up alternates") }
                            TextButton(onClick = { viewModel.dismissSuspect(case) }) { Text("Number is fine") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeanTag(case: SuspectCase) {
    val problem = if (case.kind == SuspicionKind.BAD_MERGE) "Likely wrong" else "Likely stale"
    val label = when (case.lean) {
        Lean.LIKELY_PROBLEM -> problem
        Lean.TOSS_UP -> "Toss-up"
        Lean.LIKELY_FINE -> "Probably fine"
    }
    val color = when (case.lean) {
        Lean.LIKELY_PROBLEM -> WarningRed
        Lean.TOSS_UP -> IntelligenceGold
        Lean.LIKELY_FINE -> VerifiedGreen
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun BalanceBar(suspicion: Double, defense: Double) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
    ) {
        Box(Modifier.weight(suspicion.coerceAtLeast(0.1).toFloat()).fillMaxHeight().background(WarningRed))
        Box(Modifier.weight(defense.coerceAtLeast(0.1).toFloat()).fillMaxHeight().background(VerifiedGreen))
    }
}

@Composable
private fun IdentityComparison(identities: List<IdentityLine>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        identities.forEach { line ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    line.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(line.name ?: "—", fontWeight = FontWeight.Bold)
                Text(line.phone ?: "no number", style = MaterialTheme.typography.bodyMedium)
                line.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                line.history?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceList(title: String, items: List<Evidence>, color: Color, emptyText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // One to four dots: how much the reason counts.
                Text("●".repeat(e.weight.roundToInt().coerceIn(1, 4)), color = color, style = MaterialTheme.typography.labelSmall)
                Text(e.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlternatesDialog(info: AlternateInfo, onApply: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alternate info for ${info.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (info.stalePhone != null && info.holderName != null) {
                    Text(
                        if (info.holderDiffers) {
                            "${info.stalePhone} now comes back to \"${info.holderName}\", a different person. The number was likely recycled."
                        } else {
                            "${info.stalePhone} still comes back to \"${info.holderName}\", which fits this contact. It may not be stale after all."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                info.phone?.let { Text("Possible current number: $it", fontWeight = FontWeight.Bold) }
                info.address?.let { Text("Possible address: $it", fontWeight = FontWeight.Bold) }
                if (!info.hasReplacement) {
                    Text("The lookup found no verified replacement.", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    info.basis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (info.hasReplacement) TextButton(onClick = onApply) { Text("Use these") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (info.hasReplacement) TextButton(onClick = onDismiss) { Text("Not now") }
        }
    )
}
