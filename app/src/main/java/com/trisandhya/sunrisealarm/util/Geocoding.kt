package com.trisandhya.sunrisealarm.util

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/** Reverse-geocodes coordinates to a display name, shared by the UI and the background worker. */
object Geocoding {

    /** A human-readable place name, or the coordinates formatted as a fallback. */
    fun resolveName(context: Context, latitude: Double, longitude: Double): String = try {
        @Suppress("DEPRECATION")
        val addresses = Geocoder(context, Locale.getDefault())
            .getFromLocation(latitude, longitude, 1)

        val address = addresses?.firstOrNull()
        if (address == null) {
            formatCoords(latitude, longitude)
        } else {
            listOfNotNull(address.locality, address.adminArea, address.countryName)
                .distinct()
                .joinToString(", ")
                .ifEmpty { formatCoords(latitude, longitude) }
        }
    } catch (e: Exception) {
        // Geocoding is a nicety; coordinates are a perfectly usable fallback.
        formatCoords(latitude, longitude)
    }

    fun formatCoords(latitude: Double, longitude: Double): String =
        String.format(Locale.getDefault(), "%.4f°, %.4f°", latitude, longitude)
}
