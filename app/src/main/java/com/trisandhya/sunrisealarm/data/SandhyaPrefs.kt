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

    /** Minutes before the junction that the alarm should fire (0..[MAX_OFFSET_MINUTES]). */
    fun offsetMinutes(junction: Junction): Int =
        prefs.getInt("offset_${junction.key}", DEFAULT_OFFSET_MINUTES)

    fun setOffsetMinutes(junction: Junction, minutes: Int) =
        prefs.edit { putInt("offset_${junction.key}", minutes.coerceIn(0, MAX_OFFSET_MINUTES)) }

    /** Minutes a fired alarm is pushed back when the user snoozes it, per junction. */
    fun snoozeMinutes(junction: Junction): Int =
        prefs.getInt("snooze_${junction.key}", DEFAULT_SNOOZE_MINUTES)

    fun setSnoozeMinutes(junction: Junction, minutes: Int) =
        prefs.edit { putInt("snooze_${junction.key}", minutes) }

    fun anyEnabled(): Boolean = Junction.entries.any { isEnabled(it) }

    /**
     * Chosen app background — a built-in key (see AppBackground) or "custom", in which
     * case [backgroundUri] holds the user's picked image.
     */
    var backgroundKey: String
        get() = prefs.getString(KEY_BG_KEY, DEFAULT_BACKGROUND_KEY) ?: DEFAULT_BACKGROUND_KEY
        set(value) = prefs.edit { putString(KEY_BG_KEY, value) }

    var backgroundUri: String?
        get() = prefs.getString(KEY_BG_URI, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_BG_URI) else putString(KEY_BG_URI, value)
        }

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

        /** The slider tops out at one hour before, per the feature request. */
        const val MAX_OFFSET_MINUTES = 60

        /** Quick-pick presets in the offset picker; the slider still allows any value. */
        val OFFSET_CHOICES = listOf(0, 5, 10, 15, 30, 45, 60)

        const val DEFAULT_SNOOZE_MINUTES = 10

        /** Snooze durations offered in the picker, in minutes. */
        val SNOOZE_CHOICES = listOf(5, 10, 15, 20, 30)

        const val DEFAULT_BACKGROUND_KEY = "default"
        const val CUSTOM_BACKGROUND_KEY = "custom"

        private const val KEY_LAT = "latitude"
        private const val KEY_LNG = "longitude"
        private const val KEY_LOCATION_NAME = "location_name"
        private const val KEY_BG_KEY = "background_key"
        private const val KEY_BG_URI = "background_uri"
    }
}
