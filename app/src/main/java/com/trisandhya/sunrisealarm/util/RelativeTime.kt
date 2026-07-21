package com.trisandhya.sunrisealarm.util

import android.content.Context
import com.trisandhya.sunrisealarm.R
import java.time.Duration
import java.time.ZonedDateTime

/** Renders "in 40 min" / "in 6 h" / "tomorrow" for an upcoming alarm. */
object RelativeTime {

    fun format(context: Context, target: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now()): String {
        val minutes = Duration.between(now, target).toMinutes()
        return when {
            minutes < 60 -> context.getString(R.string.in_minutes, minutes.coerceAtLeast(0))
            minutes < 60 * 24 -> context.getString(R.string.in_hours, minutes / 60)
            else -> {
                val days = (minutes / (60 * 24)).toInt()
                context.resources.getQuantityString(R.plurals.in_days, days, days)
            }
        }
    }
}
