package com.trisandhya.sunrisealarm.alarm

import android.Manifest
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Builds and posts the alarm notification, and owns its channel. */
object AlarmNotifier {

    private const val CHANNEL_ID = "sandhya_alarms"

    // Added to a junction's request code so each action button gets a distinct
    // PendingIntent, separate from the alarm PendingIntents in SandhyaScheduler.
    private const val SNOOZE_ACTION_OFFSET = 2000
    private const val DISMISS_ACTION_OFFSET = 3000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_alarms),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alarms_description)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                attributes
            )
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /** True when we are actually allowed to post; false is not an error worth crashing over. */
    fun notify(context: Context, junction: Junction, firedAt: ZonedDateTime) {
        ensureChannel(context)

        // Without POST_NOTIFICATIONS the post below would throw. Returning early keeps
        // the caller's re-arm intact, so a denied permission costs today's notification
        // rather than the whole schedule. Kept inline so lint can see the guard.
        //
        // The SDK_INT half is load-bearing, not defensive: POST_NOTIFICATIONS does not
        // exist before API 33, so an unguarded check reports DENIED on API 26-32 and
        // would suppress every notification on those versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val label = context.getString(junction.labelRes)
        val timeText = firedAt.format(
            DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        )

        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlarmActivity.EXTRA_JUNCTION, junction.key)
            putExtra(AlarmActivity.EXTRA_TIME, timeText)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            junction.requestCode,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sandhya_notification)
            .setContentTitle(context.getString(R.string.alarm_title, label))
            .setContentText(context.getString(R.string.alarm_body, timeText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPending)
            // Wakes the screen when locked; degrades to a heads-up banner otherwise.
            .setFullScreenIntent(fullScreenPending, true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .addAction(
                0,
                context.getString(R.string.snooze),
                actionIntent(context, junction, SandhyaScheduler.ACTION_SNOOZE, SNOOZE_ACTION_OFFSET)
            )
            .addAction(
                0,
                context.getString(R.string.dismiss),
                actionIntent(context, junction, SandhyaScheduler.ACTION_DISMISS, DISMISS_ACTION_OFFSET)
            )
            .build()

        NotificationManagerCompat.from(context).notify(junction.requestCode, notification)
    }

    /** Broadcast PendingIntent for a notification action button, keyed off the junction. */
    private fun actionIntent(
        context: Context,
        junction: Junction,
        action: String,
        codeOffset: Int
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(SandhyaScheduler.EXTRA_JUNCTION, junction.key)
        }
        return PendingIntent.getBroadcast(
            context,
            junction.requestCode + codeOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(context: Context, junction: Junction) {
        NotificationManagerCompat.from(context).cancel(junction.requestCode)
    }
}
