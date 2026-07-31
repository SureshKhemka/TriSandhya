package com.trisandhya.sunrisealarm.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.trisandhya.sunrisealarm.alarm.AlarmReceiver
import com.trisandhya.sunrisealarm.alarm.SandhyaScheduler
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Add the system-bar insets on top of the layout's existing uniform padding so
        // the centred content never hides under the status or navigation bar.
        val basePadding = binding.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom
            )
            insets
        }

        val junction = Junction.fromKey(intent.getStringExtra(EXTRA_JUNCTION))
        val timeText = intent.getStringExtra(EXTRA_TIME).orEmpty()

        if (junction != null) {
            binding.tvJunction.text = getString(junction.labelRes)
            binding.tvJunction.setTextColor(ContextCompat.getColor(this, junction.colorRes))
        }
        binding.tvTime.text = timeText

        // Route both buttons through AlarmReceiver so snooze/dismiss have a single code
        // path shared with the notification actions (scheduling, the snoozed status
        // notification, and the toast all live there).
        binding.btnSnooze.setOnClickListener {
            junction?.let { sendAction(SandhyaScheduler.ACTION_SNOOZE, it) }
            finish()
        }

        binding.btnDismiss.setOnClickListener {
            junction?.let { sendAction(SandhyaScheduler.ACTION_DISMISS, it) }
            finish()
        }
    }

    private fun sendAction(action: String, junction: Junction) {
        sendBroadcast(
            Intent(this, AlarmReceiver::class.java).apply {
                this.action = action
                putExtra(SandhyaScheduler.EXTRA_JUNCTION, junction.key)
            }
        )
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
