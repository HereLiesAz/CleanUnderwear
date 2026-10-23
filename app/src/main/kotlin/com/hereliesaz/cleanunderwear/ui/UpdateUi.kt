package com.hereliesaz.cleanunderwear.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
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
    AppDialog(
        title = "Update available",
        confirm = if (update.channel == UpdateChannel.PLAY) "Open Google Play" else "Open the release",
        onConfirm = {
            openUpdate(context, update, playWebUrl)
            onDismiss()
        },
        onDismiss = onDismiss,
        dismiss = "Later"
    ) {
        Text(
            when (update.channel) {
                UpdateChannel.PLAY -> "Version ${update.version} is on Google Play. You have $installed."
                UpdateChannel.GITHUB -> "Version ${update.version} is out. You have $installed. On the release page, " +
                    "download the file ending in .apk and open it to install; Android may ask you to allow installs from your browser."
            }
        )
    }
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
