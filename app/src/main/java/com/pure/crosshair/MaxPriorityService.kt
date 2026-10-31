package com.pure.crosshair

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Optional renderer that draws the crosshair from an accessibility service.
 *
 * TYPE_ACCESSIBILITY_OVERLAY sits above TYPE_APPLICATION_OVERLAY in the window stack, so the
 * crosshair keeps its place above other floating apps and stays put in more full screen
 * situations. It reads no screen content and tracks nothing; it exists purely because that
 * window type is the only one ranked higher than a normal app overlay.
 *
 * Everything works without this. It is off until the user turns it on.
 */
class MaxPriorityService : AccessibilityService() {

    private lateinit var prefs: Prefs

    var layer: CrosshairLayer? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        Bridge.maxPriority = this
        // Hand the crosshair over from OverlayService if it is currently drawing one.
        Bridge.refresh()
    }

    /** Same rotation problem as OverlayService: reload the offsets for the new orientation. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val handler = Handler(Looper.getMainLooper())
        handler.post { applyConfig() }
        handler.postDelayed({ applyConfig() }, 350L)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        layer?.hide()
        layer = null
        Bridge.maxPriority = null
        // Give the crosshair back to the normal overlay service.
        Bridge.overlay?.applyConfig()
    }

    fun applyConfig() {
        if (!::prefs.isInitialized) prefs = Prefs(this)

        val shouldDraw = prefs.maxPriority && prefs.visible && OverlayService.isRunning
        if (!shouldDraw) {
            layer?.hide()
            layer = null
            return
        }

        val current = layer ?: CrosshairLayer(this, CrosshairLayer.ACCESSIBILITY_OVERLAY).also {
            it.onMoved = { x, y ->
                prefs.offsetX = x
                prefs.offsetY = y
                Bridge.overlay?.notifyMoved()
            }
            layer = it
        }
        current.show(prefs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
