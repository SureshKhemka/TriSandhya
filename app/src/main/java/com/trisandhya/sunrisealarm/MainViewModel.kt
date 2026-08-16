package com.trisandhya.sunrisealarm

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.trisandhya.sunrisealarm.alarm.SandhyaScheduler
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.model.Junction
import com.trisandhya.sunrisealarm.solar.SolarCalculator
import com.trisandhya.sunrisealarm.util.Geocoding
import com.trisandhya.sunrisealarm.util.RelativeTime
import com.trisandhya.sunrisealarm.work.DailyRescheduleWorker
import com.trisandhya.sunrisealarm.work.LocationUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime

/** What one junction row shows. */
data class JunctionRow(
    val junction: Junction,
    val time: ZonedDateTime?,
    val enabled: Boolean,
    val offsetMinutes: Int,
    val snoozeMinutes: Int,
    val snoozeAllowed: Boolean,
    val nextFireAt: ZonedDateTime?
)

data class UiState(
    val loading: Boolean = false,
    val locationName: String? = null,
    val hasLocation: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val rows: List<JunctionRow> = emptyList(),
    val nextAlarm: Pair<Junction, ZonedDateTime>? = null,
    val statusText: String? = null,
    val needsExactAlarmPermission: Boolean = false,
    val needsBatteryExemption: Boolean = false,
    val isBackgroundRestricted: Boolean = false,
    val backgroundKey: String = SandhyaPrefs.DEFAULT_BACKGROUND_KEY,
    val backgroundUri: String? = null,
    val alarmSoundTitle: String? = null,
    val locationMode: String = SandhyaPrefs.LOCATION_OPEN
)

/** Actions a Snackbar can offer when something goes wrong. */
enum class ErrorAction { RETRY, OPEN_APP_SETTINGS }

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Error(val text: String, val action: ErrorAction? = null) : UiEvent
}

