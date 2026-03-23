package com.trisandhya.sunrisealarm.alarm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.Toast

/**
 * Helper for creating alarms in the system Clock app via AlarmClock intent.
 *
 * Uses ACTION_SET_ALARM which integrates with the user's existing clock/alarm app.
 * EXTRA_SKIP_UI = true silently creates the alarm without opening the clock UI.
 */
object AlarmHelper {

    data class AlarmResult(
        val label: String,
        val hour: Int,
        val minute: Int,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Sets a single alarm in the system clock app.
     *
     * @param context Application context
     * @param hour    Hour in 24-hour format (0-23)
     * @param minute  Minute (0-59)
     * @param label   Display label shown in the clock app
     * @param skipUi  If true, alarm is created silently (default: true)
     * @return        AlarmResult indicating success/failure
     */
    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        label: String,
        skipUi: Boolean = true
    ): AlarmResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AlarmResult(label, hour, minute, success = true)
        } catch (e: Exception) {
            AlarmResult(label, hour, minute, success = false, errorMessage = e.message)
        }
    }

    /**
     * Sets multiple alarms, one for each item in the list.
     *
     * @param context   Application context
     * @param alarmItems List of (label, hour, minute) triples
     * @return          List of AlarmResult for each alarm
     */
    fun setAlarms(
        context: Context,
        alarmItems: List<Triple<String, Int, Int>>
    ): List<AlarmResult> {
        return alarmItems.map { (label, hour, minute) ->
            setAlarm(context, hour, minute, label)
        }
    }

    /**
     * Formats an hour/minute pair into a human-readable string.
     * e.g., 6, 30 → "06:30 AM"
     */
    fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "%02d:%02d %s".format(displayHour, minute, amPm)
    }
}
