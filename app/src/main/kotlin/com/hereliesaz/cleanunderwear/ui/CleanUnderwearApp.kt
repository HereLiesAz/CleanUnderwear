package com.hereliesaz.cleanunderwear.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
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
    val showDiagnosticLog by viewModel.showDiagnosticLog.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val showIgnored by viewModel.showIgnored.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    // Monochrome: the active item and focus share the one accent.
    val activeColor = MaterialTheme.colorScheme.primary
    val highlightedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.background
    var confirmLookUp by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

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
                "registry" to "Everyone the app checks, with what the last check found.",
                "review" to "Contacts that may be two people combined, or have an out-of-date number.",
                "harvest" to "Read the people saved on your phone into the app.",
                "ingest" to "Add someone who isn't in your phone's contacts.",
                "update" to "Run the daily check now instead of waiting for it.",
                "resolve" to "Look up missing names, numbers and ages on a people-search site, contact by contact. Asks first.",
                "archives" to "Show or hide contacts you archived.",
                "settings" to "Alerts, check time, imports, your data.",
                "diagnostic_log" to "Show what the app is doing, step by step."
            )
        )

        if (isOnboardingCompleted) {
            // Top-down: see the list, act on it, arrange it, then configure.
            azRailItem(id = "registry", text = "Contacts", route = "targetList", info = "Everyone the app checks.")
            azRailItem(id = "review", text = "Review", route = "review", info = "Possible wrong combinations and old numbers.")

            azRailHostItem(id = "intelligence_ops", text = "Actions")
            azRailSubItem(id = "harvest", hostId = "intelligence_ops", text = "Import contacts", shape = AzButtonShape.NONE,
                onClick = { viewModel.sweepContacts() })
            azRailSubItem(id = "ingest", hostId = "intelligence_ops", text = "Add someone", shape = AzButtonShape.NONE,
                onClick = { viewModel.setShowManualEntryDialog(true) })
            azRailSubItem(id = "update", hostId = "intelligence_ops", text = "Check now", shape = AzButtonShape.NONE,
                onClick = { viewModel.triggerManualInterrogation() })
            azRailSubItem(id = "resolve", hostId = "intelligence_ops", text = "Fill in details", shape = AzButtonShape.NONE,
                onClick = { scope.launch { confirmLookUp = viewModel.resolveCandidateCount() } })

            azRailHostItem(id = "sort_host", text = "Sort")
            azRailSubItem(id = "sort_name", hostId = "sort_host", text = "By name", shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.NAME) })
            azRailSubItem(id = "sort_status", hostId = "sort_host", text = "By status", shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.STATUS) })
            azRailSubItem(id = "sort_date", hostId = "sort_host", text = "By last check", shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.DATE) })

            azRailToggle(
                id = "archives",
                isChecked = showIgnored,
                toggleOnText = "Hide archived",
                toggleOffText = "Show archived",
                info = "Show or hide archived contacts."
            ) {
                viewModel.toggleShowIgnored()
            }

            azDivider()

            azRailItem(id = "settings", text = "Settings", route = "settings")

            azMenuToggle(
                id = "diagnostic_log",
                isChecked = showDiagnosticLog,
                toggleOnText = "Hide log",
                toggleOffText = "Show log",
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
            if (batch != null) {
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
                    )
                return@onscreen
            }

            val startDestination = if (isOnboardingCompleted) "targetList" else "onboarding"

            val openTarget by viewModel.openTarget.collectAsState()
            LaunchedEffect(openTarget, isOnboardingCompleted) {
                val id = openTarget ?: return@LaunchedEffect
                if (!isOnboardingCompleted) return@LaunchedEffect
                navController.navigate("targetDetail/$id") { launchSingleTop = true }
                viewModel.consumeOpenTarget()
            }

            Box(modifier = Modifier.fillMaxSize()) {
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
                            navController.navigate("targetDetail/$targetId")
                        }
                    )
                }
                composable("review") {
                    ReviewScreen(
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
                    val target by viewModel
                        .observeTarget(targetId)
                        .collectAsState(initial = null)

                    val current = target
                    if (current != null) {
                        TargetDetailScreen(
                            target = current,
                            sourceCatalog = viewModel.sourceCatalog,
                            onUpdateTarget = { viewModel.updateTarget(it) },
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
            // The rail's global loading overlay swallows every touch, so the busy indicator is
            // drawn here instead: the same AzLoad, with no input handling, so taps pass through.
            if (operationState.isRunning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingCaption(operationState.description.ifBlank { "Working…" })
                }
            }

            // One-line results ("Imported 42 contacts", "No new matches").
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }

    confirmLookUp?.let { count ->
        if (count == 0) {
            AppDialog(
                title = "Nothing to fill in",
                confirm = "OK",
                onConfirm = { confirmLookUp = null },
                onDismiss = { confirmLookUp = null },
                dismiss = "Close"
            ) { Text("Every contact already has a full name and an age.") }
        } else {
            AppDialog(
                title = "Fill in details for $count contact${if (count == 1) "" else "s"}?",
                confirm = "Start",
                onConfirm = { confirmLookUp = null; viewModel.resolveUnverifiedBatch() },
                onDismiss = { confirmLookUp = null }
            ) {
                Text(
                    "Each contact missing a full name or an age is looked up on CyberBackgroundChecks, a people-search " +
                        "site, in the app's browser, using the name, number or email you have for them. It can take " +
                        "a minute or more per contact, and you may be asked to solve CAPTCHAs. You can stop at any time; " +
                        "what was found so far is kept."
                )
            }
        }
    }
}
