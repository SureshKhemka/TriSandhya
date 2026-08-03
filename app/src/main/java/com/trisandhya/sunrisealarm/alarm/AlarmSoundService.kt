package com.trisandhya.sunrisealarm.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.model.Junction
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Plays the alarm sound — the user's chosen tone or their own audio — on a loop until
 * the user dismisses or snoozes, or a safety timeout elapses.
 *
 * A foreground service rather than a bare MediaPlayer because the sound must keep
 * playing with the screen off, past the brief window a broadcast receiver gets. It is
 * started from [AlarmReceiver] at alarm time (allowed from the background because the
 * app holds the exact-alarm permission) and stopped from there on snooze/dismiss.
 *
 * The alarm notification is this service's foreground notification, so its sound
 * channel stays silent and there is no double audio.
 */
class AlarmSoundService : Service() {

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var triedFallback = false

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val junction = Junction.fromKey(intent?.getStringExtra(SandhyaScheduler.EXTRA_JUNCTION))
        if (junction == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val eventMillis = intent?.getLongExtra(SandhyaScheduler.EXTRA_EVENT_TIME, 0L) ?: 0L
        val eventTime = if (eventMillis > 0L) {
            Instant.ofEpochMilli(eventMillis).atZone(ZoneId.systemDefault())
        } else {
            ZonedDateTime.now()
        }

        // Must go foreground promptly; the notification is the alarm notification itself.
        val notification = AlarmNotifier.buildAlarmNotification(this, junction, eventTime)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, AlarmNotifier.alarmNotificationId(junction), notification, type)

        startRinging()

        // Never ring forever if the user is away; keep it alarm-length.
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun startRinging() {
        acquireWakeLock()
        requestAudioFocus()
        startVibration()
        triedFallback = false
        play(resolveSoundUri())
    }

    private fun play(uri: Uri) {
        releasePlayer()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            setOnPreparedListener { start() }
            setOnErrorListener { _, _, _ ->
                // A chosen custom sound may be unreadable/unsupported — fall back once.
                if (!triedFallback) {
                    triedFallback = true
                    play(defaultAlarmUri())
                }
                true
            }
            try {
                setDataSource(this@AlarmSoundService, uri)
                prepareAsync()
            } catch (e: Exception) {
                if (!triedFallback) {
                    triedFallback = true
                    play(defaultAlarmUri())
                }
            }
        }
    }

    private fun resolveSoundUri(): Uri {
        val saved = SandhyaPrefs(this).alarmSoundUri
        return if (saved.isNullOrBlank()) defaultAlarmUri() else Uri.parse(saved)
    }

    private fun defaultAlarmUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Uri.EMPTY

    private fun requestAudioFocus() {
        val audio = getSystemService<AudioManager>() ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        focusRequest = request
        audio.requestAudioFocus(request)
    }

    private fun startVibration() {
        val vibrator = vibrator() ?: return
        // A steady pulse the user can feel with the phone in a pocket, looping.
        val pattern = longArrayOf(0, 700, 700)
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)) }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        }

    private fun acquireWakeLock() {
        val power = getSystemService<PowerManager>() ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trisandhya:alarm").apply {
            setReferenceCounted(false)
            acquire(RING_TIMEOUT_MS)
        }
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        releasePlayer()
        runCatching { vibrator()?.cancel() }
        focusRequest?.let { getSystemService<AudioManager>()?.abandonAudioFocusRequest(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.trisandhya.sunrisealarm.ACTION_PLAY_SOUND"
        const val ACTION_STOP = "com.trisandhya.sunrisealarm.ACTION_STOP_SOUND"

        /** How long the alarm rings if the user never acts. */
        private const val RING_TIMEOUT_MS = 60_000L

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AlarmSoundService::class.java))
            }
        }
    }
}