/**
 * Holds all screen state.
 *
 * Previously this lived in Activity fields, so a rotation discarded the fetched
 * times and restarted the whole permission → GPS → network sequence. Surviving
 * configuration changes is the point of putting it here.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = SandhyaPrefs(app)
    private val scheduler = SandhyaScheduler(app)

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(getApplication<Application>())
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Render immediately from the cached location so a cold start shows times
        // straight away instead of an empty screen waiting on a GPS fix.
        recompute()
    }

    // ------------------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------------------

    /**
     * @param silent an automatic refresh (e.g. on app open) — no loading spinner and
     *   failures are swallowed, since a cached fix keeps the screen usable.
     */
    @Suppress("MissingPermission")
    fun refreshLocation(hasFinePermission: Boolean, hasCoarsePermission: Boolean, silent: Boolean = false) {
        if (!hasFinePermission && !hasCoarsePermission) {
            if (!silent) {
                emit(UiEvent.Error(string(R.string.error_location_permission), ErrorAction.OPEN_APP_SETTINGS))
            }
            return
        }

        if (!silent) {
            _state.value = _state.value.copy(loading = true, statusText = string(R.string.status_getting_location))
        }

        val priority = if (hasFinePermission) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        fusedLocationClient
            .getCurrentLocation(priority, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    onLocationFixed(location.latitude, location.longitude)
                } else {
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { last ->
                            if (last != null) {
                                onLocationFixed(last.latitude, last.longitude)
                            } else {
                                failLocation(string(R.string.error_location_unavailable), silent)
                            }
                        }
                        .addOnFailureListener { e -> failLocation(locationError(e), silent) }
                }
            }
            .addOnFailureListener { e -> failLocation(locationError(e), silent) }
    }

    /** True when there's a cached fix but it's stale enough to auto-refresh on open. */
    fun shouldRefreshOnOpen(): Boolean =
        prefs.hasLocation() &&
            System.currentTimeMillis() - prefs.lastLocationFixMillis > SandhyaPrefs.LOCATION_STALE_MS

    private fun locationError(e: Exception) =
        string(R.string.error_location_failed, e.message ?: "unknown")

    private fun failLocation(message: String, silent: Boolean = false) {
        _state.value = _state.value.copy(loading = false, statusText = null)
        // If a previous fix is cached the screen is still usable, so this is a
        // retryable warning rather than a dead end.
        if (!silent) emit(UiEvent.Error(message, ErrorAction.RETRY))
    }

    private fun onLocationFixed(latitude: Double, longitude: Double) {
        prefs.latitude = latitude
        prefs.longitude = longitude
        prefs.lastLocationFixMillis = System.currentTimeMillis()

        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                Geocoding.resolveName(getApplication(), latitude, longitude)
            }
            prefs.locationName = name
            _state.value = _state.value.copy(loading = false, statusText = null)
            recompute()
            rescheduleAll()
        }
    }

    // ------------------------------------------------------------------------------
    // User settings
    // ------------------------------------------------------------------------------

    /**
     * Persists the location-update mode and (de)schedules the background worker. The
     * caller is responsible for ensuring any needed permission is granted first.
     */
    fun setLocationMode(mode: String) {
        prefs.locationMode = mode
        val app = getApplication<Application>()
        when (mode) {
            SandhyaPrefs.LOCATION_DAILY -> LocationUpdateWorker.scheduleDaily(app)
            SandhyaPrefs.LOCATION_BACKGROUND -> LocationUpdateWorker.scheduleBackground(app)
            else -> LocationUpdateWorker.cancel(app)
        }
        recompute()
    }

    fun setEnabled(junction: Junction, enabled: Boolean) {
        prefs.setEnabled(junction, enabled)
        recompute()

        if (enabled) {
            val fireAt = scheduler.schedule(junction)
            if (fireAt == null) {
                emit(UiEvent.Error(string(R.string.error_could_not_schedule)))
            } else {
                emit(UiEvent.Message(string(R.string.alarm_scheduled, formatRelative(fireAt))))
            }
            DailyRescheduleWorker.enqueue(getApplication())
        } else {
            scheduler.cancel(junction)
            emit(UiEvent.Message(string(R.string.alarm_cancelled, string(junction.labelRes))))
        }
        refreshSystemWarnings()
    }

    fun setOffsetMinutes(junction: Junction, minutes: Int) {
        val snoozeBefore = prefs.snoozeMinutes(junction)
        prefs.setOffsetMinutes(junction, minutes)
        // Lowering the offset can force the snooze below it; snoozeMinutes() clamps on
        // read, so just report it if the effective value dropped.
        val snoozeAfter = prefs.snoozeMinutes(junction)
        if (prefs.isSnoozeAllowed(junction) && snoozeAfter < snoozeBefore) {
            emit(UiEvent.Message(string(R.string.snooze_reduced, snoozeAfter)))
        }
        recompute()
        if (prefs.isEnabled(junction)) {
            scheduler.schedule(junction)?.let {
                emit(UiEvent.Message(string(R.string.alarm_scheduled, formatRelative(it))))
            }
        }
    }

    fun setSnoozeMinutes(junction: Junction, minutes: Int) {
        prefs.setSnoozeMinutes(junction, minutes)
        recompute()
    }

    /** Selects a built-in background (uri null) or the user's own photo (key "custom"). */
    fun setBackground(key: String, uri: String? = null) {
        prefs.backgroundKey = key
        prefs.backgroundUri = uri
        recompute()
    }

    /** Sets the alarm sound (uri null = system default), with a title for display. */
    fun setAlarmSound(uri: String?, title: String?) {
        prefs.alarmSoundUri = uri
        prefs.alarmSoundTitle = title
        recompute()
    }

    fun rescheduleAll() {
        scheduler.syncAll()
        if (prefs.anyEnabled()) DailyRescheduleWorker.enqueue(getApplication())
        recompute()
        refreshSystemWarnings()
    }

    // ------------------------------------------------------------------------------
    // Derived state
    // ------------------------------------------------------------------------------

    /**
     * Recomputes today's solar times and each junction's next firing.
     *
     * Note this shows *today's* times, and separately the instant each alarm will
     * actually fire. The old screen showed only tomorrow's times, which was both
     * less useful and the source of the wrong-day alarm bug.
     */
    fun recompute() {
        val latitude = prefs.latitude
        val longitude = prefs.longitude
        val today = LocalDate.now()

        if (latitude == null || longitude == null) {
            _state.value = _state.value.copy(
                hasLocation = false,
                today = today,
                rows = Junction.entries.map {
                    JunctionRow(
                        it, null, prefs.isEnabled(it),
                        prefs.offsetMinutes(it), prefs.snoozeMinutes(it),
                        prefs.isSnoozeAllowed(it), null
                    )
                },
                nextAlarm = null,
                backgroundKey = prefs.backgroundKey,
                backgroundUri = prefs.backgroundUri,
                alarmSoundTitle = prefs.alarmSoundTitle,
            locationMode = prefs.locationMode
            )
            return
        }

        val zone = ZonedDateTime.now().zone
        val times = SolarCalculator.compute(today, latitude, longitude, zone)

        val rows = Junction.entries.map { junction ->
            val time = when (junction) {
                Junction.SUNRISE -> times.sunrise
                Junction.SOLAR_NOON -> times.solarNoon
                Junction.SUNSET -> times.sunset
            }
            JunctionRow(
                junction = junction,
                time = time,
                enabled = prefs.isEnabled(junction),
                offsetMinutes = prefs.offsetMinutes(junction),
                snoozeMinutes = prefs.snoozeMinutes(junction),
                snoozeAllowed = prefs.isSnoozeAllowed(junction),
                nextFireAt = scheduler.nextOccurrence(junction)
            )
        }

        _state.value = _state.value.copy(
            hasLocation = true,
            locationName = prefs.locationName,
            today = today,
            rows = rows,
            nextAlarm = scheduler.nextEnabledAlarm(),
            backgroundKey = prefs.backgroundKey,
            backgroundUri = prefs.backgroundUri,
            alarmSoundTitle = prefs.alarmSoundTitle,
            locationMode = prefs.locationMode
        )
    }

    fun refreshSystemWarnings() {
        val anyEnabled = prefs.anyEnabled()
        _state.value = _state.value.copy(
            needsExactAlarmPermission = anyEnabled && !scheduler.canScheduleExact(),
            // Battery optimization can defer/kill an exact alarm — the likeliest reason
            // an alarm silently fails to fire.
            needsBatteryExemption = anyEnabled && !isIgnoringBatteryOptimizations(),
            isBackgroundRestricted = anyEnabled && isBackgroundRestricted()
        )
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getApplication<Application>().getSystemService(PowerManager::class.java)
        return power?.isIgnoringBatteryOptimizations(getApplication<Application>().packageName) ?: true
    }

    private fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        return am?.isBackgroundRestricted == true
    }

    private fun formatRelative(target: ZonedDateTime): String =
        RelativeTime.format(getApplication(), target)

    private fun emit(event: UiEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    private fun string(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) {
            getApplication<Application>().getString(resId)
        } else {
            getApplication<Application>().getString(resId, *args)
        }
}
