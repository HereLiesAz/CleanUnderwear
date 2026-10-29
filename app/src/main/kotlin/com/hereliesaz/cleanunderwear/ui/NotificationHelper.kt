package com.hereliesaz.cleanunderwear.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

/**
 * Tells the user a daily check turned up (or cleared) a record that may be about one of their
 * contacts.
 *
 * The news can be grave and is often about someone else with the same name, so every alert
 * says so, shows nothing personal on the lock screen, and opens the contact it is about. The
 * user can turn off alerts about possible deaths, or about arrests and custody, in Settings
 * ([PREF_NOTIFY_DEATH], [PREF_NOTIFY_CUSTODY]); the match still shows in the app.
 */
@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        // A channel's importance can't be lowered once created, so the default-importance
        // channel has a new id and the old high-importance one is deleted.
        private const val CHANNEL_ID = "possible_matches"
        private const val OLD_CHANNEL_ID = "panopticon_alerts"
        /**
         * Alerts are posted under this tag, with the contact's id as the notification id. The
         * tag keeps them apart from untagged notifications, such as the daily check's ongoing
         * one, whose fixed id would otherwise replace (or be removed with) a contact's alert.
         */
        const val ALERT_TAG = "possible_match"
        const val EXTRA_TARGET_ID = "com.hereliesaz.cleanunderwear.TARGET_ID"
        const val PREFS = "clean_underwear_prefs"
        const val PREF_NOTIFY_DEATH = "notify_death"
        const val PREF_NOTIFY_CUSTODY = "notify_custody"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun notifyStatusChange(target: Target, oldStatus: TargetStatus) {
        val isDeath = target.matchKind == "DEATH"
        val (title, body) = when (target.status) {
            TargetStatus.POSSIBLE_MATCH -> {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (isDeath && !prefs.getBoolean(PREF_NOTIFY_DEATH, true)) return
                if (!isDeath && !prefs.getBoolean(PREF_NOTIFY_CUSTODY, true)) return
                val found = when (target.matchKind) {
                    "DEATH" -> R.string.notif_match_obituary
                    "ARREST" -> R.string.notif_match_arrest
                    else -> R.string.notif_match_custody
                }
                context.getString(R.string.notif_match_title, target.displayName) to
                    context.getString(found, target.displayName)
            }
            TargetStatus.MONITORING -> if (oldStatus == TargetStatus.POSSIBLE_MATCH) {
                context.getString(R.string.notif_cleared_title) to context.getString(R.string.notif_cleared_body, target.displayName)
            } else return
            else -> return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET_ID, target.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, target.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // What the lock screen shows instead: no name, no claim.
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.hereliesaz.cleanunderwear.R.drawable.ic_stat_briefs)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_public_text))
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.hereliesaz.cleanunderwear.R.drawable.ic_stat_briefs)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(ALERT_TAG, target.id, builder.build())
        } catch (e: SecurityException) {
            // Notifications not allowed; the match is still in the app.
        }
    }
}
