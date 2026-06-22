package com.hereliesaz.cleanunderwear.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hereliesaz.cleanunderwear.MainActivity
import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "panopticon_alerts"
        private const val CHANNEL_NAME = "Panopticon Status Alerts"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Alerts when a target's status in the ledger changes."
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun notifyStatusChange(target: Target, oldStatus: TargetStatus) {
        // UNVERIFIED is an internal queue state (name needs identity enrichment),
        // not a user-actionable hit — never wake the user for it.
        if (target.status == TargetStatus.UNVERIFIED) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (target.status) {
            TargetStatus.POSSIBLE_MATCH -> "may be incarcerated — tap to review and confirm."
            TargetStatus.INCARCERATED -> "has been incarcerated."
            TargetStatus.DECEASED -> "has passed away."
            TargetStatus.MONITORING -> "is back under monitoring."
            else -> "status has changed to ${target.status}."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Generic alert icon
            .setContentTitle("Ledger Update: ${target.displayName}")
            .setContentText("${target.displayName} $statusText")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            // Check for permission is handled at the call site or by system on older versions
            try {
                notify(target.id, builder.build())
            } catch (e: SecurityException) {
                // Permission not granted
            }
        }
    }
}
