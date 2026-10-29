package com.hereliesaz.cleanunderwear.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.hereliesaz.aznavrail.AzRoller
import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.network.NameValidator
import com.hereliesaz.cleanunderwear.network.SourceCatalog
import com.hereliesaz.cleanunderwear.network.SourceKind
import com.hereliesaz.cleanunderwear.network.SourceScope
import com.hereliesaz.cleanunderwear.network.SourceUrlBuilder
import com.hereliesaz.cleanunderwear.util.CyberBackgroundChecks
import java.net.URLEncoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

/**
 * One contact: what the app knows, what the last check found, and what the user can do about it.
 *
 * A possible match is the reason most people open this screen, so it comes first, says plainly
 * that it may be someone else, and asks before recording anything grave. Every verdict can be
 * taken back: "Not them" offers Undo in the snackbar, and "Show dismissed records again" on this
 * screen lets records already dismissed be raised once more; a confirmed status has "This was a
 * mistake", which also stops the same record from being raised again.
 */
@Composable
fun TargetDetailScreen(
    target: Target,
    sourceCatalog: SourceCatalog,
    onUpdateTarget: (shown: Target, change: (Target) -> Target) -> Unit,
    onLaunchMission: (BrowserMission, (String?) -> Unit) -> Unit,
    onLookUp: (Int) -> Unit,
    onArchive: (Int) -> Unit,
    onRestore: (Int) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var confirming by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Verdict?>(null) }
    val name = target.displayName
    val snackbars = LocalAppSnackbars.current
    val bottomBar = LocalBottomBarHeight.current

    // "Not them" is undoable from the snackbar. The snackbar waits until the contact has really
    // changed (a verdict is refused if the match moved meanwhile) and lives in the app's scope,
    // so Undo is still there after Back. Holds the contact as it was and as "Not them" leaves it.
    var undoDismissal by remember { mutableStateOf<Pair<Target, Target>?>(null) }
    val latest by rememberUpdatedState(target)
    val dismissedMessage = stringResource(R.string.detail_dismissed_snackbar, name)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(undoDismissal) {
        val (before, after) = undoDismissal ?: return@LaunchedEffect
        val changed = withTimeoutOrNull(3_000) { snapshotFlow { latest.status != before.status }.first { it } }
        undoDismissal = null
        if (changed != null) {
            snackbars?.show(dismissedMessage, undoLabel) { onUpdateTarget(after) { restoreDismissal(it, before) } }
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = name, onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = screenInsets()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .contentWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            StatusBadge(statusSpec(target.status, target.matchKind, target.monitorabilityState))

            val isDeath = target.matchKind == "DEATH"
            val evidenceUrl = (if (isDeath) target.obituaryUrl else target.lockupUrl)?.takeIf { sourceCatalog.isFromCatalog(it) }

            if (target.status == TargetStatus.POSSIBLE_MATCH || target.status == TargetStatus.WEAK_MATCH) {
                val weak = target.status == TargetStatus.WEAK_MATCH
                AppCard(emphasis = !weak) {
                    Text(
                        if (weak) stringResource(R.string.detail_weak_match, name)
                        else stringResource(
                            when (target.matchKind) {
                                "DEATH" -> R.string.detail_found_obituary
                                "ARREST" -> R.string.detail_found_arrest
                                else -> R.string.detail_found_custody
                            },
                            name
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    AppCaption(stringResource(R.string.detail_may_be_someone_else))
                    target.matchConfidence?.let { p ->
                        AppCaption(stringResource(R.string.detail_match_fit, name, (p * 100).toInt()))
                    }
                    target.matchReasons?.takeIf { it.isNotBlank() }?.let { reasons ->
                        Text(reasons.lines().joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                    }
                    target.lastVerificationSnippet?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                    evidenceUrl?.let { url -> AppAction(stringResource(R.string.detail_open_record), { openUrl(context, url) }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        AppAction(stringResource(R.string.detail_its_them, name), { confirming = Verdict.CONFIRM }, Modifier.weight(1f))
                        AppAction(stringResource(R.string.detail_not_them), {
                            undoDismissal = target to dismissed(target, evidenceUrl)
                            onUpdateTarget(target) { dismissed(it, evidenceUrl) }
                        }, Modifier.weight(1f))
                    }
                }
            }

            if (target.status == TargetStatus.DECEASED || target.status == TargetStatus.INCARCERATED) {
                AppCard {
                    Text(
                        if (target.status == TargetStatus.DECEASED) stringResource(R.string.detail_confirmed_died, name)
                        else stringResource(R.string.detail_confirmed_custody, name),
                        style = MaterialTheme.typography.titleMedium
                    )
                    AppCaption(stringResource(R.string.detail_checks_stopped, name))
                    val confirmedUrl = (if (target.status == TargetStatus.DECEASED) target.obituaryUrl else target.lockupUrl)
                        ?.takeIf { sourceCatalog.isFromCatalog(it) }
                    confirmedUrl?.let { url -> AppAction(stringResource(R.string.detail_open_record), { openUrl(context, url) }) }
                    AppTextAction(stringResource(R.string.detail_was_mistake), { confirming = Verdict.UNDO })
                }
            }

            if (target.status == TargetStatus.UNVERIFIED || target.monitorabilityState == MonitorabilityState.NEEDS_ENRICHMENT ||
                target.monitorabilityState == MonitorabilityState.ENRICHMENT_FAILED
            ) {
                AppCard {
                    Text(
                        if (NameValidator.isVerifiable(name)) stringResource(R.string.detail_needs_phone_or_address, name)
                        else stringResource(R.string.detail_needs_full_name, name),
                        style = MaterialTheme.typography.titleMedium
                    )
                    AppCaption(stringResource(R.string.detail_needs_details_caption, name))
                    AppAction(stringResource(R.string.detail_look_up_details), { confirming = Verdict.LOOK_UP })
                }
            }

            if (target.monitorabilityState == MonitorabilityState.NO_AUTOMATED_SOURCE) {
                AppCard {
                    Text(stringResource(R.string.detail_no_source_title, name), style = MaterialTheme.typography.titleMedium)
                    AppCaption(stringResource(R.string.detail_no_source_caption))
                }
            }

            AppSection(stringResource(R.string.detail_section_details))
            target.phoneNumber?.let {
                DetailRow(
                    stringResource(R.string.detail_phone),
                    if (target.phoneIsStale) stringResource(R.string.detail_phone_stale, it) else it
                )
                if (target.phoneIsStale) {
                    AppTextAction(stringResource(R.string.detail_number_current), { onUpdateTarget(target) { it.copy(stalePhone = null) } })
                }
            }
            target.email?.let { DetailRow(stringResource(R.string.detail_email), it) }
            target.residenceInfo?.takeIf { it.isNotBlank() }?.let { DetailRow(stringResource(R.string.detail_address), it) }
            target.areaCode?.takeIf { it != "LOCAL" }?.let { DetailRow(stringResource(R.string.detail_area_code), it) }
            target.dateOfBirth?.let {
                DetailRow(
                    stringResource(R.string.detail_born),
                    it.removePrefix("~").let { y -> if (it.startsWith("~")) stringResource(R.string.detail_born_about, y) else y }
                )
            }
            DetailRow(stringResource(R.string.detail_from), target.sourceAccount ?: stringResource(R.string.detail_unknown))

            AppSection(stringResource(R.string.detail_section_checks))
            DetailRow(
                stringResource(R.string.detail_last_checked),
                if (target.lastScrapedTimestamp > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(target.lastScrapedTimestamp))
                else stringResource(R.string.detail_not_yet)
            )
            target.unreadSources?.let {
                AppCaption(
                    stringResource(
                        if (target.status == TargetStatus.POSSIBLE_MATCH || target.status == TargetStatus.WEAK_MATCH) {
                            R.string.detail_unread_match_stays
                        } else {
                            R.string.detail_unread_retry
                        },
                        it
                    )
                )
            }
            val plan = sourceCatalog.searchPlan(target.areaCode, target.residenceInfo)
            if (plan.automated && !plan.lockupAutomated) {
                val federal = if (plan.lockup.any { it.kind != SourceKind.MANUAL_LANDING && it.scope == SourceScope.MULTI_STATE }) {
                    stringResource(R.string.detail_federal_prisons)
                } else ""
                AppCaption(
                    if (plan.lockup.any { it.kind != SourceKind.MANUAL_LANDING && it.county != null }) {
                        stringResource(R.string.detail_county_jail_not_searched, federal)
                    } else {
                        stringResource(R.string.detail_no_jail_search, federal)
                    }
                )
            }
            // Who "Not them" was said about: how many records, and a way to let them be raised again.
            val dismissedCount = dismissedKeys(target).size
            if (dismissedCount > 0) {
                AppCaption(pluralStringResource(R.plurals.detail_dismissed_records, dismissedCount, dismissedCount, name))
                val forgottenMessage = stringResource(R.string.detail_dismissed_forgotten)
                AppTextAction(stringResource(R.string.detail_show_dismissed_again), {
                    val before = target
                    val after = forgetDismissals(before)
                    onUpdateTarget(before) { forgetDismissals(it) }
                    snackbars?.show(forgottenMessage, undoLabel) {
                        onUpdateTarget(after) { it.copy(dismissedMatchKeys = before.dismissedMatchKeys) }
                    }
                })
            }
            Text(stringResource(R.string.detail_how_often), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val frequencies = linkedMapOf(
                24 to stringResource(R.string.detail_daily),
                48 to stringResource(R.string.detail_every_2_days),
                168 to stringResource(R.string.detail_weekly)
            )
            AzRoller(
                options = frequencies.values.toList(),
                selectedOption = frequencies[target.checkFrequencyHours] ?: frequencies.getValue(24),
                onOptionSelected = { label ->
                    val hours = frequencies.entries.first { it.value == label }.key
                    onUpdateTarget(target) { it.copy(checkFrequencyHours = hours) }
                }
            )
            target.enrichmentProvenance?.let { AppCaption(stringResource(R.string.detail_details_lookup, it)) }

            AppSection(stringResource(R.string.detail_section_look_up))
            AppCaption(stringResource(R.string.detail_look_up_caption))
            val (first, last) = searchName(name)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getNameSearchUrl(name)) }, label = { Text(stringResource(R.string.detail_by_name)) })
                target.usablePhone?.let { phone ->
                    AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getPhoneSearchUrl(phone)) }, label = { Text(stringResource(R.string.detail_by_phone)) })
                }
                target.email?.let { email ->
                    AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getEmailSearchUrl(email)) }, label = { Text(stringResource(R.string.detail_by_email)) })
                }
                target.residenceInfo?.let { addr ->
                    AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getAddressSearchUrl(addr)) }, label = { Text(stringResource(R.string.detail_by_address)) })
                }
                // The records sources for this contact's area. A manual source opens its
                // landing page; an automated one its human-facing page, never a raw API.
                (plan.lockup + plan.obituary).distinctBy { it.id }.forEach { source ->
                    val template = if (source.kind == SourceKind.MANUAL_LANDING) source.urlTemplate else source.evidenceUrlTemplate
                    val landing = SourceUrlBuilder.fillLanding(template, first, last)
                    if (!landing.contains("{")) {
                        AssistChip(onClick = { openUrl(context, landing) }, label = { Text(source.label) })
                    }
                }
                AssistChip(
                    onClick = {
                        val query = URLEncoder.encode("$name ${target.residenceInfo ?: ""}".trim(), "UTF-8")
                        openUrl(context, "https://news.google.com/search?q=$query")
                    },
                    label = { Text(stringResource(R.string.detail_news)) }
                )
            }

            // Shown only when there is a match to rule out.
            val hasMatchToVerify = target.status in setOf(
                TargetStatus.POSSIBLE_MATCH, TargetStatus.WEAK_MATCH, TargetStatus.INCARCERATED, TargetStatus.DECEASED
            )
            val identitySources = sourceCatalog.identitySources().filter { source ->
                (!source.urlTemplate.contains("{first}") || first.isNotBlank()) && (!source.urlTemplate.contains("{last}") || last.isNotBlank())
            }
            if (hasMatchToVerify && identitySources.isNotEmpty()) {
                AppSection(stringResource(R.string.detail_section_really_them))
                AppCaption(stringResource(R.string.detail_really_them_caption))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    identitySources.forEach { source ->
                        AssistChip(
                            onClick = {
                                val url = SourceUrlBuilder.buildFetchUrl(source, first, last)
                                if (source.id in BROWSER_HOSTED_IDENTITY_SOURCES) {
                                    // These sites block anything but a real browser session; open them in
                                    // the app's own browser so the user's session applies.
                                    onLaunchMission(BrowserMission.OpenInBrowser(url, source.label)) { }
                                } else {
                                    openUrl(context, url)
                                }
                            },
                            label = { Text(source.label) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (target.status == TargetStatus.IGNORED) {
                AppTextAction(stringResource(R.string.bring_back_to_checks), { onRestore(target.id) })
            } else {
                AppTextAction(stringResource(R.string.archive), { confirming = Verdict.ARCHIVE })
            }
            // Clear of the bar of an operation put aside.
            Spacer(Modifier.height(bottomBar))
        }
    }

    when (confirming) {
        Verdict.CONFIRM -> {
            val death = target.matchKind == "DEATH"
            AppDialog(
                title = stringResource(if (death) R.string.detail_mark_deceased_title else R.string.detail_mark_custody_title, name),
                confirm = stringResource(if (death) R.string.detail_mark_deceased else R.string.detail_mark_custody),
                onConfirm = {
                    confirming = null
                    onUpdateTarget(target) { it.copy(status = if (death) TargetStatus.DECEASED else TargetStatus.INCARCERATED) }
                },
                onDismiss = { confirming = null }
            ) {
                Text(stringResource(R.string.detail_mark_body, name))
            }
        }
        Verdict.UNDO -> AppDialog(
            title = stringResource(R.string.detail_undo_title),
            confirm = stringResource(R.string.undo),
            onConfirm = {
                confirming = null
                onUpdateTarget(target) { dismissed(it, if (it.matchKind == "DEATH") it.obituaryUrl else it.lockupUrl) }
            },
            onDismiss = { confirming = null }
        ) {
            Text(stringResource(R.string.detail_undo_body, name))
        }
        Verdict.LOOK_UP -> AppDialog(
            title = stringResource(R.string.detail_look_up_title, name),
            confirm = stringResource(R.string.detail_look_up),
            onConfirm = { confirming = null; onLookUp(target.id) },
            onDismiss = { confirming = null }
        ) {
            Text(stringResource(R.string.detail_look_up_body, name))
        }
        Verdict.ARCHIVE -> AppDialog(
            title = stringResource(R.string.detail_archive_title, name),
            confirm = stringResource(R.string.archive),
            onConfirm = { confirming = null; onArchive(target.id) },
            onDismiss = { confirming = null }
        ) {
            Text(stringResource(R.string.detail_archive_body, name))
        }
        null -> Unit
    }
}

private enum class Verdict { CONFIRM, UNDO, LOOK_UP, ARCHIVE }

/** Opens [url] in the user's browser; nothing happens on a device with no browser. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: android.content.ActivityNotFoundException) {
        // No browser installed.
    }
}

/**
 * Identity-source IDs whose chips open in the in-app browser (not the system
 * browser): CBC and SmartBGC block other clients, and the app's browser carries
 * the session (and any CAPTCHA solved) that the lookups use.
 */
private val BROWSER_HOSTED_IDENTITY_SOURCES = setOf(
    "cyberbgc_people_lookup",
    "smartbgc_people_lookup",
)

/** The given name and surname to search on, as the daily check uses them ("" when missing). */
private fun searchName(displayName: String): Pair<String, String> {
    val tokens = NameValidator.tokenize(displayName)
    return when {
        tokens.isEmpty() -> "" to ""
        tokens.size == 1 -> tokens[0] to ""
        else -> tokens.first() to tokens.last()
    }
}
