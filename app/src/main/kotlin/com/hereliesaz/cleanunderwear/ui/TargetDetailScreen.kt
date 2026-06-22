package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.network.SourceCatalog
import com.hereliesaz.cleanunderwear.network.SourceKind
import com.hereliesaz.cleanunderwear.network.SourceUrlBuilder
import com.hereliesaz.cleanunderwear.util.CyberBackgroundChecks
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetDetailScreen(
    target: Target,
    sourceCatalog: SourceCatalog,
    onUpdateTarget: (Target) -> Unit,
    onLaunchMission: (BrowserMission, (String?) -> Unit) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Intelligence Profile: ${target.displayName}") },
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
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = target.displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            DetailRow(label = "Intelligence Name", value = target.displayName)

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

            target.phoneNumber?.let { DetailRow(label = "Contact Number", value = it) }
            target.email?.let { DetailRow(label = "Intelligence Email", value = it) }
            target.areaCode?.let { DetailRow(label = "Location Reference", value = it) }
            if (!target.residenceInfo.isNullOrBlank()) {
                DetailRow(label = "Known Home Area", value = target.residenceInfo)
            }
            DetailRow(
                label = "Latest Status",
                value = target.status.name.lowercase().replaceFirstChar { it.uppercase() }
            )

            val dateString = if (target.lastScrapedTimestamp > 0) {
                SimpleDateFormat("MM/dd/yyyy hh:mm a", java.util.Locale.US)
                    .format(Date(target.lastScrapedTimestamp))
            } else {
                "Never"
            }
            DetailRow(label = "Last Registry Check", value = dateString)
            DetailRow(label = "Found In", value = target.sourceAccount ?: "Unknown")

            // Verified evidence URL — only present when the stored URL came
            // from the curated catalog AND the contact is in a status that
            // implies a confirmed match. Search-engine URLs and stale entries
            // never qualify.
            val lockupEvidenceUrl = target.lockupUrl?.takeIf {
                sourceCatalog.isFromCatalog(it) && target.status == TargetStatus.INCARCERATED
            }
            val obitEvidenceUrl = target.obituaryUrl?.takeIf {
                sourceCatalog.isFromCatalog(it) && target.status == TargetStatus.DECEASED
            }

            if (lockupEvidenceUrl != null || obitEvidenceUrl != null) {
                Text(
                    text = "Verified Match Source",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                lockupEvidenceUrl?.let { url ->
                    AzButton(
                        text = "Open Verified Roster Page",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AzButtonShape.RECTANGLE
                    )
                }
                obitEvidenceUrl?.let { url ->
                    AzButton(
                        text = "Open Verified Obituary Page",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AzButtonShape.RECTANGLE
                    )
                }
            }

            // POSSIBLE_MATCH review. The vigil found a roster record matching
            // this contact's name but cannot prove it's the same person, so it
            // surfaces the candidate here for the user to confirm or reject.
            if (target.status == TargetStatus.POSSIBLE_MATCH) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Possible incarceration match — needs your review",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = target.lastVerificationSnippet
                                ?: "A roster record matched this contact's name. Confirm it is the same person.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        target.lockupUrl?.takeIf { sourceCatalog.isFromCatalog(it) }?.let { url ->
                            AzButton(
                                text = "Open roster page to verify",
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = AzButtonShape.RECTANGLE
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AzButton(
                                text = "Confirm — incarcerated",
                                onClick = { onUpdateTarget(target.copy(status = TargetStatus.INCARCERATED)) },
                                modifier = Modifier.weight(1f),
                                shape = AzButtonShape.RECTANGLE
                            )
                            AzButton(
                                text = "Not a match",
                                onClick = {
                                    // Non-BOP sources carry no inmate number, so the
                                    // snippet has no [#id]; fall back to the evidence URL
                                    // (the deterministic lockupUrl) so the dismissal sticks.
                                    val key = extractInmateKey(target.lastVerificationSnippet)
                                        ?: target.lockupUrl
                                    val dismissed = target.dismissedMatchKeys
                                        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                                        ?: emptyList()
                                    val updated = (if (key != null) dismissed + key else dismissed)
                                        .distinct().joinToString(",")
                                    onUpdateTarget(
                                        target.copy(
                                            status = TargetStatus.MONITORING,
                                            dismissedMatchKeys = updated.ifEmpty { null },
                                            lastVerificationSnippet = null,
                                            lockupUrl = null
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = AzButtonShape.RECTANGLE
                            )
                        }
                    }
                }
            }

            // UNVERIFIED status explainer. Tells the user why nothing's
            // happening and offers to launch identity enrichment. The button
            // is a placeholder until BrowserScreen lands; it currently routes
            // to cyberbackgroundchecks via Intent.ACTION_VIEW so the user can
            // resolve the contact manually if they want.
            if (target.status == TargetStatus.UNVERIFIED) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Name needs first + last to be verifiable. " +
                                "This contact is queued for identity enrichment via cyberbackgroundchecks.com.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AzButton(
                            text = "Resolve Identity Now",
                            onClick = {
                                // Pick the best mode the contact supports —
                                // phone is most precise, name is least.
                                val mission: BrowserMission = target.phoneNumber?.let {
                                    BrowserMission.CbcByPhone(it)
                                } ?: target.email?.let {
                                    BrowserMission.CbcByEmail(it)
                                } ?: target.residenceInfo?.let {
                                    BrowserMission.CbcByAddress(it)
                                } ?: BrowserMission.CbcByName(target.displayName)

                                onLaunchMission(mission) { html ->
                                    // Result parsing lands in a follow-up
                                    // (PR6c). For now just log so the round-
                                    // trip is observable.
                                    DiagnosticLogger.log(
                                        "Identity mission for ${target.displayName} returned " +
                                            (html?.length?.let { "$it chars" } ?: "no payload")
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = AzButtonShape.RECTANGLE
                        )
                    }
                }
            }

            // NO_AUTOMATED_SOURCE explainer. The contact is identifiable, but the
            // daily vigil has no source it can fetch + verify on its own for this
            // locale (everything in the catalog for the area is operator-launch
            // only). Tell the user plainly and point them at the manual source
            // chips below — don't let the absence of an alert read as "all clear".
            if (target.monitorabilityState ==
                com.hereliesaz.cleanunderwear.data.MonitorabilityState.NO_AUTOMATED_SOURCE
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "The daily vigil has no automated records source for this " +
                                "contact's area — public records here are operator-launch only. " +
                                "Use the Manual Research source chips below to check them in your " +
                                "browser; the automatic scan cannot confirm a change for this person.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "Check Frequency",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AzRoller(
                    options = listOf(6, 12, 24, 48).map { "${it}h" },
                    selectedOption = "${target.checkFrequencyHours}h",
                    onOptionSelected = { hoursText ->
                        val hours = hoursText.removeSuffix("h").toInt()
                        onUpdateTarget(target.copy(checkFrequencyHours = hours))
                    }
                )
            }

            if (target.lastVerificationSnippet != null) {
                HorizontalDivider()
                Text(
                    text = "Verification Evidence",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = target.lastVerificationSnippet,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Only "Incorrect Match" needs a button — confirming
                        // a match is the implicit default (the snippet stays
                        // attached to the target until the user rejects it).
                        // The previous "Confirm Match" button had an empty
                        // onClick.
                        AzButton(
                            text = "Incorrect Match",
                            onClick = {
                                onUpdateTarget(target.copy(
                                    status = TargetStatus.MONITORING,
                                    lastVerificationSnippet = null
                                ))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = AzButtonShape.RECTANGLE
                        )
                    }
                }
            }

            // Enrichment provenance — shows which CBC search(es) sourced this
            // contact's data and whether the candidate was verified as the same
            // person, so a bad merge is visible and reversible.
            target.enrichmentProvenance?.let { provenance ->
                Text(
                    text = "Enrichment: $provenance",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Manual Research — these all open in the user's browser. None of
            // them write back to Target.lockupUrl / Target.obituaryUrl, so they
            // can never be confused with verified evidence.
            Text(
                text = "Manual Research",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CyberBackgroundChecks.getNameSearchUrl(target.displayName)))) },
                    label = { Text("Name Check") }
                )

                target.phoneNumber?.let { phone ->
                    AssistChip(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CyberBackgroundChecks.getPhoneSearchUrl(phone)))) },
                        label = { Text("Phone Check") }
                    )
                }

                target.email?.let { email ->
                    AssistChip(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CyberBackgroundChecks.getEmailSearchUrl(email)))) },
                        label = { Text("Email Check") }
                    )
                }

                target.residenceInfo?.let { addr ->
                    AssistChip(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CyberBackgroundChecks.getAddressSearchUrl(addr)))) },
                        label = { Text("Address Check") }
                    )
                }

                // Catalog-derived lockup sources for this contact's locale
                // (county sheriff, state DOC, VINELink, federal BOP, etc.) —
                // clicking a chip opens a human-facing page in the browser.
                // MANUAL_LANDING sources open their landing URL; automatable
                // sources (e.g. the BOP JSON query) open their evidence URL so
                // the operator never lands on a raw API/JSON response. Sources
                // whose landing URL still carries unfilled {first}/{last} slots
                // are skipped here — they have no operator-friendly page.
                val lockupChips = sourceCatalog
                    .lockupSourcesFor(target.areaCode, target.residenceInfo)
                lockupChips.forEach { source ->
                    val landing = if (source.kind == SourceKind.MANUAL_LANDING) {
                        source.urlTemplate
                    } else {
                        source.evidenceUrlTemplate
                    }
                    if (!landing.contains("{")) {
                        AssistChip(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(landing)))
                            },
                            label = { Text(source.label) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Identity correlation — face recognition, reverse image search,
            // and name-based OSINT services ported from the doxray project.
            // All entries are MANUAL_LANDING: face/image services need a
            // photo upload the Registry doesn't carry, and the name-based
            // services bot-block raw HTTP. The chips open each provider in
            // the user's own browser so their session cookies apply.
            val (first, last) = splitDisplayName(target.displayName)
            // Filter identity sources to only those whose template can be
            // safely slot-filled with the names we have. A template that
            // references {last} on a mononym contact would otherwise produce
            // junk like ".../people/madonna-" and either 404 the user or, on
            // search providers, run a quoted-string search for "Madonna ".
            val identitySources = sourceCatalog.identitySources().filter { source ->
                val needsFirst = source.urlTemplate.contains("{first}")
                val needsLast = source.urlTemplate.contains("{last}")
                (!needsFirst || first.isNotBlank()) && (!needsLast || last.isNotBlank())
            }
            if (identitySources.isNotEmpty()) {
                Text(
                    text = "Identity Correlation (doxray)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    identitySources.forEach { source ->
                        AssistChip(
                            onClick = {
                                val url = SourceUrlBuilder.buildFetchUrl(source, first, last)
                                if (source.id in BROWSER_HOSTED_IDENTITY_SOURCES) {
                                    // CBC + SmartBGC bot-block raw HTTP and the
                                    // system-browser UA; route through the in-app
                                    // WebView so the request rides on the user's
                                    // own session cookies and WebView UA. See
                                    // WebViewIdentityInterceptor for the OkHttp
                                    // half of the same bridge.
                                    onLaunchMission(
                                        BrowserMission.OpenInBrowser(url, source.label)
                                    ) { /* browse-only; no extraction */ }
                                } else {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        // No browser installed — fail silent,
                                        // matching the "General News Search"
                                        // button below.
                                    }
                                }
                            },
                            label = { Text(source.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            AzButton(
                text = "General News Search",
                onClick = {
                    try {
                        val query = "${target.displayName} ${target.residenceInfo ?: ""}".trim()
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://news.google.com/search?q=$encodedQuery"))
                        context.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        // Ignore if no web browser is installed
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = AzButtonShape.RECTANGLE
            )
        }
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

/**
 * Splits a free-form display name into a (first, last) pair for slot-filling
 * URL templates. Returns (name, "") for mononyms; (first, last) for two-token
 * names; (first-token, last-token) for longer names. Middle tokens are
 * preserved in the first part of the pair to improve OSINT accuracy.
 */
private fun splitDisplayName(displayName: String): Pair<String, String> {
    val tokens = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        tokens.isEmpty() -> "" to ""
        tokens.size == 1 -> tokens[0] to ""
        else -> tokens.dropLast(1).joinToString(" ") to tokens.last()
    }
}

/**
 * Pulls the record identifier the verifier embedded as "[#id]" in a possible-
 * match snippet, so "Not a match" can remember exactly which record to suppress.
 */
private fun extractInmateKey(snippet: String?): String? =
    snippet?.let { Regex("\\[#([^\\]]+)]").find(it)?.groupValues?.getOrNull(1)?.trim() }
        ?.takeIf { it.isNotEmpty() }

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
