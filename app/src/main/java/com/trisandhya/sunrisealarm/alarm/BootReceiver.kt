package com.trisandhya.sunrisealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trisandhya.sunrisealarm.work.DailyRescheduleWorker

/**
 * Restores the schedule after events that silently clear pending alarms.
 *
 * Exact alarms do not survive a reboot or an app update, and a timezone change
 * invalidates every previously computed instant.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                SandhyaScheduler(context).syncAll()
                DailyRescheduleWorker.enqueue(context)
            }
        }
    }
}
