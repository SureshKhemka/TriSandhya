package com.trisandhya.sunrisealarm.api

import com.google.gson.annotations.SerializedName

/**
 * Top-level response from api.sunrise-sunset.org
 */
data class SunriseSunsetResponse(
    val results: SunriseSunsetResults,
    val status: String        // "OK" or "INVALID_REQUEST" etc.
)

/**
 * The results object containing all solar times in ISO 8601 UTC format
 * when requested with formatted=0.
 *
 * Example: "2024-12-25T06:14:21+00:00"
 */
data class SunriseSunsetResults(
    val sunrise: String,
    val sunset: String,
    @SerializedName("solar_noon") val solarNoon: String,
    @SerializedName("day_length") val dayLength: Long,
    @SerializedName("civil_twilight_begin") val civilTwilightBegin: String,
    @SerializedName("civil_twilight_end") val civilTwilightEnd: String,
    @SerializedName("nautical_twilight_begin") val nauticalTwilightBegin: String,
    @SerializedName("nautical_twilight_end") val nauticalTwilightEnd: String,
    @SerializedName("astronomical_twilight_begin") val astronomicalTwilightBegin: String,
    @SerializedName("astronomical_twilight_end") val astronomicalTwilightEnd: String
)
