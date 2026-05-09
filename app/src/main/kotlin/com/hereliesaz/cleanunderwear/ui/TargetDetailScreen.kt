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

                // Catalog-derived MANUAL_LANDING sources for this contact's
                // locale (county sheriff, state DOC, VINELink, etc.) — clicking
                // a chip opens the source's landing page in the user's browser.
                val manualLockup = sourceCatalog
                    .lockupSourcesFor(target.areaCode, target.residenceInfo)
                    .filter { it.kind == SourceKind.MANUAL_LANDING }
                manualLockup.forEach { source ->
                    AssistChip(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.urlTemplate)))
                        },
                        label = { Text(source.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
