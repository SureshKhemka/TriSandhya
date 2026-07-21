package com.trisandhya.sunrisealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.model.Junction
import java.time.ZonedDateTime

/**
 * Fires at a junction, shows the alarm, then immediately re-arms itself.
 *
 * The re-arm is what makes the app set-and-forget: each firing schedules the next
 * day's occurrence using freshly computed solar times, so the schedule tracks the
 * seasonal drift without the user ever reopening the app.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val junction = Junction.fromKey(intent.getStringExtra(SandhyaScheduler.EXTRA_JUNCTION))
            ?: return

        val prefs = SandhyaPrefs(context)
        val scheduler = SandhyaScheduler(context)

        // The user may have switched this junction off while the alarm was pending.
        if (!prefs.isEnabled(junction)) {
            scheduler.cancel(junction)
            return
        }

        AlarmNotifier.notify(context, junction, ZonedDateTime.now())

        // Re-arm last so that a failure to notify still leaves tomorrow scheduled.
        scheduler.schedule(junction)
    }
}
