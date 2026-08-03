package com.trisandhya.sunrisealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.trisandhya.sunrisealarm.R
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.model.Junction
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Handles everything that arrives at alarm time or from the alarm's action buttons.
 *
 * On a junction firing it shows the alarm and re-arms the next day — the re-arm is
 * what makes the app set-and-forget: each firing schedules the next day's occurrence
 * from freshly computed solar times, so the schedule tracks seasonal drift without
 * the user reopening the app. The Snooze and Dismiss actions (from the notification
 * or the full-screen alarm) route here too.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val junction = Junction.fromKey(intent.getStringExtra(SandhyaScheduler.EXTRA_JUNCTION))
            ?: return

        val scheduler = SandhyaScheduler(context)

        when (intent.action) {
            SandhyaScheduler.ACTION_SNOOZE -> snooze(context, junction, scheduler, intent)

            SandhyaScheduler.ACTION_CANCEL_SNOOZE -> {
                scheduler.cancelSnooze(junction)
                AlarmNotifier.cancelSnoozed(context, junction)
                Toast.makeText(
                    context, context.getString(R.string.snooze_cancelled_toast), Toast.LENGTH_SHORT
                ).show()
            }

            SandhyaScheduler.ACTION_DISMISS -> {
                AlarmSoundService.stop(context)
                AlarmNotifier.cancel(context, junction)
                AlarmNotifier.cancelSnoozed(context, junction)
                scheduler.cancelSnooze(junction)
            }

            else -> onAlarmFired(context, junction, intent, scheduler)
        }
    }

    private fun onAlarmFired(
        context: Context,
        junction: Junction,
        intent: Intent,
        scheduler: SandhyaScheduler
    ) {
        val prefs = SandhyaPrefs(context)

        // This firing supersedes any snooze that led here, so clear its status chip.
        AlarmNotifier.cancelSnoozed(context, junction)

        // The user may have switched this junction off while the alarm was pending.
        if (!prefs.isEnabled(junction)) {
            scheduler.cancel(junction)
            return
        }

        startAlarmSound(context, junction, eventTime(context, junction, intent))

        // A snooze re-fire must not re-arm the daily alarm: tomorrow was already
        // scheduled when the original alarm fired. Only the real daily firing re-arms.
        val isSnooze = intent.getBooleanExtra(SandhyaScheduler.EXTRA_IS_SNOOZE, false)
        if (!isSnooze) scheduler.schedule(junction)
    }

    /**
     * Starts the foreground service that rings and shows the alarm. If it can't start
     * (e.g. background-start blocked), falls back to a plain notification whose channel
     * carries the default alarm sound, so the alarm is never silent.
     */
    private fun startAlarmSound(context: Context, junction: Junction, eventTime: ZonedDateTime) {
        val intent = Intent(context, AlarmSoundService::class.java).apply {
            action = AlarmSoundService.ACTION_PLAY
            putExtra(SandhyaScheduler.EXTRA_JUNCTION, junction.key)
            putExtra(SandhyaScheduler.EXTRA_EVENT_TIME, eventTime.toInstant().toEpochMilli())
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            AlarmNotifier.notifyFallback(context, junction, eventTime)
        }
    }

    private fun snooze(
        context: Context,
        junction: Junction,
        scheduler: SandhyaScheduler,
        intent: Intent
    ) {
        AlarmSoundService.stop(context)
        AlarmNotifier.cancel(context, junction)
        val eventMillis = eventTime(context, junction, intent).toInstant().toEpochMilli()
        val fireAt = scheduler.snooze(junction, eventMillis)
        if (fireAt != null) {
            // Post the ongoing "snoozed — rings at HH:MM" status so the pending re-ring
            // has a visible Cancel handle for its whole life.
            AlarmNotifier.showSnoozed(context, junction, fireAt)
            Toast.makeText(
                context,
                context.getString(R.string.snoozed_toast, SandhyaPrefs(context).snoozeMinutes(junction)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * The event this alarm is for. Read from the intent; if absent (e.g. an alarm
     * armed by an older build), fall back to now-plus-offset, which equals the event
     * at the moment a daily alarm fires.
     */
    private fun eventTime(context: Context, junction: Junction, intent: Intent): ZonedDateTime {
        val millis = intent.getLongExtra(SandhyaScheduler.EXTRA_EVENT_TIME, 0L)
        return if (millis > 0L) {
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        } else {
            ZonedDateTime.now().plusMinutes(SandhyaPrefs(context).offsetMinutes(junction).toLong())
        }
    }
}
