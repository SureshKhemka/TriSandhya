package com.trisandhya.sunrisealarm.api

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the sunrise-sunset.org API.
 * Base URL: https://api.sunrise-sunset.org/
 *
 * Example request:
 *   GET /json?lat=36.72&lng=-4.42&date=2024-12-25&formatted=0
 */
interface SunriseSunsetApi {

    @GET("json")
    suspend fun getSunriseSunset(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("date") date: String,
        @Query("formatted") formatted: Int = 0   // 0 = ISO 8601 UTC, 1 = formatted local
    ): SunriseSunsetResponse
}
