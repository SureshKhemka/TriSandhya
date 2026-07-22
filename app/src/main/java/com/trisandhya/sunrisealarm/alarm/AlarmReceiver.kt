package com.trisandhya.sunrisealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.trisandhya.sunrisealarm.R
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.model.Junction
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
            SandhyaScheduler.ACTION_SNOOZE -> snooze(context, junction, scheduler)

            SandhyaScheduler.ACTION_DISMISS -> {
                AlarmNotifier.cancel(context, junction)
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

        // The user may have switched this junction off while the alarm was pending.
        if (!prefs.isEnabled(junction)) {
            scheduler.cancel(junction)
            return
        }

        AlarmNotifier.notify(context, junction, ZonedDateTime.now())

        // A snooze re-fire must not re-arm the daily alarm: tomorrow was already
        // scheduled when the original alarm fired. Only the real daily firing re-arms.
        val isSnooze = intent.getBooleanExtra(SandhyaScheduler.EXTRA_IS_SNOOZE, false)
        if (!isSnooze) scheduler.schedule(junction)
    }

    private fun snooze(context: Context, junction: Junction, scheduler: SandhyaScheduler) {
        AlarmNotifier.cancel(context, junction)
        val fireAt = scheduler.snooze(junction)
        if (fireAt != null) {
            Toast.makeText(
                context,
                context.getString(R.string.snoozed_toast, SandhyaPrefs(context).snoozeMinutes()),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
