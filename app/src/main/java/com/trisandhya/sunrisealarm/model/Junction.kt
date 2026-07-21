package com.trisandhya.sunrisealarm.model

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.trisandhya.sunrisealarm.R

/**
 * The three daily junctions ("sandhya") the app tracks.
 *
 * [requestCode] must stay stable across releases: it identifies each junction's
 * PendingIntent, so changing it would orphan already-scheduled alarms.
 */
enum class Junction(
    val requestCode: Int,
    val key: String,
    @StringRes val labelRes: Int,
    @ColorRes val colorRes: Int
) {
    SUNRISE(1001, "sunrise", R.string.sunrise, R.color.sunrise_color),
    SOLAR_NOON(1002, "solar_noon", R.string.solar_noon, R.color.solar_noon_color),
    SUNSET(1003, "sunset", R.string.sunset, R.color.sunset_color);

    companion object {
        fun fromKey(key: String?): Junction? = entries.firstOrNull { it.key == key }
    }
}
