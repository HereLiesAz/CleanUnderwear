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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzLoad
import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.domain.AlternateInfo
import com.hereliesaz.cleanunderwear.domain.Evidence
import com.hereliesaz.cleanunderwear.domain.IdentityLine
import com.hereliesaz.cleanunderwear.domain.Lean
import com.hereliesaz.cleanunderwear.domain.Recommendation
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
    var confirmAll by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

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

    if (confirmAll) {
        ApplyAllDialog(
            cases = cases,
            onConfirm = { confirmAll = false; viewModel.applyRecommendations(cases) },
            onDismiss = { confirmAll = false }
        )
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.review_title), onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = screenInsets()
    ) { padding ->
        val bottomBar = LocalBottomBarHeight.current
        LazyColumn(
            modifier = Modifier.padding(padding).contentWidth().fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.md, top = Spacing.md, end = Spacing.md, bottom = Spacing.md + bottomBar
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                Header(
                    loading = state.loading,
                    total = report?.cases?.size ?: 0,
                    merges = report?.cases?.count { it.kind == SuspicionKind.BAD_MERGE } ?: 0,
                    stale = report?.cases?.count { it.kind == SuspicionKind.STALE_NUMBER } ?: 0,
                    filter = kindFilter,
                    onFilter = { kindFilter = if (kindFilter == it) null else it },
                    recommended = cases.count { it.recommendation != null },
                    onApplyAll = { confirmAll = true }
                )
            }

            if (state.loading && report == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        LoadingCaption(stringResource(R.string.review_looking_for_wrong))
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
    loading: Boolean,
    total: Int,
    merges: Int,
    stale: Int,
    filter: SuspicionKind?,
    onFilter: (SuspicionKind) -> Unit,
    recommended: Int,
    onApplyAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            when {
                loading && total == 0 -> stringResource(R.string.review_looking)
                total == 0 -> stringResource(R.string.review_nothing)
                else -> pluralStringResource(R.plurals.review_to_review, total, total)
            },
            style = MaterialTheme.typography.titleLarge
        )
        // A rescan after a decision keeps the last list on screen; say it's being refreshed.
        if (loading && total > 0) LoadingCaption(stringResource(R.string.review_checking_again))
        AppCaption(stringResource(R.string.review_intro))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = filter == SuspicionKind.BAD_MERGE,
                onClick = { onFilter(SuspicionKind.BAD_MERGE) },
                label = { Text(stringResource(R.string.review_filter_merged, merges)) }
            )
            FilterChip(
                selected = filter == SuspicionKind.STALE_NUMBER,
                onClick = { onFilter(SuspicionKind.STALE_NUMBER) },
                label = { Text(stringResource(R.string.review_filter_old, stale)) }
            )
        }
        if (recommended > 0) {
            AppAction(stringResource(R.string.review_apply_recommendations, recommended), onApplyAll)
            AppCaption(stringResource(if (filter != null) R.string.review_apply_caption_group else R.string.review_apply_caption))
        }
    }
}

/** The confirmation before applying every recommendation: exactly what will happen, by kind. */
@Composable
private fun ApplyAllDialog(cases: List<SuspectCase>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val byRec = cases.mapNotNull { it.recommendation }.groupingBy { it }.eachCount()
    val left = cases.count { it.recommendation == null }
    val total = byRec.values.sum()
    AppDialog(
        title = pluralStringResource(R.plurals.review_apply_title, total, total),
        confirm = stringResource(R.string.review_apply),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    ) {
        Recommendation.entries.forEach { rec ->
            byRec[rec]?.let { n -> Text(stringResource(R.string.review_rec_count, rec.label, n), style = MaterialTheme.typography.bodyMedium) }
            byRec[rec]?.let { AppCaption(stringResource(whatItDoes(rec, bulk = true))) }
        }
        if (left > 0) AppCaption(pluralStringResource(R.plurals.review_left_for_you, left, left))
        AppCaption(stringResource(R.string.review_apply_note))
    }
}

/**
 * One line on what a recommendation does: on its card, or for [bulk] in the Apply-all
 * confirmation. They differ for one action: marking a number out of date on a card also
 * searches for a current one; in bulk it doesn't (that search needs the browser, contact by contact).
 */
