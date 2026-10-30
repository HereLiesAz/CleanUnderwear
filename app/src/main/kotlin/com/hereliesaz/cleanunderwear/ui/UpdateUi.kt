package com.hereliesaz.cleanunderwear.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hereliesaz.cleanunderwear.R
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
        title = stringResource(R.string.update_title),
        confirm = stringResource(if (update.channel == UpdateChannel.PLAY) R.string.settings_open_play else R.string.update_open_release),
        onConfirm = {
            openUpdate(context, update, playWebUrl)
            onDismiss()
        },
        onDismiss = onDismiss,
        dismiss = stringResource(R.string.update_later)
    ) {
        Text(
            when (update.channel) {
                UpdateChannel.PLAY -> stringResource(R.string.update_body_play, update.version, installed)
                UpdateChannel.GITHUB -> stringResource(R.string.update_body_github, update.version, installed)
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
