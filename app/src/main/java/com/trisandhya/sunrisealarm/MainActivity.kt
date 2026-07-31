package com.trisandhya.sunrisealarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.trisandhya.sunrisealarm.data.SandhyaPrefs
import com.trisandhya.sunrisealarm.databinding.ActivityMainBinding
import com.trisandhya.sunrisealarm.databinding.ItemJunctionRowBinding
import com.trisandhya.sunrisealarm.model.AppBackground
import com.trisandhya.sunrisealarm.model.Junction
import com.trisandhya.sunrisealarm.util.RelativeTime
import kotlinx.coroutines.launch
import java.io.File
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

    // The system photo picker needs no permission. The chosen image is copied into
    // app storage so the background survives reboots (picker URIs are not durable).
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val path = copyPickedImage(uri)
        if (path != null) {
            viewModel.setBackground(SandhyaPrefs.CUSTOM_BACKGROUND_KEY, path)
        } else {
            Snackbar.make(binding.root, R.string.background_photo_error, Snackbar.LENGTH_SHORT).show()
        }
    }

    // ----------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apps targeting Android 15+ (API 35) draw edge-to-edge by default, so the
        // system-bar colors in the theme are ignored and content would otherwise sit
        // under the status and navigation bars. Opt in explicitly (uniform on all API
        // levels) and pad the scroll container by the bar insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pad the scrolling content (not the root) so a chosen background image still
        // fills edge-to-edge behind the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        rowBindings = mapOf(
            Junction.SUNRISE to binding.rowSunrise,
            Junction.SOLAR_NOON to binding.rowSolarNoon,
            Junction.SUNSET to binding.rowSunset
        )

        binding.btnRefresh.setOnClickListener { requestLocation() }
        binding.btnBackground.setOnClickListener { showBackgroundDialog() }

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
        applyBackground(state)
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

        rowBinding.btnSnooze.text = getString(R.string.snooze_button, row.snoozeMinutes)
        rowBinding.btnSnooze.setOnClickListener { showSnoozeDialog(row.junction, row.snoozeMinutes) }

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

    /**
     * Slider from exact time (0) to one hour before (60), with preset chips as
     * shortcuts. The slider is the single source of truth; chips just move it.
     */
    private fun showOffsetDialog(junction: Junction, current: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_offset, null)
        val slider = view.findViewById<Slider>(R.id.slider_offset)
        val valueLabel = view.findViewById<TextView>(R.id.tv_offset_value)
        val presets = view.findViewById<ChipGroup>(R.id.chip_presets)

        slider.value = current.coerceIn(0, SandhyaPrefs.MAX_OFFSET_MINUTES).toFloat()
        valueLabel.text = offsetLabel(slider.value.toInt())
        slider.addOnChangeListener { _, value, _ ->
            valueLabel.text = offsetLabel(value.toInt())
        }

        SandhyaPrefs.OFFSET_CHOICES.forEach { preset ->
            val chip = Chip(presets.context).apply {
                text = if (preset == 0) {
                    getString(R.string.offset_chip_exact)
                } else {
                    getString(R.string.offset_chip_minutes, preset)
                }
                isCheckable = false
                setOnClickListener { slider.value = preset.toFloat() }
            }
            presets.addView(chip)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.offset_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.setOffsetMinutes(junction, slider.value.toInt())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSnoozeDialog(junction: Junction, current: Int) {
        val choices = SandhyaPrefs.SNOOZE_CHOICES
        val labels = choices.map { getString(R.string.snooze_minutes, it) }.toTypedArray()
        val checked = choices.indexOf(current).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.snooze_dialog_title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setSnoozeMinutes(junction, choices[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------------------------
    // Background
    // ----------------------------------------------------------------------------------

    /** Draws the chosen background behind the content, or nothing for the default. */
    private fun applyBackground(state: UiState) {
        binding.btnBackground.text = backgroundLabel(state)

        val image = binding.bgImage
        val scrim = binding.bgScrim

        val bitmap = if (state.backgroundKey == SandhyaPrefs.CUSTOM_BACKGROUND_KEY) {
            state.backgroundUri?.let { decodeSampled(it, image.width, image.height) }
        } else {
            null
        }

        when {
            bitmap != null -> {
                image.setImageBitmap(bitmap)
                showBackground(image, scrim, visible = true)
            }
            state.backgroundKey != SandhyaPrefs.CUSTOM_BACKGROUND_KEY &&
                AppBackground.fromKey(state.backgroundKey).drawableRes != null -> {
                image.setImageResource(AppBackground.fromKey(state.backgroundKey).drawableRes!!)
                showBackground(image, scrim, visible = true)
            }
            else -> {
                image.setImageDrawable(null)
                showBackground(image, scrim, visible = false)
            }
        }
    }

    private fun showBackground(image: ImageView, scrim: View, visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        image.visibility = v
        scrim.visibility = v
    }

    private fun backgroundLabel(state: UiState): String =
        if (state.backgroundKey == SandhyaPrefs.CUSTOM_BACKGROUND_KEY) {
            getString(R.string.background_choose_photo)
        } else {
            getString(AppBackground.fromKey(state.backgroundKey).labelRes)
        }

    private fun showBackgroundDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_background, null)
        val tiles = view.findViewById<LinearLayout>(R.id.bg_tiles)
        val state = viewModel.state.value

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.background_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        AppBackground.entries.forEach { bg ->
            val selected = state.backgroundKey == bg.key
            addTile(
                tiles = tiles,
                previewRes = bg.drawableRes ?: R.drawable.bg_default_swatch,
                label = getString(bg.labelRes),
                selected = selected,
                hintPhoto = false
            ) {
                viewModel.setBackground(bg.key)
                dialog.dismiss()
            }
        }

        val customSelected = state.backgroundKey == SandhyaPrefs.CUSTOM_BACKGROUND_KEY
        addTile(
            tiles = tiles,
            previewRes = if (customSelected) null else R.drawable.bg_default_swatch,
            previewBitmap = if (customSelected) {
                state.backgroundUri?.let { decodeSampled(it, 240, 360) }
            } else null,
            label = getString(R.string.background_choose_photo),
            selected = customSelected,
            hintPhoto = !customSelected
        ) {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun addTile(
        tiles: LinearLayout,
        previewRes: Int? = null,
        previewBitmap: Bitmap? = null,
        label: String,
        selected: Boolean,
        hintPhoto: Boolean,
        onClick: () -> Unit
    ) {
        val tile = layoutInflater.inflate(R.layout.item_bg_tile, tiles, false)
        val image = tile.findViewById<ImageView>(R.id.tile_image)
        val check = tile.findViewById<ImageView>(R.id.tile_check)

        when {
            previewBitmap != null -> image.setImageBitmap(previewBitmap)
            previewRes != null -> image.setImageResource(previewRes)
        }
        tile.findViewById<TextView>(R.id.tile_label).text = label

        // The check slot doubles as a "pick a photo" hint on the unselected photo tile.
        when {
            selected -> {
                check.setImageResource(R.drawable.ic_check)
                check.visibility = View.VISIBLE
            }
            hintPhoto -> {
                check.setImageResource(R.drawable.ic_photo)
                check.visibility = View.VISIBLE
            }
            else -> check.visibility = View.GONE
        }

        tile.setOnClickListener { onClick() }
        tiles.addView(tile)
    }

    /** Copies a picked image into app storage; returns its path, or null on failure. */
    private fun copyPickedImage(uri: Uri): String? = try {
        val file = File(filesDir, "background_custom.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        if (file.length() > 0) file.absolutePath else null
    } catch (e: Exception) {
        null
    }

    /**
     * Decodes a saved image downsampled to roughly the target size, so a large photo
     * cannot blow up memory when used as a full-screen background.
     */
    private fun decodeSampled(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val targetW = if (reqWidth > 0) reqWidth else resources.displayMetrics.widthPixels
        val targetH = if (reqHeight > 0) reqHeight else resources.displayMetrics.heightPixels

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > targetW * 2 || bounds.outHeight / sample > targetH * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
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