@androidx.annotation.StringRes
private fun whatItDoes(rec: Recommendation, bulk: Boolean = false): Int = when (rec) {
    Recommendation.SEPARATE -> R.string.review_does_separate
    Recommendation.KEEP_COMBINED -> R.string.review_does_keep_combined
    Recommendation.USE_REPLACEMENT -> R.string.review_does_use_replacement
    Recommendation.MARK_OUT_OF_DATE -> if (bulk) R.string.review_does_mark_stale_bulk else R.string.review_does_mark_stale
    Recommendation.KEEP_NUMBER -> R.string.review_does_keep_number
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(if (filtered) R.string.review_empty_group else R.string.review_empty_all),
            style = MaterialTheme.typography.titleMedium
        )
        if (!filtered) {
            AppCaption(stringResource(R.string.review_empty_caption))
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
    var pending by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Pending?>(null) }
    val expandedDescription = stringResource(if (expanded) R.string.review_expanded else R.string.review_collapsed)
    AppCard(emphasis = case.lean == Lean.LIKELY_PROBLEM) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onToggle,
                    role = Role.Button,
                    onClickLabel = stringResource(if (expanded) R.string.review_hide_reasons else R.string.review_show_reasons)
                )
                .semantics { stateDescription = expandedDescription },
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(case.title, style = MaterialTheme.typography.titleMedium)
                    AppCaption(
                        stringResource(
                            when (case.kind) {
                                SuspicionKind.BAD_MERGE -> R.string.review_kind_merge
                                SuspicionKind.STALE_NUMBER -> R.string.review_kind_stale
                            }
                        )
                    )
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            Text(case.headline, style = MaterialTheme.typography.bodyMedium)
            BalanceBar(case.suspicion.size, case.defense.size, case.suspicionScore, case.defenseScore)
            RecommendationLine(case)
        }
        case.recommendation?.let { rec -> AppAction(rec.label, { pending = rec.toPending() }) }

        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IdentityComparison(case.identities)
            EvidenceList(stringResource(R.string.review_why_wrong), case.suspicion, Icons.Filled.Warning, stringResource(R.string.review_nothing_wrong))
            EvidenceList(stringResource(R.string.review_why_fine), case.defense, Icons.Filled.Info, stringResource(R.string.review_nothing_fine))
            // Every choice, the recommended one included; the recommendation only says which.
            Text(
                stringResource(if (case.recommendation == null) R.string.review_your_choices else R.string.review_or_choose),
                style = MaterialTheme.typography.titleMedium
            )
            val rec = case.recommendation
            when (case.kind) {
                SuspicionKind.BAD_MERGE -> {
                    if (rec != Recommendation.SEPARATE) AppTextAction(stringResource(R.string.review_separate_them), { pending = Pending.SPLIT })
                    if (rec != Recommendation.KEEP_COMBINED) AppTextAction(stringResource(R.string.review_same_person), { pending = Pending.KEEP })
                }
                SuspicionKind.STALE_NUMBER -> {
                    if (rec != Recommendation.USE_REPLACEMENT) {
                        case.replacementPhone?.let { AppTextAction(stringResource(R.string.review_use_instead, it), { pending = Pending.ADOPT }) }
                    }
                    if (rec != Recommendation.MARK_OUT_OF_DATE) AppTextAction(stringResource(R.string.review_mark_stale), { pending = Pending.STALE })
                    AppTextAction(stringResource(R.string.review_look_for_current), { pending = Pending.LOOK_UP })
                    if (rec != Recommendation.KEEP_NUMBER) AppTextAction(stringResource(R.string.review_number_fine), { pending = Pending.FINE })
                }
            }
        }
    }

    val name = case.title
    val oldNumber = case.identities.firstOrNull()?.phone
    when (pending) {
        Pending.SPLIT -> AppDialog(stringResource(R.string.review_split_title), stringResource(R.string.review_split_confirm), { pending = null; viewModel.splitMerge(case) }, { pending = null }) {
            Text(stringResource(R.string.review_split_body, name))
        }
        Pending.KEEP -> AppDialog(stringResource(R.string.review_keep_title), stringResource(R.string.review_keep_confirm), { pending = null; viewModel.keepMerge(case) }, { pending = null }) {
            Text(stringResource(R.string.review_keep_body, name))
        }
        Pending.ADOPT -> AppDialog(stringResource(R.string.review_adopt_title, case.replacementPhone.orEmpty()), stringResource(R.string.review_adopt_confirm), { pending = null; viewModel.adoptReplacement(case) }, { pending = null }) {
            Text(stringResource(R.string.review_adopt_body, name, case.replacementPhone.orEmpty()))
        }
        Pending.STALE -> AppDialog(
            if (oldNumber != null) stringResource(R.string.review_stale_title, oldNumber) else stringResource(R.string.review_stale_title_no_number),
            stringResource(R.string.review_stale_confirm),
            { pending = null; viewModel.markStale(case) },
            { pending = null }
        ) {
            Text(stringResource(R.string.review_stale_body, name))
        }
        Pending.LOOK_UP -> AppDialog(stringResource(R.string.review_lookup_title), stringResource(R.string.review_lookup_confirm), { pending = null; viewModel.findAlternates(case.targetId, oldNumber) }, { pending = null }) {
            Text(
                if (oldNumber != null) stringResource(R.string.review_lookup_body, name, oldNumber)
                else stringResource(R.string.review_lookup_body_no_number, name)
            )
        }
        Pending.FINE -> AppDialog(stringResource(R.string.review_fine_title), stringResource(R.string.review_fine_confirm), { pending = null; viewModel.dismissSuspect(case) }, { pending = null }) {
            Text(
                if (oldNumber != null) stringResource(R.string.review_fine_body, oldNumber, name)
                else stringResource(R.string.review_fine_body_no_number, name)
            )
        }
        null -> Unit
    }
}

