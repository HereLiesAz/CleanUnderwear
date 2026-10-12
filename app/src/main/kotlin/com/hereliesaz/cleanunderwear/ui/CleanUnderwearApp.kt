package com.hereliesaz.cleanunderwear.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val showDiagnosticLog by viewModel.showDiagnosticLog.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val showIgnored by viewModel.showIgnored.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    // The active/selected rail item stands out from the inactive items. White
    // reads well on the dark rail (the default theme); in light theme it would
    // be invisible, so fall back to the primary color there.
    val activeColor = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backgroundColor = MaterialTheme.colorScheme.background

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
            translucentBackground = backgroundColor.copy(alpha = 0.90f),
            defaultShape = AzButtonShape.RECTANGLE
        )

        azAdvanced(
            isLoading = operationState.isRunning,
            helpEnabled = true,
            helpList = mapOf(
                "registry" to "Your monitored contacts.",
                "ingest" to "Add a contact that didn't sync automatically.",
                "harvest" to "Import contacts from your device and connected accounts.",
                "interrogate" to "Force a real-time check against public rosters."
            )

        )

        if (isOnboardingCompleted) {
            // Rail order follows the user's journey top-down: see the list,
            // fill and process it (Operations), arrange it (Sort), hide the
            // noise (Archives), then configure (Settings).
            azRailItem(
                id = "registry",
                text = "Registry",
                route = "targetList",
                info = "View all monitored contacts."
            )

            azRailHostItem(
                id = "intelligence_ops",
                text = "Operations"
            )

            // Harvest leads: importing your contacts is the first thing to do.
            azRailSubItem(
                id = "harvest",
                hostId = "intelligence_ops",
                text = "Harvest",
                shape = AzButtonShape.NONE,
                onClick = { viewModel.sweepContacts() }
            )
            azRailSubItem(
                id = "ingest",
                hostId = "intelligence_ops",
                text = "Entry",
                shape = AzButtonShape.NONE,
                onClick = { viewModel.setShowManualEntryDialog(true) }
            )
            azRailSubItem(
                id = "update",
                hostId = "intelligence_ops",
                text = "Update",
                shape = AzButtonShape.NONE,
                onClick = { viewModel.triggerManualInterrogation() }
            )
            azRailSubItem(
                id = "resolve",
                hostId = "intelligence_ops",
                text = "Resolve",
                shape = AzButtonShape.NONE,
                onClick = { viewModel.resolveUnverifiedBatch() }
            )

            azRailHostItem(
                id = "sort_host",
                text = "Sort"
            )
            azRailSubItem(
                id = "sort_name",
                hostId = "sort_host",
                text = "By Name",
                shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.NAME) }
            )
            azRailSubItem(
                id = "sort_status",
                hostId = "sort_host",
                text = "By Status",
                shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.STATUS) }
            )
            azRailSubItem(
                id = "sort_date",
                hostId = "sort_host",
                text = "By Date",
                shape = AzButtonShape.NONE,
                onClick = { viewModel.setSortOrder(MainViewModel.SortOrder.DATE) }
            )

            azRailToggle(
                id = "archives",
                isChecked = showIgnored,
                toggleOnText = "Hide",
                toggleOffText = "Archives",
                info = "Show or hide archived contacts."
            ) {
                viewModel.toggleShowIgnored()
            }

            azDivider()

            azRailItem(
                id = "settings",
                text = "Settings",
                route = "settings"
            )
            
            azMenuToggle(
                id = "diagnostic_log",
                isChecked = showDiagnosticLog,
                toggleOnText = "Hide",
                toggleOffText = "Log",
                onClick = { viewModel.setShowDiagnosticLog(!showDiagnosticLog) }
            )
        }



        onscreen {
            // Mission overlay: when a BrowserMission is active, the visible
            // WebView screen takes over the whole content area. This honors
            // the rule that cyberbackgroundchecks / Facebook flows must use
            // the user's real session, not a covert WebView.
            val activeBatch by viewModel.activeMissionBatch.collectAsState()
            val batch = activeBatch
            if (batch != null) {
                BrowserScreen(
                    missions = batch.missions,
                    onMissionResult = { mission, outcome ->
                        if (outcome is MissionOutcome.Extracted) {
                            batch.onMissionExtracted(mission, outcome.html)
                        }
                    },
                    onAllComplete = { viewModel.completeMissionBatch() },
                    onCancel = { viewModel.cancelMissionBatch() },
                    isBlocked = batch.isBlocked,
                )
                return@onscreen
            }

            val startDestination = if (isOnboardingCompleted) "targetList" else "onboarding"

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
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
