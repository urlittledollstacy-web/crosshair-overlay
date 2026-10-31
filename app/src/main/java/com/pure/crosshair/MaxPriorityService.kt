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
    private val handler = Handler(Looper.getMainLooper())

    var layer: CrosshairLayer? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        Bridge.maxPriority = this
        // Hand the crosshair over from OverlayService if it is currently drawing one.
        Bridge.refresh()
    }

    /**
     * Same rotation problem as OverlayService: reload the offsets for the new orientation.
     * One handler for the life of the service rather than a new one per rotation, and the
     * callbacks are cleared on teardown.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.removeCallbacksAndMessages(null)
        // applyConfig() initialises prefs itself, so refresh the metrics after it has run
        // rather than touching a lateinit field that may not be set yet.
        handler.post { refreshAndApply() }
        handler.postDelayed({ refreshAndApply() }, ROTATION_SETTLE_MS)
    }

    private fun refreshAndApply() {
        if (!::prefs.isInitialized) prefs = Prefs(this)
        prefs.refreshMetrics()
        applyConfig()
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
        handler.removeCallbacksAndMessages(null)
        layer?.hide()
        layer = null
        Bridge.maxPriority = null
        // Give the crosshair back to the normal overlay service.
        Bridge.overlay?.applyConfig()
    }

    fun applyConfig() {
        if (!::prefs.isInitialized) prefs = Prefs(this)

        // The same rule the normal renderer uses, so switching apps hides and shows the
        // crosshair the same way whichever renderer happens to own it.
        val shouldDraw = prefs.maxPriority && OverlayService.isRunning &&
            prefs.shouldShowNow(Bridge.foreground)
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

    private companion object {
        /** Grace period for the display metrics to update after a rotation. */
        const val ROTATION_SETTLE_MS = 350L
    }
}
