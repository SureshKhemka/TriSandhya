package com.trisandhya.sunrisealarm.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trisandhya.sunrisealarm.R
import com.trisandhya.sunrisealarm.alarm.AlarmNotifier
import com.trisandhya.sunrisealarm.alarm.SandhyaScheduler
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.databinding.ActivityAlarmBinding
import com.trisandhya.sunrisealarm.model.Junction

/**
 * Full-screen surface shown when a sandhya alarm fires.
 *
 * Reached via the notification's full-screen intent, so it must be able to appear
 * over the lock screen and wake the display.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val junction = Junction.fromKey(intent.getStringExtra(EXTRA_JUNCTION))
        val timeText = intent.getStringExtra(EXTRA_TIME).orEmpty()

        if (junction != null) {
            binding.tvJunction.text = getString(junction.labelRes)
            binding.tvJunction.setTextColor(ContextCompat.getColor(this, junction.colorRes))
        }
        binding.tvTime.text = timeText

        binding.btnSnooze.setOnClickListener {
            junction?.let { j ->
                AlarmNotifier.cancel(this, j)
                val fireAt = SandhyaScheduler(this).snooze(j)
                if (fireAt != null) {
                    Toast.makeText(
                        this,
                        getString(R.string.snoozed_toast, SandhyaPrefs(this).snoozeMinutes()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            finish()
        }

        binding.btnDismiss.setOnClickListener {
            junction?.let {
                AlarmNotifier.cancel(this, it)
                SandhyaScheduler(this).cancelSnooze(it)
            }
            finish()
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_JUNCTION = "junction"
        const val EXTRA_TIME = "time"
    }
}
