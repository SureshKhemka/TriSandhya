package com.trisandhya.sunrisealarm.util

import android.content.Context
import com.trisandhya.sunrisealarm.R
import com.trisandhya.sunrisealarm.model.Junction
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Wording for a fired alarm.
 *
 * The alarm fires ahead of the event (by the user's configured offset), so it must
 * name the *upcoming* event and its time rather than implying the event is happening
 * at the moment the alarm rings. The absolute event time is always the anchor; the
 * relative phrase reflects whatever offset was configured, and reads sensibly after
 * a snooze that pushes the ring past the event.
 */
object AlarmPhrasing {

    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /** Within this window either side of the event, we simply say "now". */
    private const val NOW_WINDOW_SECONDS = 30L

    /** Notification title, e.g. "Sunset in 10 minutes" / "Sunset now" / "Sunset was 10 minutes ago". */
    fun title(context: Context, junction: Junction, event: ZonedDateTime, now: ZonedDateTime): String {
        val label = context.getString(junction.labelRes)
        val seconds = Duration.between(now, event).seconds
        return when {
            seconds >= NOW_WINDOW_SECONDS ->
                context.getString(R.string.alarm_title_before, label, duration(context, seconds))
            seconds <= -NOW_WINDOW_SECONDS ->
                context.getString(R.string.alarm_title_after, label, duration(context, -seconds))
            else -> context.getString(R.string.alarm_title_now, label)
        }
    }

    /** Notification body, e.g. "Sunset at 6:46 PM". */
    fun body(context: Context, junction: Junction, event: ZonedDateTime): String =
        context.getString(
            R.string.alarm_body_at,
            context.getString(junction.labelRes),
            event.format(timeFormat)
        )

    /** Relative phrase for the full-screen alarm, e.g. "in 10 minutes" / "now" / "10 minutes ago". */
    fun relative(context: Context, event: ZonedDateTime, now: ZonedDateTime): String {
        val seconds = Duration.between(now, event).seconds
        return when {
            seconds >= NOW_WINDOW_SECONDS ->
                context.getString(R.string.alarm_rel_before, duration(context, seconds))
            seconds <= -NOW_WINDOW_SECONDS ->
                context.getString(R.string.alarm_rel_after, duration(context, -seconds))
            else -> context.getString(R.string.alarm_rel_now)
        }
    }

    fun clock(event: ZonedDateTime): String = event.format(timeFormat)

    private fun duration(context: Context, seconds: Long): String {
        val minutes = Math.round(seconds / 60.0).toInt().coerceAtLeast(1)
        return when {
            minutes < 60 ->
                context.resources.getQuantityString(R.plurals.alarm_minutes, minutes, minutes)
            minutes % 60 == 0 ->
                context.resources.getQuantityString(R.plurals.alarm_hours, minutes / 60, minutes / 60)
            else ->
                context.getString(R.string.alarm_hours_minutes, minutes / 60, minutes % 60)
        }
    }
}
