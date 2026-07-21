package com.trisandhya.sunrisealarm.data

import android.content.Context
import androidx.core.content.edit
import com.trisandhya.sunrisealarm.model.Junction

/**
 * Persisted user choices.
 *
 * Two reasons this exists rather than keeping state in the Activity: the toggles
 * and offsets must survive app restarts (they used to reset on every launch), and
 * the background receivers need the same values with no UI attached.
 */
class SandhyaPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("trisandhya_prefs", Context.MODE_PRIVATE)

    fun isEnabled(junction: Junction): Boolean =
        prefs.getBoolean("enabled_${junction.key}", false)

    fun setEnabled(junction: Junction, enabled: Boolean) =
        prefs.edit { putBoolean("enabled_${junction.key}", enabled) }

    /** Minutes before the junction that the alarm should fire. */
    fun offsetMinutes(junction: Junction): Int =
        prefs.getInt("offset_${junction.key}", DEFAULT_OFFSET_MINUTES)

    fun setOffsetMinutes(junction: Junction, minutes: Int) =
        prefs.edit { putInt("offset_${junction.key}", minutes) }

    fun anyEnabled(): Boolean = Junction.entries.any { isEnabled(it) }

    /**
     * Last known coordinates. Cached so the background re-arm and a cold start with
     * no GPS fix can still compute times instead of failing outright.
     */
    var latitude: Double?
        get() = if (prefs.contains(KEY_LAT)) prefs.getFloat(KEY_LAT, 0f).toDouble() else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAT) else putFloat(KEY_LAT, value.toFloat())
        }

    var longitude: Double?
        get() = if (prefs.contains(KEY_LNG)) prefs.getFloat(KEY_LNG, 0f).toDouble() else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LNG) else putFloat(KEY_LNG, value.toFloat())
        }

    var locationName: String?
        get() = prefs.getString(KEY_LOCATION_NAME, null)
        set(value) = prefs.edit { putString(KEY_LOCATION_NAME, value) }

    fun hasLocation(): Boolean = latitude != null && longitude != null

    companion object {
        const val DEFAULT_OFFSET_MINUTES = 10

        /** Offsets offered in the picker, in minutes before the junction. */
        val OFFSET_CHOICES = listOf(0, 5, 10, 15, 20, 30, 45, 60)

        private const val KEY_LAT = "latitude"
        private const val KEY_LNG = "longitude"
        private const val KEY_LOCATION_NAME = "location_name"
    }
}
