package com.hereliesaz.cleanunderwear.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.cleanunderwear.update.UpdateChannel
import com.hereliesaz.cleanunderwear.update.UpdateResult

/** Announces an available update and, on "Update", takes the user to where it is. */
@Composable
fun UpdateDialog(
    update: UpdateResult.Available,
    installed: String,
    playWebUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            Text(
                when (update.channel) {
                    UpdateChannel.PLAY -> "A newer version (${update.version}) is on Google Play. You have $installed."
                    UpdateChannel.GITHUB -> "Version ${update.version} is out on GitHub. You have $installed."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                openUpdate(context, update, playWebUrl)
                onDismiss()
            }) { Text(if (update.channel == UpdateChannel.PLAY) "Open Play Store" else "Open on GitHub") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

/** Opens the update's page: the Play Store app (or its web listing if absent), or the GitHub release. */
fun openUpdate(context: Context, update: UpdateResult.Available, playWebUrl: String) {
    fun view(url: String) = context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    try {
        view(update.url)
    } catch (e: ActivityNotFoundException) {
        if (update.channel == UpdateChannel.PLAY) view(playWebUrl)
    }
}
