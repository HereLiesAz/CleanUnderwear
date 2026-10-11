package com.hereliesaz.cleanunderwear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.AzButtonShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val showDiagnosticLog by viewModel.showDiagnosticLog.collectAsState()
    val globalLimit by viewModel.globalTargetLimit.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Settings") },
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
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Covert Mode (Dark Theme)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Use a darker theme in low light.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AzToggle(
                    isChecked = isDarkTheme,
                    onToggle = { viewModel.setDarkTheme(it) },
                    toggleOnText = "Dark",
                    toggleOffText = "Light"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Display Diagnostic Log", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Show real-time under-the-hood activity on the main screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AzToggle(
                    isChecked = showDiagnosticLog,
                    onToggle = { viewModel.setShowDiagnosticLog(it) },
                    toggleOnText = "Log On",
                    toggleOffText = "Log Off"
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Contact Limit", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Maximum number of contacts to monitor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                AzRoller(
                    options = listOf(50, 100, 250, 500).map { it.toString() },
                    selectedOption = globalLimit.toString(),
                    onOptionSelected = { viewModel.setGlobalTargetLimit(it.toInt()) }
                )
            }

            HorizontalDivider()

            Text(
                text = "Permissions",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            PermissionStatusItem(
                label = "Contact Access",
                isGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionStatusItem(
                    label = "Status Notifications",
                    isGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )
            }

            AzButton(
                text = "Manage App Permissions",
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = AzButtonShape.RECTANGLE
            )

            HorizontalDivider()

            Text(
                text = "Import From Other Accounts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                "To harvest contacts from Apple, Meta, or other platforms, ensure those accounts are synced to this device in System Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AzButton(
                text = "Connect New Account",
                onClick = {
                    val intent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                        putExtra(Settings.EXTRA_AUTHORITIES, arrayOf("com.android.contacts"))
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = AzButtonShape.RECTANGLE
            )
            
            AzButton(
                text = "Import Facebook Friends",
                onClick = { viewModel.harvestFacebook() },
                modifier = Modifier.fillMaxWidth(),
                shape = AzButtonShape.RECTANGLE
            )

            AzButton(
                text = "Import WhatsApp Chats",
                onClick = { viewModel.harvestWhatsApp() },
                modifier = Modifier.fillMaxWidth(),
                shape = AzButtonShape.RECTANGLE
            )

            AzButton(
                text = "Import Instagram Followers",
                onClick = { viewModel.harvestInstagram() },
                modifier = Modifier.fillMaxWidth(),
                shape = AzButtonShape.RECTANGLE
            )

            AzButton(
                text = "Pull Google Contacts",
                onClick = { viewModel.harvestGoogleContacts() },
                modifier = Modifier.fillMaxWidth(),
                shape = AzButtonShape.RECTANGLE
            )

            HorizontalDivider()
        }
    }
}

@Composable
fun PermissionStatusItem(label: String, isGranted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isGranted) "Granted" else "Denied",
                style = MaterialTheme.typography.labelMedium,
                color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
