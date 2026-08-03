package com.trisandhya.sunrisealarm.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.trisandhya.sunrisealarm.R
import com.trisandhya.sunrisealarm.model.Junction
import com.trisandhya.sunrisealarm.ui.AlarmActivity
import com.trisandhya.sunrisealarm.util.AlarmPhrasing
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Builds and posts the alarm and snoozed-status notifications, and owns their channels. */
object AlarmNotifier {

    // Bumped from the original "sandhya_alarms": a channel's sound is immutable once
    // created, and the alarm sound is now played by AlarmSoundService (looping, and
    // user-selectable), so this channel is silent. The legacy channel is deleted.
    private const val ALARM_CHANNEL_ID = "sandhya_alarms_v2"
    private const val LEGACY_ALARM_CHANNEL_ID = "sandhya_alarms"

    // Fallback channel that carries the default alarm sound, used only if the
    // foreground playback service cannot start (so the alarm is never silent).
    private const val ALARM_FALLBACK_CHANNEL_ID = "sandhya_alarms_fallback"

    private const val SNOOZED_CHANNEL_ID = "sandhya_snoozed"

    // Added to a junction's request code so each action button gets a distinct
    // PendingIntent, separate from the alarm PendingIntents in SandhyaScheduler.
    private const val SNOOZE_ACTION_OFFSET = 2000
    private const val DISMISS_ACTION_OFFSET = 3000
    private const val CANCEL_ACTION_OFFSET = 4000

    // Keeps the low-priority "snoozed" notification on its own id, distinct from the
    // ringing alarm notification (which uses junction.requestCode).
    private const val SNOOZED_NOTIF_OFFSET = 5000

    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        // The old sound-carrying channel is obsolete now that the service plays sound.
        manager.deleteNotificationChannel(LEGACY_ALARM_CHANNEL_ID)

        if (manager.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
            // Silent, but still HIGH so it heads-up and shows full-screen; the service
            // provides the (looping, user-chosen) sound.
            manager.createNotificationChannel(
                NotificationChannel(
                    ALARM_CHANNEL_ID,
                    context.getString(R.string.channel_alarms),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.channel_alarms_description)
                    setSound(null, null)
                    enableVibration(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
            )
        }

        if (manager.getNotificationChannel(ALARM_FALLBACK_CHANNEL_ID) == null) {
            val attributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            manager.createNotificationChannel(
                NotificationChannel(
                    ALARM_FALLBACK_CHANNEL_ID,
                    context.getString(R.string.channel_alarms),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attributes)
                    enableVibration(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
            )
        }

        if (manager.getNotificationChannel(SNOOZED_CHANNEL_ID) == null) {
            // Silent and low: this is a status chip the user can act on, not an alert.
            manager.createNotificationChannel(
                NotificationChannel(
                    SNOOZED_CHANNEL_ID,
                    context.getString(R.string.channel_snoozed),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.channel_snoozed_description)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
            )
        }
    }

    /** Notification id for a junction's ringing alarm (also the service's FGS id). */
    fun alarmNotificationId(junction: Junction): Int = junction.requestCode

    /**
     * Builds the ringing-alarm notification (full-screen intent + Snooze/Dismiss). The
     * service posts this via startForeground; [loud] = true uses the sound-carrying
     * fallback channel for the no-service path.
     */
    fun buildAlarmNotification(
        context: Context,
        junction: Junction,
        eventTime: ZonedDateTime,
        loud: Boolean = false
    ): Notification {
        ensureChannels(context)
        val eventMillis = eventTime.toInstant().toEpochMilli()

        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlarmActivity.EXTRA_JUNCTION, junction.key)
            putExtra(SandhyaScheduler.EXTRA_EVENT_TIME, eventMillis)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            junction.requestCode,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (loud) ALARM_FALLBACK_CHANNEL_ID else ALARM_CHANNEL_ID
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_sandhya_notification)
            .setContentTitle(AlarmPhrasing.title(context, junction, eventTime, ZonedDateTime.now()))
            .setContentText(AlarmPhrasing.body(context, junction, eventTime))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(!loud) // the FGS notification is ongoing until dismissed/timeout
            .setContentIntent(fullScreenPending)
            // Wakes the screen when locked; degrades to a heads-up banner otherwise.
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(
                0,
                context.getString(R.string.snooze),
                // Carries the event so a snoozed re-ring still refers to the same event.
                actionIntent(context, junction, SandhyaScheduler.ACTION_SNOOZE, SNOOZE_ACTION_OFFSET, eventMillis)
            )
            .addAction(
                0,
                context.getString(R.string.dismiss),
                actionIntent(context, junction, SandhyaScheduler.ACTION_DISMISS, DISMISS_ACTION_OFFSET)
            )
            .build()
    }

    /** Fallback post used only when the playback service cannot start; sound via channel. */
    fun notifyFallback(context: Context, junction: Junction, eventTime: ZonedDateTime) {
        // Guard kept inline so lint's MissingPermission flow analysis can see it; it
        // does not follow into helper methods. The SDK_INT half is load-bearing:
        // POST_NOTIFICATIONS does not exist before API 33, so an unguarded check
        // reports DENIED on API 26-32 and would suppress every notification there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context)
            .notify(alarmNotificationId(junction), buildAlarmNotification(context, junction, eventTime, loud = true))
    }

    /**
     * Posts the ongoing "snoozed — rings at HH:MM" status with a Cancel action.
     *
     * This is the answer to "once snoozed there's no way to cancel it": the pending
     * re-ring now has a visible, dismissible handle the whole time it is armed.
     */
    fun showSnoozed(context: Context, junction: Junction, ringAt: ZonedDateTime) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val label = context.getString(junction.labelRes)
        val ringText = ringAt.format(timeFormat)

        val notification = NotificationCompat.Builder(context, SNOOZED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sandhya_notification)
            .setContentTitle(context.getString(R.string.snoozed_title, label))
            .setContentText(context.getString(R.string.snoozed_body, ringText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                0,
                context.getString(R.string.cancel_snooze),
                actionIntent(context, junction, SandhyaScheduler.ACTION_CANCEL_SNOOZE, CANCEL_ACTION_OFFSET)
            )
            .build()

        NotificationManagerCompat.from(context)
            .notify(junction.requestCode + SNOOZED_NOTIF_OFFSET, notification)
    }

    /** Broadcast PendingIntent for a notification action button, keyed off the junction. */
    private fun actionIntent(
        context: Context,
        junction: Junction,
        action: String,
        codeOffset: Int,
        eventMillis: Long = 0L
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(SandhyaScheduler.EXTRA_JUNCTION, junction.key)
            putExtra(SandhyaScheduler.EXTRA_EVENT_TIME, eventMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            junction.requestCode + codeOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Cancels the ringing alarm notification. */
    fun cancel(context: Context, junction: Junction) {
        NotificationManagerCompat.from(context).cancel(junction.requestCode)
    }

    /** Cancels the low-priority "snoozed" status notification. */
    fun cancelSnoozed(context: Context, junction: Junction) {
        NotificationManagerCompat.from(context).cancel(junction.requestCode + SNOOZED_NOTIF_OFFSET)
    }
}
