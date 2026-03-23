package com.trisandhya.sunrisealarm

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.snackbar.Snackbar
import com.trisandhya.sunrisealarm.alarm.AlarmHelper
import com.trisandhya.sunrisealarm.api.RetrofitClient
import com.trisandhya.sunrisealarm.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    // Store parsed Calendar objects for alarm creation (in device local time)
    private var sunriseCalendar: Calendar? = null
    private var solarNoonCalendar: Calendar? = null
    private var sunsetCalendar: Calendar? = null

    // ----------------------------------------------------------------------------------
    // Permission launcher
    // ----------------------------------------------------------------------------------

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchCurrentLocation()
        } else {
            showError(getString(R.string.error_location_permission))
        }
    }

    // ----------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStaticUI()
        checkLocationPermissionAndFetch()
    }

    // ----------------------------------------------------------------------------------
    // UI Setup
    // ----------------------------------------------------------------------------------

    private fun setupStaticUI() {
        // Show tomorrow's date in the header card
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val dateFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
        binding.tvTomorrowDate.text = getString(R.string.alarms_for, dateFormat.format(tomorrow.time))

        // Refresh / retry button
        binding.btnRefresh.setOnClickListener {
            checkLocationPermissionAndFetch()
        }

        // Set alarms button
        binding.btnSetAlarms.setOnClickListener {
            setSelectedAlarms()
        }

        // Initially hide dynamic sections
        binding.cardResults.visibility = View.GONE
        binding.btnSetAlarms.visibility = View.GONE
        binding.layoutAlarmInfo.visibility = View.GONE
    }

    // ----------------------------------------------------------------------------------
    // Location Permission
    // ----------------------------------------------------------------------------------

    private fun checkLocationPermissionAndFetch() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        when {
            fineGranted || coarseGranted -> fetchCurrentLocation()

            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.location_rationale_title)
                    .setMessage(R.string.location_rationale_message)
                    .setPositiveButton(R.string.grant) { _, _ -> requestLocationPermission() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            else -> requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // ----------------------------------------------------------------------------------
    // Location Fetching
    // ----------------------------------------------------------------------------------

    private fun fetchCurrentLocation() {
        showLoading(true)
        setStatus(getString(R.string.status_getting_location))
        resetResults()

        val hasFinePerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarsePerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePerm && !hasCoarsePerm) {
            showError(getString(R.string.error_location_permission))
            return
        }

        val priority = if (hasFinePerm)
            Priority.PRIORITY_HIGH_ACCURACY
        else
            Priority.PRIORITY_BALANCED_POWER_ACCURACY

        val cancellationToken = CancellationTokenSource()

        fusedLocationClient
            .getCurrentLocation(priority, cancellationToken.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    onLocationObtained(location.latitude, location.longitude)
                } else {
                    // Fall back to last known location
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { lastLoc ->
                            if (lastLoc != null) {
                                onLocationObtained(lastLoc.latitude, lastLoc.longitude)
                            } else {
                                showError(getString(R.string.error_location_unavailable))
                            }
                        }
                        .addOnFailureListener { e ->
                            showError(getString(R.string.error_location_failed, e.message))
                        }
                }
            }
            .addOnFailureListener { e ->
                showError(getString(R.string.error_location_failed, e.message))
            }
    }

    private fun onLocationObtained(lat: Double, lng: Double) {
        setStatus(getString(R.string.status_resolving_location))

        lifecycleScope.launch {
            // Reverse geocode on IO thread
            val locationName = withContext(Dispatchers.IO) {
                resolveLocationName(lat, lng)
            }

            binding.tvLocation.text = locationName
            fetchSunriseSunsetTimes(lat, lng)
        }
    }

    private fun resolveLocationName(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                buildString {
                    addr.locality?.let { append(it) }
                    addr.adminArea?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                    addr.countryName?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                }.ifEmpty { formatCoords(lat, lng) }
            } else {
                formatCoords(lat, lng)
            }
        } catch (e: Exception) {
            formatCoords(lat, lng)
        }
    }

    private fun formatCoords(lat: Double, lng: Double) = "%.4f°, %.4f°".format(lat, lng)

    // ----------------------------------------------------------------------------------
    // API Call
    // ----------------------------------------------------------------------------------

    private fun fetchSunriseSunsetTimes(lat: Double, lng: Double) {
        setStatus(getString(R.string.status_fetching_times))

        lifecycleScope.launch {
            try {
                // Tomorrow's date in yyyy-MM-dd format (local timezone)
                val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                val apiDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val dateString = apiDateFormat.format(tomorrow.time)

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getSunriseSunset(
                        lat = lat,
                        lng = lng,
                        date = dateString,
                        formatted = 0   // Returns ISO 8601 UTC
                    )
                }

                if (response.status == "OK") {
                    displaySolarTimes(
                        sunriseUtc = response.results.sunrise,
                        solarNoonUtc = response.results.solarNoon,
                        sunsetUtc = response.results.sunset
                    )
                } else {
                    showError(getString(R.string.error_api_status, response.status))
                }

            } catch (e: Exception) {
                showError(getString(R.string.error_network, e.message ?: "Unknown error"))
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // Display Results
    // ----------------------------------------------------------------------------------

    private fun displaySolarTimes(sunriseUtc: String, solarNoonUtc: String, sunsetUtc: String) {
        try {
            // Parse ISO 8601 UTC timestamps
            val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val displayFormat = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }

            val sunriseDate = isoParser.parse(sunriseUtc)!!
            val solarNoonDate = isoParser.parse(solarNoonUtc)!!
            val sunsetDate = isoParser.parse(sunsetUtc)!!

            // Store as Calendar (local timezone) for alarm creation
            sunriseCalendar = Calendar.getInstance().apply { time = sunriseDate }
            solarNoonCalendar = Calendar.getInstance().apply { time = solarNoonDate }
            sunsetCalendar = Calendar.getInstance().apply { time = sunsetDate }

            // Update UI
            binding.tvSunriseTime.text = displayFormat.format(sunriseDate)
            binding.tvSolarNoonTime.text = displayFormat.format(solarNoonDate)
            binding.tvSunsetTime.text = displayFormat.format(sunsetDate)

            // Show results section
            showLoading(false)
            binding.cardResults.visibility = View.VISIBLE
            binding.btnSetAlarms.visibility = View.VISIBLE
            binding.layoutAlarmInfo.visibility = View.VISIBLE
            setStatus(getString(R.string.status_times_ready))

        } catch (e: Exception) {
            showError(getString(R.string.error_parse, e.message))
        }
    }

    // ----------------------------------------------------------------------------------
    // Alarm Creation
    // ----------------------------------------------------------------------------------

    private fun setSelectedAlarms() {
        val alarmItems = mutableListOf<Triple<String, Int, Int>>()

        if (binding.switchSunrise.isChecked) {
            sunriseCalendar?.let { cal ->
                alarmItems.add(Triple(
                    getString(R.string.alarm_label_sunrise),
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
                ))
            }
        }

        if (binding.switchSolarNoon.isChecked) {
            solarNoonCalendar?.let { cal ->
                alarmItems.add(Triple(
                    getString(R.string.alarm_label_solar_noon),
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
                ))
            }
        }

        if (binding.switchSunset.isChecked) {
            sunsetCalendar?.let { cal ->
                alarmItems.add(Triple(
                    getString(R.string.alarm_label_sunset),
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
                ))
            }
        }

        if (alarmItems.isEmpty()) {
            Toast.makeText(this, R.string.error_no_alarm_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val results = AlarmHelper.setAlarms(this, alarmItems)
        val successCount = results.count { it.success }
        val failCount = results.size - successCount

        val message = when {
            failCount == 0 -> resources.getQuantityString(
                R.plurals.alarm_set_success, successCount, successCount
            )
            successCount == 0 -> getString(R.string.alarm_set_all_failed)
            else -> getString(R.string.alarm_set_partial, successCount, failCount)
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    // ----------------------------------------------------------------------------------
    // UI Helpers
    // ----------------------------------------------------------------------------------

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setStatus(message: String) {
        binding.tvStatus.text = message
    }

    private fun showError(message: String) {
        showLoading(false)
        setStatus(message)
    }

    private fun resetResults() {
        binding.cardResults.visibility = View.GONE
        binding.btnSetAlarms.visibility = View.GONE
        binding.layoutAlarmInfo.visibility = View.GONE
        sunriseCalendar = null
        solarNoonCalendar = null
        sunsetCalendar = null
    }
}
