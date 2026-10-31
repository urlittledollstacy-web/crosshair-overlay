package com.pure.crosshair

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pure.crosshair.databinding.ActivityMainBinding
import com.pure.crosshair.databinding.DialogCrosshairPickerBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denied only costs us the control notification; the overlay still runs.
            launchOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnGrant.setOnClickListener { requestOverlayPermission() }
        binding.btnChoose.setOnClickListener { showCrosshairPicker() }
        binding.btnPrimary.setOnClickListener { onPrimaryClicked() }
        binding.btnPriority.setOnClickListener { onPriorityClicked() }
        binding.btnOpenPanel.setOnClickListener {
            val overlay = Bridge.overlay
            if (overlay == null) {
                Toast.makeText(this, R.string.panel_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                overlay.openPanel()
            }
        }
        binding.btnHideButton.setOnClickListener {
            val overlay = Bridge.overlay
            if (overlay != null) {
                // Goes through the service so the button window is added or removed at once.
                overlay.setButtonHidden(!prefs.buttonHidden)
            } else {
                // Overlay is not running, so just record it for the next start.
                prefs.buttonHidden = !prefs.buttonHidden
            }
            refresh()
        }
        binding.btnSnap.setOnClickListener {
            prefs.snapButtonToEdge = !prefs.snapButtonToEdge
            refresh()
        }
        binding.btnBoot.setOnClickListener {
            prefs.startOnBoot = !prefs.startOnBoot
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // Max priority mode is meaningless without the service actually enabled.
        if (prefs.maxPriority && !isMaxPriorityEnabled()) prefs.maxPriority = false
        Bridge.refresh()
        refresh()
    }

    private fun refresh() {
        val granted = Settings.canDrawOverlays(this)
        val running = OverlayService.isRunning

        binding.permissionState.text = getString(if (granted) R.string.perm_granted else R.string.perm_missing)
        binding.permissionState.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.accent else R.color.danger)
        )
        binding.btnGrant.visibility = if (granted) View.GONE else View.VISIBLE
        binding.permissionBody.visibility = if (granted) View.GONE else View.VISIBLE

        binding.btnPrimary.setText(if (running) R.string.stop_overlay else R.string.start_overlay)
        binding.btnPrimary.setBackgroundResource(
            if (running) R.drawable.bg_cta_ghost else R.drawable.bg_cta
        )
        binding.btnPrimary.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.text else R.color.on_accent)
        )
        binding.btnPrimary.isEnabled = granted
        binding.btnPrimary.alpha = if (granted) 1f else 0.4f

        binding.crosshairName.text = CrosshairCatalog.name(prefs.selected)
        binding.crosshairPreview.setImageDrawable(
            CrosshairCatalog.byId(prefs.selected)?.drawable(prefs.crosshairColor)
                ?: CrosshairCatalog.default.drawable(prefs.crosshairColor)
        )

        binding.statusLine.setText(if (running) R.string.running else R.string.not_running)
        binding.statusDot.visibility = if (running) View.VISIBLE else View.GONE

        val priorityOn = prefs.maxPriority && isMaxPriorityEnabled()
        binding.btnPriority.isSelected = priorityOn
        binding.btnPriority.setText(
            when {
                priorityOn -> R.string.on
                isMaxPriorityEnabled() -> R.string.off
                else -> R.string.setup
            }
        )

        binding.btnHideButton.isSelected = prefs.buttonHidden
        binding.btnHideButton.setText(if (prefs.buttonHidden) R.string.on else R.string.off)
        // The button is what opens the panel, so with it hidden this is the way back in.
        binding.btnSnap.alpha = if (prefs.buttonHidden) 0.5f else 1f
        binding.btnSnap.isEnabled = !prefs.buttonHidden

        binding.btnSnap.isSelected = prefs.snapButtonToEdge
        binding.btnSnap.setText(if (prefs.snapButtonToEdge) R.string.on else R.string.off)

        binding.btnBoot.isSelected = prefs.startOnBoot
        binding.btnBoot.setText(if (prefs.startOnBoot) R.string.on else R.string.off)
    }

    private fun onPrimaryClicked() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (OverlayService.isRunning) {
            OverlayService.stop(this)
            binding.root.postDelayed({ refresh() }, 200)
            return
        }

        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchOverlay()
        }
    }

    private fun launchOverlay() {
        OverlayService.start(this)
        binding.root.postDelayed({ refresh() }, 250)
    }

    private fun onPriorityClicked() {
        if (!isMaxPriorityEnabled()) {
            Toast.makeText(this, R.string.a11y_toast, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            prefs.maxPriority = true
            return
        }
        prefs.maxPriority = !prefs.maxPriority
        Bridge.refresh()
        refresh()
    }

    /**
     * The crosshair picker.
     *
     * A dialog rather than a screen: choosing a reticle is a small decision made in place, and
     * a full screen for it would cost a navigation step each time.
     */
    private fun showCrosshairPicker() {
        val dialogBinding = DialogCrosshairPickerBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Crosshair_Dialog)
            .setTitle(R.string.picker_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.picker_done, null)
            .create()

        val gap = resources.getDimensionPixelSize(R.dimen.picker_gap)
        val columns = PICKER_COLUMNS
        val grid = dialogBinding.pickerGrid
        grid.columnCount = columns

        val screenWidth = resources.displayMetrics.widthPixels
        val available = screenWidth - resources.getDimensionPixelSize(R.dimen.picker_inset)
        val cell = ((available - gap * (columns - 1)) / columns).coerceAtLeast(dp(44))

        val tiles = mutableListOf<View>()
        for (model in CrosshairCatalog.all) {
            val tile = ImageView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                    marginEnd = gap
                    bottomMargin = gap
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_thumb)
                isSelected = model.id == prefs.selected
                contentDescription = model.name
                setOnClickListener {
                    prefs.selected = model.id
                    tiles.forEachIndexed { i, v ->
                        v.isSelected = CrosshairCatalog.all[i].id == model.id
                    }
                    tintTiles(tiles)
                    Bridge.refresh()
                    refresh()
                }
            }
            tiles += tile
            grid.addView(tile)
        }
        tintTiles(tiles)

        buildColorSwatches(dialogBinding, tiles)

        dialog.show()
    }

    /** Retints the existing tiles in place instead of rebuilding 35 drawables. */
    private fun tintTiles(tiles: List<View>) {
        tiles.forEach { v ->
            ((v as? ImageView)?.drawable as? CrosshairDrawable)?.setColor(prefs.crosshairColor)
        }
    }

    /** A ring per colour, filled with that colour. Tapping one retints the grid immediately. */
    private fun buildColorSwatches(
        b: DialogCrosshairPickerBinding,
        tiles: List<View>
    ) {
        val slot = dp(36)
        val fill = dp(26)
        val gap = dp(8)
        b.colorRow.removeAllViews()

        for ((name, color) in CrosshairColors.all) {
            val swatch = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(slot, slot).also { it.marginEnd = gap }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_swatch_slot)
                isSelected = color == prefs.crosshairColor
                contentDescription = name
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.crosshairColor = color
                    for (i in 0 until b.colorRow.childCount) {
                        b.colorRow.getChildAt(i).isSelected = i == CrosshairColors.all.indexOfFirst {
                            it.second == color
                        }
                    }
                    tintTiles(tiles)
                    Bridge.refresh()
                    refresh()
                }
            }
            swatch.addView(
                View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(fill, fill, Gravity.CENTER)
                    background = ContextCompat.getDrawable(
                        this@MainActivity, R.drawable.bg_swatch_fill
                    )?.mutate()?.apply {
                        setTint(color)
                    }
                }
            )
            b.colorRow.addView(swatch)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    companion object {
        /** Five columns keeps a tile around 56dp on a normal phone, which is a comfortable tap. */
        private const val PICKER_COLUMNS = 5
    }

    /** True when the user has switched our accessibility service on in system settings. */
    private fun isMaxPriorityEnabled(): Boolean {
        val expected = ComponentName(this, MaxPriorityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }
}
