package com.trisandhya.sunrisealarm.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.model.Junction
import com.trisandhya.sunrisealarm.solar.SolarCalculator
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Owns alarm scheduling via [AlarmManager].
 *
 * This replaces the previous approach of firing `AlarmClock.ACTION_SET_ALARM`
 * intents at the system clock app, which had three problems it could not solve:
 *
 *  1. It only ran when the user manually opened the app and tapped a button, so
 *     alarms had to be re-created by hand every single day.
 *  2. `ACTION_SET_ALARM` carries only an hour and minute, so the clock app fired
 *     at the *next occurrence* of that time — which for an evening junction meant
 *     today rather than the day whose times were displayed.
 *  3. It reported success whenever `startActivity` did not throw, which proved
 *     only that some app accepted the intent, not that an alarm existed.
 *
 * Scheduling against a precise instant fixes all three: the target is an absolute
 * epoch millis, and the alarm re-arms itself from the background.
 */
class SandhyaScheduler(private val context: Context) {

    private val prefs = SandhyaPrefs(context)
    private val alarmManager = context.getSystemService<AlarmManager>()

    /**
     * The instant a junction's alarm should next fire, offset applied.
     *
     * Starts from today and walks forward. Walking rather than assuming "today or
     * tomorrow" matters inside the polar circles, where sunrise and sunset can be
     * absent for weeks at a time.
     */
    fun nextOccurrence(
        junction: Junction,
        now: ZonedDateTime = ZonedDateTime.now()
    ): ZonedDateTime? {
        val lat = prefs.latitude ?: return null
        val lng = prefs.longitude ?: return null
        val zone: ZoneId = now.zone
        val offset = prefs.offsetMinutes(junction).toLong()

        var date: LocalDate = now.toLocalDate()
        repeat(SEARCH_HORIZON_DAYS) {
            val times = SolarCalculator.compute(date, lat, lng, zone)
            val base = when (junction) {
                Junction.SUNRISE -> times.sunrise
                Junction.SOLAR_NOON -> times.solarNoon
                Junction.SUNSET -> times.sunset
            }
            val fireAt = base?.minusMinutes(offset)
            if (fireAt != null && fireAt.isAfter(now)) return fireAt
            date = date.plusDays(1)
        }
        return null
    }

    /** The soonest upcoming alarm across all enabled junctions, or null if none. */
    fun nextEnabledAlarm(now: ZonedDateTime = ZonedDateTime.now()): Pair<Junction, ZonedDateTime>? =
        Junction.entries
            .filter { prefs.isEnabled(it) }
            .mapNotNull { j -> nextOccurrence(j, now)?.let { j to it } }
            .minByOrNull { it.second }

    /** Re-arms every enabled junction and cancels the rest. */
    fun syncAll() {
        Junction.entries.forEach { junction ->
            if (prefs.isEnabled(junction)) schedule(junction) else cancel(junction)
        }
    }

    /**
     * Schedules the next firing of one junction.
     *
     * @return the scheduled instant, or null if it could not be scheduled.
     */
    @SuppressLint("MissingPermission")
    fun schedule(junction: Junction): ZonedDateTime? {
        val fireAt = nextOccurrence(junction) ?: return null
        return armAt(junction, fireAt, isSnooze = false)
    }

    /**
     * Pushes a just-fired alarm back by the configured snooze duration.
     *
     * The snooze fires through a *separate* PendingIntent from the daily alarm, so it
     * never overwrites tomorrow's already-scheduled occurrence. Chained snoozes work
     * because FLAG_UPDATE_CURRENT replaces the previous snooze in place.
     *
     * @return the instant the snooze will fire, or null if it could not be scheduled.
     */
    @SuppressLint("MissingPermission")
    fun snooze(junction: Junction, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val fireAt = now.plusMinutes(prefs.snoozeMinutes().toLong())
        return armAt(junction, fireAt, isSnooze = true)
    }

    @SuppressLint("MissingPermission")
    private fun armAt(junction: Junction, fireAt: ZonedDateTime, isSnooze: Boolean): ZonedDateTime? {
        val manager = alarmManager ?: return null
        val triggerAt = fireAt.toInstant().toEpochMilli()
        val operation = alarmPendingIntent(junction, isSnooze)

        if (canScheduleExact()) {
            // A devotional alarm is time-critical; setExactAndAllowWhileIdle is the
            // only variant that survives Doze without being deferred.
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        } else {
            // Exact permission revoked: still fire, just without the exactness
            // guarantee, rather than silently scheduling nothing.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
        return fireAt
    }

    fun cancel(junction: Junction) {
        alarmManager?.cancel(alarmPendingIntent(junction, isSnooze = false))
        cancelSnooze(junction)
    }

    fun cancelSnooze(junction: Junction) {
        alarmManager?.cancel(alarmPendingIntent(junction, isSnooze = true))
    }

    fun cancelAll() = Junction.entries.forEach { cancel(it) }

    /** False on Android 12+ when the user has revoked the exact-alarm permission. */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    private fun alarmPendingIntent(junction: Junction, isSnooze: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_SANDHYA_ALARM
            putExtra(EXTRA_JUNCTION, junction.key)
            putExtra(EXTRA_IS_SNOOZE, isSnooze)
        }
        // Distinct request code so the snooze one-shot and the daily alarm are two
        // separate PendingIntents that never clobber each other.
        val requestCode = if (isSnooze) junction.requestCode + SNOOZE_CODE_OFFSET else junction.requestCode
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    companion object {
        const val ACTION_SANDHYA_ALARM = "com.trisandhya.sunrisealarm.ACTION_SANDHYA_ALARM"
        const val ACTION_SNOOZE = "com.trisandhya.sunrisealarm.ACTION_SNOOZE"
        const val ACTION_DISMISS = "com.trisandhya.sunrisealarm.ACTION_DISMISS"
        const val EXTRA_JUNCTION = "junction"
        const val EXTRA_IS_SNOOZE = "is_snooze"

        /** Added to a junction's request code to key its snooze PendingIntent apart. */
        const val SNOOZE_CODE_OFFSET = 1000

        /** Long enough to cross a polar winter and still find the next sunrise. */
        private const val SEARCH_HORIZON_DAYS = 400
    }
}
