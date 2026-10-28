package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.hereliesaz.aznavrail.AzLoad
import com.hereliesaz.aznavrail.AzTextBox
import com.hereliesaz.cleanunderwear.data.TargetLite
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** The contact list: search, filters, and every contact with its status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetListScreen(
    viewModel: MainViewModel,
    onTargetClick: (Int) -> Unit
) {
    val targets = viewModel.targets.collectAsLazyPagingItems()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val showDiagnosticLog by viewModel.showDiagnosticLog.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val namelessFilter by viewModel.showNamelessFilter.collectAsState()
    val emailOnlyFilter by viewModel.showEmailOnlyFilter.collectAsState()
    val hasEmailFilter by viewModel.hasEmailFilter.collectAsState()
    val hasAddressFilter by viewModel.hasAddressFilter.collectAsState()
    val googleFilter by viewModel.googleFilter.collectAsState()
    val metaFilter by viewModel.metaFilter.collectAsState()
    val appleFilter by viewModel.appleFilter.collectAsState()
    val deviceFilter by viewModel.deviceFilter.collectAsState()
    val pendingEnrichmentFilter by viewModel.pendingEnrichmentFilter.collectAsState()
    val showManualEntryDialog by viewModel.showManualEntryDialog.collectAsState()

    // The action sheet tracks only an id; the full row is observed inside it.
    var selectedTargetIdForActions by rememberSaveable { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val filters = listOf(
        googleFilter, metaFilter, appleFilter, deviceFilter,
        namelessFilter, emailOnlyFilter, hasEmailFilter, hasAddressFilter, pendingEnrichmentFilter
    )
    val filtering = filters.any { it != null }

    val context = LocalContext.current
    fun granted() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var contactsAllowed by remember { mutableStateOf(granted()) }
    val contactsPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        contactsAllowed = granted()
        if (result[android.Manifest.permission.READ_CONTACTS] == true) {
            viewModel.sweepContacts()
        } else if ((context as? android.app.Activity)?.let {
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, android.Manifest.permission.READ_CONTACTS)
            } == true
        ) {
            // Android answered without asking (refused for good before): system settings can still allow it.
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
            )
        }
    }
    // Re-read when coming back from system settings.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val now = granted()
            if (now && !contactsAllowed) viewModel.sweepContacts()
            contactsAllowed = now
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (showDiagnosticLog) DiagnosticLogView(diagnosticLogs)
        if (!rememberNotificationsAllowed()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(ALERTS_OFF, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                AppTextAction("Turn on alerts", { openNotificationSettings(context) })
            }
        }

        AzTextBox(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            hint = "Search contacts",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            historyContext = "registry_search",
            onSubmit = { viewModel.setSearchQuery(it) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (filtering) {
                AssistChip(onClick = { viewModel.clearFilters() }, label = { Text("Clear filters") },
                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) })
            }
            TriStateFilterChip(state = googleFilter, onToggle = { viewModel.setGoogleFilter(it) }, label = "Google")
            TriStateFilterChip(state = metaFilter, onToggle = { viewModel.setMetaFilter(it) }, label = "Meta")
            TriStateFilterChip(state = appleFilter, onToggle = { viewModel.setAppleFilter(it) }, label = "Apple")
            TriStateFilterChip(state = deviceFilter, onToggle = { viewModel.setDeviceFilter(it) }, label = "This phone")
            TriStateFilterChip(state = namelessFilter, onToggle = { viewModel.setNamelessFilter(it) }, label = "No name")
            TriStateFilterChip(state = emailOnlyFilter, onToggle = { viewModel.setEmailOnlyFilter(it) }, label = "Email only")
            TriStateFilterChip(state = hasEmailFilter, onToggle = { viewModel.setEmailFilter(it) }, label = "Has email")
            TriStateFilterChip(state = hasAddressFilter, onToggle = { viewModel.setAddressFilter(it) }, label = "Has address")
            TriStateFilterChip(state = pendingEnrichmentFilter, onToggle = { viewModel.setPendingEnrichmentFilter(it) }, label = "Needs details")
        }

        if (targets.itemCount == 0 && targets.loadState.refresh is LoadState.NotLoading) {
            // A search or filter that matched nothing, versus an empty list: the latter is where
            // a new user lands after onboarding, so it says what to do next.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(Spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    if (searchQuery.isNotBlank() || filtering) {
                        Text("No contacts match.", style = MaterialTheme.typography.titleMedium)
                        AppCaption("A filter tapped once shows only those contacts, twice hides them, three times clears it.")
                        AppTextAction("Clear search and filters", { viewModel.setSearchQuery(""); viewModel.clearFilters() })
                    } else if (!contactsAllowed) {
                        Text("The app can't see your contacts.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Allow access to read the people on your phone. You can still add someone by hand.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        AppAction("Allow access to contacts", {
                            contactsPermission.launch(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS))
                        })
                        AppTextAction("Add someone by hand", { viewModel.setShowManualEntryDialog(true) })
                    } else {
                        Text("Start by importing your contacts.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This reads the people saved on your phone. You can also add someone by hand.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        AppAction("Import contacts", { viewModel.sweepContacts() })
                        AppTextAction("Add someone by hand", { viewModel.setShowManualEntryDialog(true) })
                    }
                }
            }
        } else {
            // The bottom clears the navigation bar (the app draws edge to edge).
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = Spacing.md, top = Spacing.md, end = Spacing.md, bottom = Spacing.md + navBar),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(count = targets.itemCount, key = targets.itemKey { it.id }) { index ->
                    val target: TargetLite? = targets[index]
                    if (target != null) {
                        TargetItem(
                            target = target,
                            onClick = { onTargetClick(target.id) },
                            onLongClick = { selectedTargetIdForActions = target.id }
                        )
                    }
                }
                if (targets.loadState.append is LoadState.Loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { AzLoad() }
                    }
                }
            }
        }
    }

    if (showManualEntryDialog) {
        ManualEntryDialog(
            onDismiss = { viewModel.setShowManualEntryDialog(false) },
            onConfirm = { name, phone, email ->
                viewModel.addManualTarget(name, phone, email)
                viewModel.setShowManualEntryDialog(false)
            }
        )
    }

    selectedTargetIdForActions?.let { selectedId ->
        ModalBottomSheet(
            onDismissRequest = { selectedTargetIdForActions = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            TargetActionMenu(
                targetId = selectedId,
                onOpen = { selectedTargetIdForActions = null; onTargetClick(selectedId) },
                onDone = { selectedTargetIdForActions = null },
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun DiagnosticLogView(logs: List<DiagnosticLogger.LogEntry>) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logListState = rememberLazyListState()

    // Entries are newest first: follow the top as they arrive (keyed on the newest entry,
    // since the count stops changing once the log is full).
    LaunchedEffect(logs.firstOrNull()) {
        if (logs.isNotEmpty()) logListState.animateScrollToItem(0)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = Spacing.sm)) {
                AppSection("Activity log", Modifier.weight(1f))
                val fullLog = { logs.joinToString("\n") { "[${it.timestamp}] ${it.message}" } }
                IconButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Activity log", fullLog()))
                        )
                    }
                }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy log") }
                IconButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, fullLog())
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share activity log"))
                }) { Icon(Icons.Default.Share, contentDescription = "Share log") }
            }
            AppCaption("Contacts' names appear here. Share it only with someone you'd show your contacts to.", Modifier.padding(horizontal = Spacing.sm))
            LazyColumn(
                state = logListState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.sm)
            ) {
                items(logs) { entry ->
                    Text(
                        text = "[${entry.timestamp}] ${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.level) {
                            DiagnosticLogger.LogEntry.LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            DiagnosticLogger.LogEntry.LogLevel.WARN -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/** Long-press actions: open the contact, or archive / bring it back. */
@Composable
fun TargetActionMenu(
    targetId: Int,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    viewModel: MainViewModel
) {
    val fullTarget by viewModel.observeTarget(targetId).collectAsState(initial = null)
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xl)) {
        fullTarget?.let { Text(it.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md)) }
        ListItem(
            headlineContent = { Text("Open") },
            supportingContent = { Text("Details, checks and searches") },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.clickable(onClick = onOpen)
        )
        // Until the row loads, don't offer an action that might be the wrong one.
        when (fullTarget?.status) {
            null -> Unit
            TargetStatus.IGNORED -> ListItem(
                headlineContent = { Text("Bring back to daily checks") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable { viewModel.restoreTarget(targetId); onDone() }
            )
            else -> ListItem(
                headlineContent = { Text("Archive") },
                supportingContent = { Text("Stop checking and hide from the list. Show archived (on the rail) brings it back.") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable { viewModel.ignoreTarget(targetId); onDone() }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TargetItem(target: TargetLite, onClick: () -> Unit, onLongClick: () -> Unit) {
    val spec = statusSpec(target.status, target.matchKind, target.monitorabilityState)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = "Open",
                onLongClickLabel = "Archive or bring back"
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (spec.needsUser) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(target.displayName, style = MaterialTheme.typography.titleMedium)
                target.phoneNumber?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                target.email?.let { AppCaption(it) }
                target.areaCode?.let { AppCaption(if (it == "LOCAL") "Local number" else "Area code $it") }
            }
            StatusBadge(spec)
        }
    }
}

/** A filter that cycles: all, only these, none of these. */
@Composable
fun TriStateFilterChip(
    state: Boolean?,
    onToggle: (Boolean?) -> Unit,
    label: String
) {
    FilterChip(
        selected = state != null,
        onClick = {
            onToggle(
                when (state) {
                    null -> true
                    true -> false
                    false -> null
                }
            )
        },
        label = {
            Text(
                when (state) {
                    true -> "Only: $label"
                    false -> "Hide: $label"
                    null -> label
                }
            )
        },
        leadingIcon = if (state != null) {
            {
                Icon(
                    imageVector = if (state) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else null
    )
}

@Composable
fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?) -> Unit
) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var phone by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var email by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var missing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    AppDialog(
        title = "Add someone",
        confirm = "Add",
        onConfirm = {
            if (name.isBlank() || (phone.isBlank() && email.isBlank())) missing = true
            else onConfirm(name.trim(), phone.takeIf { it.isNotBlank() }, email.takeIf { it.isNotBlank() })
        },
        onDismiss = onDismiss
    ) {
        AppCaption("For someone who isn't in your phone's contacts. A full name and a phone number or email are needed to check them.")
        AzTextBox(value = name, onValueChange = { name = it; missing = false }, hint = "Full name", modifier = Modifier.fillMaxWidth(), onSubmit = {})
        AzTextBox(value = phone, onValueChange = { phone = it; missing = false }, hint = "Phone number", modifier = Modifier.fillMaxWidth(), onSubmit = {})
        AzTextBox(value = email, onValueChange = { email = it; missing = false }, hint = "Email", modifier = Modifier.fillMaxWidth(), onSubmit = {})
        if (missing) Text("Add a name, and a phone number or an email.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
