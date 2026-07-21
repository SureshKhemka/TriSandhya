package com.trisandhya.sunrisealarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.databinding.ActivityMainBinding
import com.trisandhya.sunrisealarm.databinding.ItemJunctionRowBinding
import com.trisandhya.sunrisealarm.model.Junction
import com.trisandhya.sunrisealarm.util.RelativeTime
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dateFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
    private val nextFormat = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

    private val icons = mapOf(
        Junction.SUNRISE to "🌅",
        Junction.SOLAR_NOON to "☀️",
        Junction.SUNSET to "🌇"
    )

    private lateinit var rowBindings: Map<Junction, ItemJunctionRowBinding>

    // ----------------------------------------------------------------------------------
    // Permission launchers
    // ----------------------------------------------------------------------------------

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            viewModel.refreshLocation(fine, coarse)
        } else {
            // A plain denial is recoverable by asking again; a permanent one is not,
            // so send those users somewhere they can actually undo it.
            val canAskAgain = shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            showError(
                getString(R.string.error_location_permission),
                if (canAskAgain) ErrorAction.RETRY else ErrorAction.OPEN_APP_SETTINGS
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) updateBanner()
    }

    // ----------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rowBindings = mapOf(
            Junction.SUNRISE to binding.rowSunrise,
            Junction.SOLAR_NOON to binding.rowSolarNoon,
            Junction.SUNSET to binding.rowSunset
        )

        binding.btnRefresh.setOnClickListener { requestLocation() }

        observeState()
        observeEvents()

        maybeRequestNotificationPermission()

        // Only auto-request location on a genuinely first run. On later launches the
        // cached fix already renders the screen, so an unprompted dialog would be noise.
        if (!SandhyaPrefs(this).hasLocation()) {
            requestLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed exact-alarm or notification settings while away,
        // and the date may have rolled over.
        viewModel.recompute()
        viewModel.refreshExactAlarmWarning()
        updateBanner()
    }

    // ----------------------------------------------------------------------------------
    // State
    // ----------------------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is UiEvent.Message ->
                            Snackbar.make(binding.root, event.text, Snackbar.LENGTH_SHORT).show()
                        is UiEvent.Error ->
                            showError(event.text, event.action)
                    }
                }
            }
        }
    }

    private fun render(state: UiState) {
        binding.progressBar.visibility = if (state.loading) View.VISIBLE else View.GONE

        binding.tvStatus.visibility = if (state.statusText != null) View.VISIBLE else View.GONE
        state.statusText?.let { binding.tvStatus.text = it }

        binding.tvLocation.text = when {
            state.locationName != null -> state.locationName
            state.loading -> getString(R.string.detecting)
            else -> getString(R.string.no_location_yet)
        }

        binding.tvTodayDate.text = state.today.format(dateFormat)

        state.rows.forEach { row -> renderRow(row, state.hasLocation) }

        renderNextAlarm(state)
        renderBanner(state)
    }

    private fun renderRow(row: JunctionRow, hasLocation: Boolean) {
        val rowBinding = rowBindings.getValue(row.junction)

        rowBinding.tvIcon.text = icons[row.junction]
        rowBinding.tvName.text = getString(row.junction.labelRes)
        rowBinding.tvTime.setTextColor(ContextCompat.getColor(this, row.junction.colorRes))

        // A null time means two different things. Without a location nothing has been
        // computed yet; with one, the sun genuinely does not reach that junction today.
        // Saying "does not occur today" in the first case is just wrong.
        rowBinding.tvTime.text = when {
            row.time != null -> row.time.format(timeFormat)
            hasLocation -> getString(R.string.sun_does_not_reach)
            else -> getString(R.string.time_placeholder)
        }

        // Detach before setting checked so restoring persisted state does not look
        // like a user toggle and re-fire scheduling.
        rowBinding.switchEnabled.setOnCheckedChangeListener(null)
        rowBinding.switchEnabled.isChecked = row.enabled
        rowBinding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEnabled(row.junction, isChecked)
        }
        rowBinding.switchEnabled.contentDescription = getString(row.junction.labelRes)

        rowBinding.btnOffset.text = offsetLabel(row.offsetMinutes)
        rowBinding.btnOffset.setOnClickListener { showOffsetDialog(row.junction, row.offsetMinutes) }

        rowBinding.tvNext.text = if (row.enabled && row.nextFireAt != null) {
            row.nextFireAt.format(nextFormat)
        } else {
            ""
        }
    }

    private fun renderNextAlarm(state: UiState) {
        val next = state.nextAlarm
        if (next == null) {
            binding.tvNextAlarm.visibility = View.GONE
            return
        }
        binding.tvNextAlarm.visibility = View.VISIBLE
        binding.tvNextAlarm.text = getString(
            R.string.next_alarm_summary,
            getString(next.first.labelRes),
            RelativeTime.format(this, next.second)
        )
    }

    private fun offsetLabel(minutes: Int): String =
        if (minutes == 0) {
            getString(R.string.offset_at_time)
        } else {
            getString(R.string.offset_minutes_before, minutes)
        }

    // ----------------------------------------------------------------------------------
    // Offsets
    // ----------------------------------------------------------------------------------

    private fun showOffsetDialog(junction: Junction, current: Int) {
        val choices = SandhyaPrefs.OFFSET_CHOICES
        val labels = choices.map { offsetLabel(it) }.toTypedArray()
        val checked = choices.indexOf(current).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.offset_dialog_title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setOffsetMinutes(junction, choices[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------------------------
    // Permissions
    // ----------------------------------------------------------------------------------

    private fun requestLocation() {
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        when {
            fine || coarse -> viewModel.refreshLocation(fine, coarse)

            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.location_rationale_title)
                    .setMessage(R.string.location_rationale_message)
                    .setPositiveButton(R.string.grant) { _, _ -> launchLocationRequest() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()

            else -> launchLocationRequest()
        }
    }

    private fun launchLocationRequest() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ----------------------------------------------------------------------------------
    // Banner
    // ----------------------------------------------------------------------------------

    private fun updateBanner() = renderBanner(viewModel.state.value)

    /**
     * Surfaces the two settings that silently break alarms: exact-alarm permission
     * and blocked notifications. Both leave the app looking like it works.
     */
    private fun renderBanner(state: UiState) {
        val notificationsBlocked = !NotificationManagerCompat.from(this).areNotificationsEnabled()

        when {
            state.needsExactAlarmPermission -> showBanner(R.string.banner_exact_alarm) {
                openExactAlarmSettings()
            }
            notificationsBlocked && state.rows.any { it.enabled } ->
                showBanner(R.string.banner_notifications) { openAppSettings() }
            else -> binding.cardPermissionBanner.visibility = View.GONE
        }
    }

    private fun showBanner(messageRes: Int, action: () -> Unit) {
        binding.cardPermissionBanner.visibility = View.VISIBLE
        binding.tvBannerText.setText(messageRes)
        binding.btnBannerAction.setOnClickListener { action() }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }.onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    // ----------------------------------------------------------------------------------
    // Errors
    // ----------------------------------------------------------------------------------

    /**
     * Errors go to a Snackbar with an action rather than into the status line,
     * where they used to be indistinguishable from ordinary progress text.
     */
    private fun showError(message: String, action: ErrorAction?) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        when (action) {
            ErrorAction.RETRY ->
                snackbar.setAction(R.string.retry) { requestLocation() }
            ErrorAction.OPEN_APP_SETTINGS ->
                snackbar.setAction(R.string.open_settings) { openAppSettings() }
            null -> Unit
        }
        snackbar.show()
    }
}