/**
 * What to do and why, in two lines: "Recommended: Separate them" and the strongest reason on the
 * side it follows. A case too evenly balanced says so instead of guessing.
 */
@Composable
private fun RecommendationLine(case: SuspectCase) {
    val rec = case.recommendation
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (rec == null) {
            Text(stringResource(R.string.review_no_recommendation), style = MaterialTheme.typography.titleMedium)
            AppCaption(stringResource(R.string.review_balanced))
        } else {
            Text(stringResource(R.string.review_recommended, rec.label), style = MaterialTheme.typography.titleMedium)
            case.mainReason?.let { AppCaption(stringResource(R.string.review_mainly_because, it.text.replaceFirstChar { c -> c.lowercase() })) }
            AppCaption(stringResource(whatItDoes(rec)))
        }
    }
}

private fun Recommendation.toPending(): Pending = when (this) {
    Recommendation.SEPARATE -> Pending.SPLIT
    Recommendation.KEEP_COMBINED -> Pending.KEEP
    Recommendation.USE_REPLACEMENT -> Pending.ADOPT
    Recommendation.MARK_OUT_OF_DATE -> Pending.STALE
    Recommendation.KEEP_NUMBER -> Pending.FINE
}

/** The weight of the reasons against (solid) and for (outlined), with the counts in words. */
@Composable
private fun BalanceBar(against: Int, inFavour: Int, suspicion: Double, defense: Double) {
    val againstText = pluralStringResource(R.plurals.review_reasons_against, against, against)
    val inFavourText = stringResource(R.string.review_in_favour, inFavour)
    val description = stringResource(R.string.review_balance_description, againstText, inFavourText)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .semantics { contentDescription = description }
        ) {
            Box(Modifier.weight(suspicion.coerceAtLeast(0.1).toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
            Box(
                Modifier.weight(defense.coerceAtLeast(0.1).toFloat()).fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            AppCaption(stringResource(R.string.review_against, against))
            AppCaption(inFavourText)
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
                Text(line.phone ?: stringResource(R.string.review_no_number), style = MaterialTheme.typography.bodyMedium)
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
                    stringResource(
                        when {
                            e.weight >= 2.5 -> R.string.review_counts_strongly
                            e.weight >= 1.5 -> R.string.review_counts
                            else -> R.string.review_counts_little
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun AlternatesDialog(info: AlternateInfo, onApply: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        title = stringResource(R.string.review_alt_title, info.name),
        confirm = stringResource(if (info.hasReplacement) R.string.review_alt_use else R.string.close),
        onConfirm = if (info.hasReplacement) onApply else onDismiss,
        onDismiss = onDismiss,
        // With nothing to apply the one button closes it; a second "Close" would do the same.
        dismiss = if (info.hasReplacement) stringResource(R.string.cancel) else null
    ) {
        if (info.stalePhone != null && info.holderName != null) {
            Text(
                if (info.holderDiffers) {
                    stringResource(R.string.review_alt_holder_differs, info.stalePhone, info.holderName)
                } else {
                    stringResource(R.string.review_alt_holder_fits, info.stalePhone, info.holderName)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        info.phone?.let { Text(stringResource(R.string.review_alt_number, it), style = MaterialTheme.typography.titleMedium) }
        info.address?.let { Text(stringResource(R.string.review_alt_address, it), style = MaterialTheme.typography.titleMedium) }
        if (!info.hasReplacement) Text(stringResource(R.string.review_alt_nothing, info.name), style = MaterialTheme.typography.bodyMedium)
        AppCaption(info.basis)
    }
}
