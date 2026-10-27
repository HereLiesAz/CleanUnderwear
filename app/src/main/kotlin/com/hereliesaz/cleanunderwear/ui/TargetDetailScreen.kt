package com.hereliesaz.cleanunderwear.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.aznavrail.AzRoller
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.network.NameValidator
import com.hereliesaz.cleanunderwear.network.SourceCatalog
import com.hereliesaz.cleanunderwear.network.SourceKind
import com.hereliesaz.cleanunderwear.network.SourceUrlBuilder
import com.hereliesaz.cleanunderwear.util.CyberBackgroundChecks
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

/**
 * One contact: what the app knows, what the last check found, and what the user can do about it.
 *
 * A possible match is the reason most people open this screen, so it comes first, says plainly
 * that it may be someone else, and asks before recording anything grave. Every verdict can be
 * taken back, and taking it back also stops the same record from being raised again.
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

    Scaffold(
        topBar = { AppTopBar(title = name, onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(Spacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            StatusBadge(statusSpec(target.status, target.matchKind, target.monitorabilityState))

            val isDeath = target.matchKind == "DEATH"
            val evidenceUrl = (if (isDeath) target.obituaryUrl else target.lockupUrl)?.takeIf { sourceCatalog.isFromCatalog(it) }

            if (target.status == TargetStatus.POSSIBLE_MATCH || target.status == TargetStatus.WEAK_MATCH) {
                val weak = target.status == TargetStatus.WEAK_MATCH
                val what = when (target.matchKind) {
                    "DEATH" -> "an obituary"
                    "ARREST" -> "an arrest or court record"
                    else -> "a jail or prison record"
                }
                AppCard(emphasis = !weak) {
                    Text(
                        if (weak) "A record shares $name's name, but little else about it fits."
                        else "Found $what that resembles $name.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    AppCaption("It may be someone else with the same name. Look at the record before deciding.")
                    target.matchConfidence?.let { p ->
                        AppCaption("How closely it fits what you know about $name: ${(p * 100).toInt()}%.")
                    }
                    target.matchReasons?.takeIf { it.isNotBlank() }?.let { reasons ->
                        Text(reasons.lines().joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                    }
                    target.lastVerificationSnippet?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                    evidenceUrl?.let { url -> AppAction("Open the record", { openUrl(context, url) }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        AppAction("It's $name", { confirming = Verdict.CONFIRM }, Modifier.weight(1f))
                        AppAction("Not them", { onUpdateTarget(target) { dismissed(it, evidenceUrl) } }, Modifier.weight(1f))
                    }
                }
            }

            if (target.status == TargetStatus.DECEASED || target.status == TargetStatus.INCARCERATED) {
                AppCard {
                    Text(
                        if (target.status == TargetStatus.DECEASED) "You confirmed that $name has died."
                        else "You confirmed that $name is in custody.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    AppCaption("Daily checks for $name have stopped.")
                    val confirmedUrl = (if (target.status == TargetStatus.DECEASED) target.obituaryUrl else target.lockupUrl)
                        ?.takeIf { sourceCatalog.isFromCatalog(it) }
                    confirmedUrl?.let { url -> AppAction("Open the record", { openUrl(context, url) }) }
                    AppTextAction("This was a mistake", { confirming = Verdict.UNDO })
                }
            }

            if (target.status == TargetStatus.UNVERIFIED || target.monitorabilityState == MonitorabilityState.NEEDS_ENRICHMENT ||
                target.monitorabilityState == MonitorabilityState.ENRICHMENT_FAILED
            ) {
                AppCard {
                    Text(
                        if (NameValidator.isVerifiable(name)) "$name needs a phone number or an address to be checked."
                        else "$name needs a first and last name to be checked.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    AppCaption(
                        "Add it in your Contacts app, or look $name up on CyberBackgroundChecks, a people-search " +
                            "site, to fill in what's missing. The lookup sends the name, number or email you have for them."
                    )
                    AppAction("Look up details", { confirming = Verdict.LOOK_UP })
                }
            }

            if (target.monitorabilityState == MonitorabilityState.NO_AUTOMATED_SOURCE) {
                AppCard {
                    Text("No public records search for $name's area runs on its own.", style = MaterialTheme.typography.titleMedium)
                    AppCaption("No news doesn't mean all is well here. Use the searches under \"Look them up yourself\" to check by hand.")
                }
            }

            AppSection("Details")
            target.phoneNumber?.let {
                DetailRow("Phone", if (target.phoneIsStale) "$it (you marked this number out of date)" else it)
                if (target.phoneIsStale) AppTextAction("This number is current after all", { onUpdateTarget(target) { it.copy(stalePhone = null) } })
            }
            target.email?.let { DetailRow("Email", it) }
            target.residenceInfo?.takeIf { it.isNotBlank() }?.let { DetailRow("Address", it) }
            target.areaCode?.takeIf { it != "LOCAL" }?.let { DetailRow("Area code", it) }
            target.dateOfBirth?.let { DetailRow("Born", it.removePrefix("~").let { y -> if (it.startsWith("~")) "about $y" else y }) }
            DetailRow("From", target.sourceAccount ?: "Unknown")

            AppSection("Checks")
            DetailRow(
                "Last checked",
                if (target.lastScrapedTimestamp > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(target.lastScrapedTimestamp))
                else "Not yet"
            )
            target.unreadSources?.let {
                AppCaption("The last check couldn't read $it. It will try again within six hours.")
            }
            val plan = sourceCatalog.searchPlan(target.areaCode, target.residenceInfo)
            if (plan.automated && !plan.lockupAutomated) {
                AppCaption(
                    if (plan.lockup.any { it.kind != SourceKind.MANUAL_LANDING }) {
                        "Their own county's jail isn't searched on its own; only other jails in the state, and obituaries and news, are. " +
                            "Its link is below, under Look them up yourself."
                    } else {
                        "No jail or prison search for this area runs on its own, so only obituaries and news are checked."
                    }
                )
            }
            Text("How often", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val frequencies = linkedMapOf(24 to "Daily", 48 to "Every 2 days", 168 to "Weekly")
            AzRoller(
                options = frequencies.values.toList(),
                selectedOption = frequencies[target.checkFrequencyHours] ?: "Daily",
                onOptionSelected = { label ->
                    val hours = frequencies.entries.first { it.value == label }.key
                    onUpdateTarget(target) { it.copy(checkFrequencyHours = hours) }
                }
            )
            target.enrichmentProvenance?.let { AppCaption("Details lookup: $it") }

            AppSection("Look them up yourself")
            AppCaption("Each opens in your browser. The site sees the name or number you search.")
            val (first, last) = searchName(name)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getNameSearchUrl(name)) }, label = { Text("By name") })
                target.usablePhone?.let { phone ->
                    AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getPhoneSearchUrl(phone)) }, label = { Text("By phone") })
                }
                target.email?.let { email ->
                    AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getEmailSearchUrl(email)) }, label = { Text("By email") })
                }
                target.residenceInfo?.let { addr ->
                    AssistChip(onClick = { openUrl(context, CyberBackgroundChecks.getAddressSearchUrl(addr)) }, label = { Text("By address") })
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
                    label = { Text("News") }
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
                AppSection("Is it really them?")
                AppCaption("Face and people searches that can help rule a match out. Photo searches open on their home page; add a photo there.")
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
                AppTextAction("Bring back to daily checks", { onRestore(target.id) })
            } else {
                AppTextAction("Archive", { confirming = Verdict.ARCHIVE })
            }
        }
    }

    when (confirming) {
        Verdict.CONFIRM -> {
            val death = target.matchKind == "DEATH"
            AppDialog(
                title = if (death) "Mark $name as deceased?" else "Mark $name as in custody?",
                confirm = if (death) "Mark as deceased" else "Mark as in custody",
                onConfirm = {
                    confirming = null
                    onUpdateTarget(target) { it.copy(status = if (death) TargetStatus.DECEASED else TargetStatus.INCARCERATED) }
                },
                onDismiss = { confirming = null }
            ) {
                Text("Daily checks for $name stop, and this is noted in their contact in your phone book. You can undo it.")
            }
        }
        Verdict.UNDO -> AppDialog(
            title = "Undo this?",
            confirm = "Undo",
            onConfirm = {
                confirming = null
                onUpdateTarget(target) { dismissed(it, if (it.matchKind == "DEATH") it.obituaryUrl else it.lockupUrl) }
            },
            onDismiss = { confirming = null }
        ) {
            Text("$name goes back to daily checks, and this record won't be raised again.")
        }
        Verdict.LOOK_UP -> AppDialog(
            title = "Look up $name?",
            confirm = "Look up",
            onConfirm = { confirming = null; onLookUp(target.id) },
            onDismiss = { confirming = null }
        ) {
            Text("CyberBackgroundChecks opens in the app's browser and is searched with what you have for $name. You may be asked to solve a CAPTCHA.")
        }
        Verdict.ARCHIVE -> AppDialog(
            title = "Archive $name?",
            confirm = "Archive",
            onConfirm = { confirming = null; onArchive(target.id) },
            onDismiss = { confirming = null }
        ) {
            Text("$name won't be checked and won't appear in your list. Turn on Archives to see them and bring them back.")
        }
        null -> Unit
    }
}

private enum class Verdict { CONFIRM, UNDO, LOOK_UP, ARCHIVE }

/**
 * [target] with its current match rejected: back to daily checks, with the record remembered as
 * dismissed so the same record is never raised again. The record's own key is used, so only this
 * record is dismissed; matches stored before keys existed fall back to the inmate number in the
 * snippet, then the evidence URL.
 */
