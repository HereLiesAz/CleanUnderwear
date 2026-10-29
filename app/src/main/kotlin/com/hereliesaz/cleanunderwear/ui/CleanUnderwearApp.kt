package com.hereliesaz.cleanunderwear.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.data.Target

@Composable
fun CleanUnderwearApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    val operationState by viewModel.operationState.collectAsState()
    val operationInBackground by viewModel.operationInBackground.collectAsState()
    val showDiagnosticLog by viewModel.showDiagnosticLog.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val showIgnored by viewModel.showIgnored.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val choices by viewModel.choices.collectAsState()

    // Monochrome: the active item and focus share the one accent.
    val activeColor = MaterialTheme.colorScheme.primary
    val highlightedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.background
    var confirmLookUp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    // The rail is configured in a DSL that is not composable, so its words are looked up here.
    val resources = LocalContext.current.resources
    fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    AzHostActivityLayout(
        navController = navController,
        modifier = Modifier.fillMaxSize(),
        currentDestination = currentDestination?.route,
        isLandscape = isLandscape,
        initiallyExpanded = false
    ) {
        azConfig(
            dockingSide = AzDockingSide.LEFT,
            packButtons = true,
        )

        azTheme(
            activeColor = activeColor,
            focusColor = highlightedColor,
            secondaryColor = highlightedColor,
            translucentBackground = backgroundColor.copy(alpha = 0.90f),
            defaultShape = AzButtonShape.RECTANGLE
        )

        // Keys are the rail item ids below, so every item has its help line.
        azAdvanced(
            helpEnabled = true,
            helpList = mapOf(
                "registry" to str(R.string.help_registry),
                "review" to str(R.string.help_review),
                "choices" to str(R.string.help_choices),
                "harvest" to str(R.string.help_harvest),
                "ingest" to str(R.string.help_ingest),
                "update" to str(R.string.help_update),
                "resolve" to str(R.string.help_resolve),
                "intelligence_ops" to str(R.string.help_actions),
                "sort_host" to str(R.string.help_sort),
                "sort_name" to str(R.string.help_sort_name),
                "sort_status" to str(R.string.help_sort_status),
                "sort_date" to str(R.string.help_sort_date),
                "archives" to str(R.string.help_archives),
                "settings" to str(R.string.help_settings),
                "diagnostic_log" to str(R.string.help_log)
            )
        )

        if (isOnboardingCompleted) {
            // Top-down: see the list, act on it, arrange it, then configure.
            azRailItem(id = "registry", text = str(R.string.rail_contacts), route = "targetList", info = str(R.string.rail_contacts_info))
            azRailItem(id = "review", text = str(R.string.rail_review), route = "review", info = str(R.string.rail_review_info))
            if (choices.isNotEmpty()) {
                azRailItem(id = "choices", text = str(R.string.choices_rail_item, choices.size), route = "choices", info = str(R.string.rail_choices_info))
            }

            azRailHostItem(id = "intelligence_ops", text = str(R.string.rail_actions))
            azRailSubItem(id = "harvest", hostId = "intelligence_ops", text = str(R.string.rail_import), shape = AzButtonShape.NONE,
                onClick = { viewModel.sweepContacts() })
            azRailSubItem(id = "ingest", hostId = "intelligence_ops", text = str(R.string.rail_add_someone), shape = AzButtonShape.NONE,
                onClick = { viewModel.setShowManualEntryDialog(true) })
            azRailSubItem(id = "update", hostId = "intelligence_ops", text = str(R.string.rail_check_now), shape = AzButtonShape.NONE,
                onClick = { viewModel.triggerManualInterrogation() })
            azRailSubItem(id = "resolve", hostId = "intelligence_ops", text = str(R.string.rail_fill_in), shape = AzButtonShape.NONE,
                onClick = { scope.launch { confirmLookUp = viewModel.resolveCandidateCount() } })

            // The active sort is ticked, so the list's order is never a guess.
            fun sortLabel(order: MainViewModel.SortOrder, text: String) = if (sortOrder == order) str(R.string.rail_sort_ticked, text) else text
            azRailHostItem(id = "sort_host", text = str(R.string.rail_sort))
            azRailSubItem(id = "sort_name", hostId = "sort_host", text = sortLabel(MainViewModel.SortOrder.NAME, str(R.string.rail_sort_name)), shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.NAME) })
            azRailSubItem(id = "sort_status", hostId = "sort_host", text = sortLabel(MainViewModel.SortOrder.STATUS, str(R.string.rail_sort_status)), shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.STATUS) })
            azRailSubItem(id = "sort_date", hostId = "sort_host", text = sortLabel(MainViewModel.SortOrder.DATE, str(R.string.rail_sort_date)), shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.DATE) })

            azRailToggle(
                id = "archives",
                isChecked = showIgnored,
                toggleOnText = str(R.string.rail_hide_archived),
                toggleOffText = str(R.string.rail_show_archived),
                info = str(R.string.rail_archived_info)
            ) {
                viewModel.toggleShowIgnored()
            }

            azDivider()

            azRailItem(id = "settings", text = str(R.string.settings_title), route = "settings")

            azMenuToggle(
                id = "diagnostic_log",
                isChecked = showDiagnosticLog,
                toggleOnText = str(R.string.rail_hide_log),
                toggleOffText = str(R.string.rail_show_log),
                onClick = { viewModel.setShowDiagnosticLog(!showDiagnosticLog) }
            )
        }

        onscreen {
            val updateState by viewModel.updateState.collectAsState()
            // Not during onboarding: the first screens are the explanation and the permission ask.
            LaunchedEffect(isOnboardingCompleted) { if (isOnboardingCompleted) viewModel.checkForUpdates(manual = false) }
            updateState.prompt?.takeIf { isOnboardingCompleted }?.let { update ->
                UpdateDialog(
                    update = update,
                    installed = viewModel.installedVersion,
                    playWebUrl = viewModel.playWebUrl(),
                    onDismiss = { viewModel.dismissUpdatePrompt() }
                )
            }

            // Mission overlay: when a BrowserMission is active, the visible
            // WebView screen takes over the whole content area. This honors
            // the rule that cyberbackgroundchecks / Facebook flows must use
            // the user's real session, not a covert WebView.
            val activeBatch by viewModel.activeMissionBatch.collectAsState()
            val batch = activeBatch

            val startDestination = if (isOnboardingCompleted) "targetList" else "onboarding"

            // A run of lookups that found people ends here, to choose who's who.
            val openChoices by viewModel.openChoices.collectAsState()
            LaunchedEffect(openChoices, isOnboardingCompleted) {
                if (!openChoices || !isOnboardingCompleted) return@LaunchedEffect
                navController.navigate("choices") { launchSingleTop = true }
                viewModel.consumeOpenChoices()
            }

            val openTarget by viewModel.openTarget.collectAsState()
            LaunchedEffect(openTarget, isOnboardingCompleted) {
                val id = openTarget ?: return@LaunchedEffect
                if (!isOnboardingCompleted) return@LaunchedEffect
                navController.navigate("targetDetail/$id") { launchSingleTop = true }
                viewModel.consumeOpenTarget()
            }

            // One-line results ("Imported 42 contacts", "No new matches"), above the bar if it shows.
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
            // Screens raise their own messages with an action (Undo) through this; it runs in the
            // app's scope, so the message outlives the screen that raised it.
            val appSnackbars = remember(snackbar, scope) { AppSnackbars(snackbar, scope) }
            // The bar of an operation put aside covers the bottom of the screen: its measured
            // height, not a guess, keeps the snackbar and the lists clear of it.
            val barShown = operationState.isRunning && operationInBackground
            val density = LocalDensity.current
            var barHeight by remember { mutableStateOf(0.dp) }
            val bottomBarHeight = if (barShown) barHeight else 0.dp

            Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalAppSnackbars provides appSnackbars, LocalBottomBarHeight provides bottomBarHeight) {
            NavHost(navController = navController, startDestination = startDestination) {
                composable("onboarding") {
                    OnboardingScreen(onComplete = {
                        viewModel.completeOnboarding()
                        navController.navigate("targetList") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    })
                }
                composable("targetList") {
                    TargetListScreen(
                        viewModel = viewModel,
                        onTargetClick = { targetId ->
                            // Single top: a double tap must not stack two of the same screen.
                            navController.navigate("targetDetail/$targetId") { launchSingleTop = true }
                        }
                    )
                }
                composable("review") {
                    ReviewScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable("choices") {
                    ChoicesScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = "targetDetail/{targetId}",
                    arguments = listOf(navArgument("targetId") { type = NavType.IntType })
                ) { backStackEntry ->
                    val targetId = backStackEntry.arguments?.getInt("targetId") ?: return@composable
                    // Loading and gone are told apart (both used to be a blank screen with no way
                    // back): the observed row is null while it loads and again if it is deleted.
                    val loaded by produceState<TargetLoad>(initialValue = TargetLoad.Loading, targetId) {
                        viewModel.observeTarget(targetId).collect { value = if (it == null) TargetLoad.Missing else TargetLoad.Found(it) }
                    }

                    val state = loaded
                    if (state !is TargetLoad.Found) {
                        TargetUnavailable(loading = state is TargetLoad.Loading, onBack = { navController.popBackStack() })
                    } else {
                        TargetDetailScreen(
                            target = state.target,
                            sourceCatalog = viewModel.sourceCatalog,
                            onUpdateTarget = { shown, change -> viewModel.updateTarget(shown, change) },
                            onLaunchMission = { mission, onResult ->
                                viewModel.launchMission(mission, onResult)
                            },
                            onLookUp = { viewModel.resolveOne(it) },
                            onArchive = { viewModel.ignoreTarget(it) },
                            onRestore = { viewModel.restoreTarget(it) },
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
            }

            // Mission overlay: the visible WebView screen takes over the whole content area
            // (the lookups and imports must use the user's real session, not a covert WebView).
            // It sits above the NavHost instead of replacing it, so the screens underneath keep
            // their scroll and expanded-card state while a mission runs. The empty pointer
            // handler makes this layer the only thing a touch can reach.
            if (batch != null) {
                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {}) {
                    // Keep the same BrowserScreen/WebView across Resolve contacts.
                    // The batch id resets mission state; tagged results reject late callbacks.
                    BrowserScreen(
                        batchId = batch.id,
                        missions = batch.missions,
                        onMissionResult = { mission, outcome ->
                            if (outcome is MissionOutcome.Extracted) {
                                batch.onMissionExtracted(mission, outcome.html)
                            }
                        },
                        onAllComplete = { viewModel.completeMissionBatch() },
                        onCancel = { viewModel.cancelMissionBatch() },
                        isBlocked = batch.isBlocked,
                        context = operationState.description.takeIf { operationState.isRunning },
                        stopsBatch = batch.retainBrowserForResolve,
                        findings = if (operationState.isRunning) operationState.findings else emptyList(),
                    )
                }
            }

            // A running operation covers the app (OperationOverlay), unless the user put one
            // that keeps going aside; then a bar at the bottom says it is still running.
            // Not over the browser, which shows the operation's context itself.
            if (batch == null && operationState.isRunning) {
                if (operationInBackground) {
                    OperationBar(
                        state = operationState,
                        onShow = viewModel::showOperation,
                        onCancel = viewModel::cancelOperation,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                    )
                } else {
                    OperationOverlay(
                        state = operationState,
                        onCancel = viewModel::cancelOperation,
                        onKeepUsingApp = viewModel::sendOperationToBackground
                    )
                }
            }

            if (batch == null) {
                SnackbarHost(
                    snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = bottomBarHeight)
                )
            }
            }
        }
    }

    confirmLookUp?.let { count ->
        if (count == 0) {
            // Nothing to confirm, so one button; a second would only do the same.
            AppDialog(
                title = stringResource(R.string.fill_nothing_title),
                confirm = stringResource(R.string.ok),
                onConfirm = { confirmLookUp = null },
                onDismiss = { confirmLookUp = null },
                dismiss = null
            ) { Text(stringResource(R.string.fill_nothing_body)) }
        } else {
            AppDialog(
                title = pluralStringResource(R.plurals.fill_title, count, count),
                confirm = stringResource(R.string.fill_start),
                onConfirm = { confirmLookUp = null; viewModel.resolveUnverifiedBatch() },
                onDismiss = { confirmLookUp = null }
            ) {
                Text(stringResource(R.string.fill_body))
            }
        }
    }
}

/** What the detail route knows of its contact: still reading it, found it, or it is gone. */
private sealed interface TargetLoad {
    data object Loading : TargetLoad
    data object Missing : TargetLoad
    data class Found(val target: Target) : TargetLoad
}

/**
 * The detail route when there is no contact to show: still loading, or deleted (by Delete
 * everything, or a contact read that dropped it). The shell stays, with a way back.
 */
@Composable
private fun TargetUnavailable(loading: Boolean, onBack: () -> Unit) {
    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.gone_title), onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = screenInsets()
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
            if (loading) {
                LoadingCaption(null)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.gone_heading), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    AppCaption(stringResource(R.string.gone_caption))
                    AppAction(stringResource(R.string.gone_back), onBack)
                }
            }
        }
    }
}
