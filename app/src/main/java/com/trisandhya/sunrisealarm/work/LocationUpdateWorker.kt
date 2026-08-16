package com.trisandhya.sunrisealarm.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import com.trisandhya.sunrisealarm.R
import com.trisandhya.sunrisealarm.alarm.SandhyaScheduler
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.util.Geocoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps location current while travelling, without the user reopening the app.
 *
 * Two modes, chosen by the user (see [SandhyaPrefs.locationMode]):
 *  - Daily: runs once a day and goes foreground (location service type) to read the
 *    location — no "all the time" permission needed.
 *  - Background: runs every few hours as a plain background job, which needs
 *    ACCESS_BACKGROUND_LOCATION.
 *
 * Either way it saves the new location and re-arms the alarms for it.
 */
class LocationUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val prefs = SandhyaPrefs(applicationContext)

    override suspend fun doWork(): Result {
        if (!hasForegroundLocation()) return Result.success()

        val mode = prefs.locationMode
        if (mode == SandhyaPrefs.LOCATION_DAILY) {
            // Go foreground so location can be read without background-location access.
            runCatching { setForeground(foregroundInfo()) }
        } else if (mode == SandhyaPrefs.LOCATION_BACKGROUND && !hasBackgroundLocation()) {
            return Result.success() // permission was revoked; nothing we can do
        }

        val location = fetchLocation() ?: return Result.success()

        prefs.latitude = location.latitude
        prefs.longitude = location.longitude
        prefs.lastLocationFixMillis = System.currentTimeMillis()
        prefs.locationName = withContext(Dispatchers.IO) {
            Geocoding.resolveName(applicationContext, location.latitude, location.longitude)
        }

        // Re-arm enabled alarms against the new location's solar times.
        SandhyaScheduler(applicationContext).syncAll()
        return Result.success()
    }

    @SuppressLint("MissingPermission") // gated by hasForegroundLocation() above
    private suspend fun fetchLocation(): Location? = withContext(Dispatchers.IO) {
        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        runCatching {
            Tasks.await(
                client.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    CancellationTokenSource().token
                ),
                60, TimeUnit.SECONDS
            )
        }.getOrNull()
            ?: runCatching { Tasks.await(client.lastLocation, 20, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService<NotificationManager>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager?.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.channel_location),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setSound(null, null) }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sandhya_notification)
            .setContentTitle(applicationContext.getString(R.string.location_updating))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun hasForegroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val WORK_NAME = "sandhya_location_update"
        private const val CHANNEL_ID = "sandhya_location"
        private const val NOTIF_ID = 8001

        fun scheduleDaily(context: Context) =
            enqueue(context, 1, TimeUnit.DAYS)

        /** WorkManager's floor is well above this, so a few hours is honoured. */
        fun scheduleBackground(context: Context) =
            enqueue(context, 3, TimeUnit.HOURS)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun enqueue(context: Context, interval: Long, unit: TimeUnit) {
            val request = PeriodicWorkRequestBuilder<LocationUpdateWorker>(interval, unit).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
