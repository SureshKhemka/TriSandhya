package com.trisandhya.sunrisealarm.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.trisandhya.sunrisealarm.R

/**
 * The built-in background choices.
 *
 * These are gradient drawables shipped with the app, so there is no image
 * licensing to worry about. A user's own photo is handled separately as the
 * "custom" key with a saved content URI — see [com.trisandhya.sunrisealarm.data.SandhyaPrefs].
 */
enum class AppBackground(
    val key: String,
    @StringRes val labelRes: Int,
    @DrawableRes val drawableRes: Int?
) {
    DEFAULT("default", R.string.bg_default, null),
    DAWN("dawn", R.string.bg_dawn, R.drawable.bg_dawn),
    DUSK("dusk", R.string.bg_dusk, R.drawable.bg_dusk),
    NIGHT("night", R.string.bg_night, R.drawable.bg_night),
    TEMPLE("temple", R.string.bg_temple, R.drawable.bg_temple);

    companion object {
        fun fromKey(key: String?): AppBackground = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
