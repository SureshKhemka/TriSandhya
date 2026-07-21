package com.trisandhya.sunrisealarm.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trisandhya.sunrisealarm.alarm.SandhyaScheduler
import java.util.concurrent.TimeUnit

/**
 * Safety net that re-arms the schedule once a day.
 *
 * [com.trisandhya.sunrisealarm.alarm.AlarmReceiver] already re-arms on each firing,
 * but that chain breaks if a single alarm is dropped — by force-stop, aggressive
 * OEM battery management, or the exact-alarm permission being revoked. WorkManager
 * survives those, so a broken chain self-heals within a day instead of silently
 * staying dead.
 */
class DailyRescheduleWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        SandhyaScheduler(applicationContext).syncAll()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "sandhya_daily_reschedule"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyRescheduleWorker>(1, TimeUnit.DAYS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
