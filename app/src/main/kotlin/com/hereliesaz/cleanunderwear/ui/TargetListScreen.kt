package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.hereliesaz.aznavrail.AzLoad
import com.hereliesaz.aznavrail.AzTextBox
import com.hereliesaz.cleanunderwear.R
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
    var explainSettings by rememberSaveable { mutableStateOf(false) }
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
            // Android answered without asking (refused for good before): system settings can still
            // allow it, but the user is told why they are being sent there before they are.
            explainSettings = true
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

    val refresh = targets.loadState.refresh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .contentWidth()
    ) {
        if (showDiagnosticLog) DiagnosticLogView(diagnosticLogs)
        if (!rememberNotificationsAllowed()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(stringResource(R.string.alerts_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                AppTextAction(stringResource(R.string.turn_on_alerts), { openNotificationSettings(context) })
            }
        }

        // AzTextBox keeps submitted text as autocomplete history; the application turns that off
        // (CleanUnderwearApplication), because what is searched is contacts' names.
        AzTextBox(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            hint = stringResource(R.string.list_search_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            onSubmit = { viewModel.setSearchQuery(it) }
        )

        // "Clear filters" is pinned at the end, outside the scrolling chips: it used to be
        // inserted at the front of the row, which pushed every chip along under the finger
        // after the first tap.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TriStateFilterChip(state = googleFilter, onToggle = { viewModel.setGoogleFilter(it) }, label = stringResource(R.string.filter_google))
                TriStateFilterChip(state = metaFilter, onToggle = { viewModel.setMetaFilter(it) }, label = stringResource(R.string.filter_meta))
                TriStateFilterChip(state = appleFilter, onToggle = { viewModel.setAppleFilter(it) }, label = stringResource(R.string.filter_apple))
                TriStateFilterChip(state = deviceFilter, onToggle = { viewModel.setDeviceFilter(it) }, label = stringResource(R.string.filter_this_phone))
                TriStateFilterChip(state = namelessFilter, onToggle = { viewModel.setNamelessFilter(it) }, label = stringResource(R.string.filter_no_name))
                TriStateFilterChip(state = emailOnlyFilter, onToggle = { viewModel.setEmailOnlyFilter(it) }, label = stringResource(R.string.filter_email_only))
                TriStateFilterChip(state = hasEmailFilter, onToggle = { viewModel.setEmailFilter(it) }, label = stringResource(R.string.filter_has_email))
                TriStateFilterChip(state = hasAddressFilter, onToggle = { viewModel.setAddressFilter(it) }, label = stringResource(R.string.filter_has_address))
                TriStateFilterChip(state = pendingEnrichmentFilter, onToggle = { viewModel.setPendingEnrichmentFilter(it) }, label = stringResource(R.string.filter_needs_details))
            }
            if (filtering) {
                AssistChip(onClick = { viewModel.clearFilters() }, label = { Text(stringResource(R.string.filter_clear)) },
                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) })
            }
        }

        if (targets.itemCount == 0 && refresh is LoadState.Loading) {
            // The first read of the list: not an empty list, so no "start by importing" yet.
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LoadingCaption(stringResource(R.string.list_loading))
            }
        } else if (targets.itemCount == 0 && refresh is LoadState.Error) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(Spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.list_load_failed), style = MaterialTheme.typography.titleMedium)
                    AppCaption(stringResource(R.string.list_load_failed_caption))
                    AppAction(stringResource(R.string.try_again), { targets.retry() })
                }
            }
        } else if (targets.itemCount == 0 && refresh is LoadState.NotLoading) {
            // A search or filter that matched nothing, versus an empty list: the latter is where
            // a new user lands after onboarding, so it says what to do next.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(Spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    if (searchQuery.isNotBlank() || filtering) {
                        Text(stringResource(R.string.list_no_match), style = MaterialTheme.typography.titleMedium)
                        AppCaption(stringResource(R.string.list_filter_hint))
                        AppTextAction(stringResource(R.string.list_clear_search_and_filters), { viewModel.setSearchQuery(""); viewModel.clearFilters() })
                    } else if (!contactsAllowed) {
                        Text(stringResource(R.string.list_cant_see_contacts), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.list_allow_access_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        AppAction(stringResource(R.string.list_allow_access), {
                            contactsPermission.launch(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS))
                        })
                        AppTextAction(stringResource(R.string.list_add_by_hand), { viewModel.setShowManualEntryDialog(true) })
                    } else {
                        Text(stringResource(R.string.list_start_importing), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.list_start_importing_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        AppAction(stringResource(R.string.list_import_contacts), { viewModel.sweepContacts() })
                        AppTextAction(stringResource(R.string.list_add_by_hand), { viewModel.setShowManualEntryDialog(true) })
                    }
                }
            }
        } else {
            // The bottom clears the navigation bar (the app draws edge to edge) and the bar of an
            // operation put aside, which is drawn over the bottom of the screen.
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomBar = LocalBottomBarHeight.current
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = Spacing.md, top = Spacing.md, end = Spacing.md, bottom = Spacing.md + navBar + bottomBar),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (refresh is LoadState.Error) {
                    // The list on screen is from before; say it could not be refreshed.
                    item {
                        LoadFailedRow(stringResource(R.string.list_refresh_failed), onRetry = { targets.retry() })
                    }
                }
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
                } else if (targets.loadState.append is LoadState.Error) {
                    item {
                        LoadFailedRow(stringResource(R.string.list_more_failed), onRetry = { targets.retry() })
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

    if (explainSettings) {
        // Android won't show its question again; say why the settings page opens next.
        AppDialog(
            title = stringResource(R.string.list_settings_title),
            confirm = stringResource(R.string.list_open_settings),
            onConfirm = {
                explainSettings = false
                try {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    )
                } catch (e: android.content.ActivityNotFoundException) {
                    // Nothing on this device opens settings.
                }
            },
            onDismiss = { explainSettings = false },
            dismiss = stringResource(R.string.not_now)
        ) {
            Text(stringResource(R.string.list_settings_body))
        }
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
private fun LoadFailedRow(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        AppTextAction(stringResource(R.string.try_again), onRetry)
    }
}

@Composable
private fun DiagnosticLogView(logs: List<DiagnosticLogger.LogEntry>) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logListState = rememberLazyListState()
    val logTitle = stringResource(R.string.log_title)
    val shareTitle = stringResource(R.string.log_share_title)
    // A third of the screen's height, within bounds, so the panel neither fills a phone on its
    // side nor shrinks to nothing at a large font size.
    val panelHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.3f).coerceIn(140.dp, 280.dp)
    val keys = remember(logs) { activityLogKeys(logs) }

    // Entries are newest first: follow the top as they arrive (keyed on the newest entry, since
    // the count stops changing once the log is full), but not while the reader has scrolled down
    // to older lines. Jumping, not animating: an animation restarted by every entry never settles.
    LaunchedEffect(logs.firstOrNull()) {
        if (logs.isNotEmpty() && logListState.firstVisibleItemIndex <= 1) logListState.scrollToItem(0)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(panelHeight),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = Spacing.sm)) {
                AppSection(logTitle, Modifier.weight(1f))
                val fullLog = { logs.joinToString("\n") { "[${it.timestamp}] ${it.message}" } }
                IconButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(logTitle, fullLog()))
                        )
                    }
                }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.log_copy)) }
                IconButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, fullLog())
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, shareTitle))
                }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.log_share)) }
            }
            AppCaption(stringResource(R.string.log_caption), Modifier.padding(horizontal = Spacing.sm))
            LazyColumn(
                state = logListState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.sm)
            ) {
                itemsIndexed(logs, key = { index, _ -> keys[index] }) { _, entry ->
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
    val snackbars = LocalAppSnackbars.current
    val archivedMessage = stringResource(R.string.list_archived_snackbar, fullTarget?.displayName.orEmpty())
    val undoLabel = stringResource(R.string.undo)
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xl)) {
        fullTarget?.let { Text(it.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md)) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_open)) },
            supportingContent = { Text(stringResource(R.string.menu_open_caption)) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.clickable(onClick = onOpen)
        )
        // Until the row loads, don't offer an action that might be the wrong one.
        when (fullTarget?.status) {
            null -> Unit
            TargetStatus.IGNORED -> ListItem(
                headlineContent = { Text(stringResource(R.string.bring_back_to_checks)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable { viewModel.restoreTarget(targetId); onDone() }
            )
            else -> ListItem(
                headlineContent = { Text(stringResource(R.string.archive)) },
                supportingContent = { Text(stringResource(R.string.menu_archive_caption)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable {
                    viewModel.ignoreTarget(targetId)
                    // Undo is the existing restore, which brings back the status it replaced.
                    snackbars?.show(archivedMessage, undoLabel) { viewModel.restoreTarget(targetId) }
                    onDone()
                }
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
                onClickLabel = stringResource(R.string.menu_open),
                // The long press opens a menu: Open, and Archive or Bring back.
                onLongClickLabel = stringResource(R.string.list_more_actions)
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
                target.areaCode?.let {
                    AppCaption(if (it == "LOCAL") stringResource(R.string.list_local_number) else stringResource(R.string.list_area_code, it))
                }
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
                    true -> stringResource(R.string.filter_only, label)
                    false -> stringResource(R.string.filter_hide, label)
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
    var problem by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<ManualEntryProblem?>(null) }

    // The same check for the Add button and the keyboard's Done.
    fun submit() {
        val found = validateManualEntry(name, phone, email)
        if (found != null) problem = found
        else onConfirm(name.trim(), phone.trim().takeIf { it.isNotEmpty() }, email.trim().takeIf { it.isNotEmpty() })
    }

    AppDialog(
        title = stringResource(R.string.add_title),
        confirm = stringResource(R.string.add_confirm),
        onConfirm = { submit() },
        onDismiss = onDismiss
    ) {
        AppCaption(stringResource(R.string.add_caption))
        AzTextBox(value = name, onValueChange = { name = it; problem = null }, hint = stringResource(R.string.add_full_name), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(), onSubmit = { submit() })
        AzTextBox(value = phone, onValueChange = { phone = it; problem = null }, hint = stringResource(R.string.add_phone), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), onSubmit = { submit() })
        AzTextBox(value = email, onValueChange = { email = it; problem = null }, hint = stringResource(R.string.add_email), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), onSubmit = { submit() })
        problem?.let { found ->
            Text(
                stringResource(
                    when (found) {
                        ManualEntryProblem.NAME -> R.string.add_problem_name
                        ManualEntryProblem.NO_PHONE_OR_EMAIL -> R.string.add_problem_contact
                        ManualEntryProblem.PHONE -> R.string.add_problem_phone
                        ManualEntryProblem.EMAIL -> R.string.add_problem_email
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                // Said aloud when it appears, not only drawn.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