private fun dismissed(target: Target, evidenceUrl: String?): Target {
    val key = target.matchRecordKey ?: extractInmateKey(target.lastVerificationSnippet) ?: evidenceUrl
    val keys = (target.dismissedMatchKeys?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty() + listOfNotNull(key))
        .distinct().joinToString(",")
    return target.copy(
        status = TargetStatus.MONITORING,
        dismissedMatchKeys = keys.ifEmpty { null },
        lastVerificationSnippet = null,
        lockupUrl = null,
        obituaryUrl = null,
        matchConfidence = null,
        matchKind = null,
        matchReasons = null,
        matchRecordKey = null
    )
}

/** Opens [url] in the user's browser; nothing happens on a device with no browser. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: android.content.ActivityNotFoundException) {
        // No browser installed.
    }
}

/**
 * Identity-source IDs whose chips MUST open in the in-app WebView (not the
 * system browser) so requests carry the user's WebView UA + session cookies.
 * CBC and SmartBGC bot-block raw HTTP requests with non-WebView UAs; the
 * in-app BrowserScreen + WebViewIdentityInterceptor together guarantee
 * every CBC/SmartBGC request uses "the user's header" end-to-end.
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

/**
 * Pulls the record identifier the verifier embedded as "[#id]" in a possible-
 * match snippet, so "Not them" can remember exactly which record to suppress.
 */
private fun extractInmateKey(snippet: String?): String? =
    snippet?.let { Regex("\\[#([^\\]]+)]").find(it)?.groupValues?.getOrNull(1)?.trim() }
        ?.takeIf { it.isNotEmpty() }
