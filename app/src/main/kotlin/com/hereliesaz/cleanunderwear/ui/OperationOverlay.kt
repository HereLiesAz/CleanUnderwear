package com.hereliesaz.cleanunderwear.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hereliesaz.cleanunderwear.ui.MainViewModel.Continues
import com.hereliesaz.cleanunderwear.ui.MainViewModel.OperationState

/**
 * The busy screen. While an operation runs, everything behind it (rail included) is darkened
 * and, where the device supports it, blurred; the loader and what is happening sit in the
 * middle, Cancel at the bottom. An operation that keeps going on its own ([Continues]) says so
 * and can be put aside with "Keep using the app", leaving [OperationBar] in its place.
 *
 * It is a window of its own so it covers the rail, takes every touch, and can blur what is
 * behind it. Always drawn dark: a darkened screen behind light text, in either theme.
 */
@Composable
fun OperationOverlay(
    state: OperationState,
    onCancel: () -> Unit,
    onKeepUsingApp: () -> Unit
) {
    Dialog(
        // Back puts aside what can be put aside; anything else waits for Cancel or the end.
        onDismissRequest = { if (state.continues != Continues.NO) onKeepUsingApp() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BlurBehind()
        MaterialTheme(colorScheme = SurveillanceDarkColorScheme, typography = MaterialTheme.typography) {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    LoadingCaption(state.description.ifBlank { "Working…" })
                    if (state.progress in 0f..1f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
                            color = MaterialTheme.colorScheme.onBackground,
                            trackColor = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    continuesNote(state.continues)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        AppTextAction("Keep using the app", onKeepUsingApp)
                    }
                    if (state.cancellable) {
                        AppAction("Cancel", onCancel)
                    } else {
                        AppCaption("This can't be stopped partway. It only takes a moment.")
                    }
                }
            }
        }
    }
}

/**
 * Where an operation put aside goes: one line at the bottom of the screen saying it is still
 * running, with a way back to it and a way to stop it.
 */
@Composable
fun OperationBar(state: OperationState, onShow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClickLabel = "Show progress", onClick = onShow),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column {
            if (state.progress in 0f..1f) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.description.ifBlank { "Working…" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AppCaption(
                        if (state.continues == Continues.ANYWHERE) "Running. You can leave the app; it keeps going."
                        else "Running while you use the app."
                    )
                }
                if (state.cancellable) AppTextAction("Cancel", onCancel)
            }
        }
    }
}

private fun continuesNote(continues: Continues): String? = when (continues) {
    Continues.NO -> null
    Continues.IN_APP -> "This keeps going while you use the rest of the app."
    Continues.ANYWHERE -> "This keeps going while you use the rest of the app, and after you leave it."
}

/**
 * Darkens the dialog's window and blurs what is behind it. Blur needs Android 12 and a device
 * that allows it (it is off in battery saver, for one); without it the screen is darkened more,
 * so what is behind still recedes.
 */
@Composable
private fun BlurBehind() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            window.windowManager.isCrossWindowBlurEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = BLUR_RADIUS_PX }
        }
        window.setDimAmount(if (blurs) 0.6f else 0.85f)
    }
}

private const val BLUR_RADIUS_PX = 60
