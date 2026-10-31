package com.pure.crosshair

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pure.crosshair.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var library: Library

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
        library = Library(this)

        binding.btnGrant.setOnClickListener { requestOverlayPermission() }
        binding.btnImport.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        binding.btnPrimary.setOnClickListener { onPrimaryClicked() }
        binding.btnPriority.setOnClickListener { onPriorityClicked() }
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

        val images = library.list().size
        binding.libraryCount.text = when (images) {
            0 -> getString(R.string.library_none)
            1 -> getString(R.string.library_one)
            else -> getString(R.string.library_n, images)
        }

        binding.statusLine.setText(
            when {
                images == 0 -> R.string.need_an_image
                running -> R.string.running
                else -> R.string.not_running
            }
        )
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
