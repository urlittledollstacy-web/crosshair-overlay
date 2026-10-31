package com.pure.crosshair

import android.content.Context
import androidx.core.content.edit

/**
 * Every tunable in one place, so the panel, the two renderers and the boot receiver
 * always read the same values.
 */
class Prefs(context: Context) {

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("crosshair", Context.MODE_PRIVATE)

    /**
     * Positions are stored per orientation. A pixel offset that centres the crosshair nicely in
     * portrait points somewhere else entirely once width and height swap, so landscape gets its
     * own set of values rather than inheriting broken ones.
     *
     * Orientation is derived from real screen metrics instead of Configuration, because the
     * Configuration attached to a Service context is not always current at the moment we read it.
     */
    private val orientation: String
        get() {
            val size = app.screenSize()
            return if (size.x > size.y) "_land" else "_port"
        }

    /** Id of the selected crosshair in [CrosshairCatalog]. Unknown ids fall back to the default. */
    var selected: String
        get() = sp.getString(KEY_SELECTED, "").orEmpty()
            .takeIf { CrosshairCatalog.isValid(it) }
            ?: CrosshairCatalog.default.id
        set(value) = sp.edit { putString(KEY_SELECTED, value) }

    /** Colour the crosshair is drawn in, as ARGB. */
    var crosshairColor: Int
        get() = sp.getInt(KEY_COLOR, CrosshairColors.default)
        set(value) = sp.edit { putInt(KEY_COLOR, value) }

    /** Crosshair edge length in dp. */
    var sizeDp: Int
        get() = sp.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
        set(value) = sp.edit { putInt(KEY_SIZE, value.coerceIn(MIN_SIZE, MAX_SIZE)) }

    /** 5..100. At 100 the crosshair is fully solid; it stays click-through either way. */
    var opacity: Int
        get() = sp.getInt(KEY_OPACITY, 100).coerceIn(MIN_OPACITY, 100)
        set(value) = sp.edit { putInt(KEY_OPACITY, value.coerceIn(MIN_OPACITY, 100)) }

    /** Pixel offset from the exact centre of the screen, per orientation. */
    var offsetX: Int
        get() = sp.getInt(KEY_X + orientation, 0)
        set(value) = sp.edit { putInt(KEY_X + orientation, value) }

    var offsetY: Int
        get() = sp.getInt(KEY_Y + orientation, 0)
        set(value) = sp.edit { putInt(KEY_Y + orientation, value) }

    var visible: Boolean
        get() = sp.getBoolean(KEY_VISIBLE, true)
        set(value) = sp.edit { putBoolean(KEY_VISIBLE, value) }

    var buttonX: Int
        get() = sp.getInt(KEY_BTN_X + orientation, Int.MIN_VALUE)
        set(value) = sp.edit { putInt(KEY_BTN_X + orientation, value) }

    var buttonY: Int
        get() = sp.getInt(KEY_BTN_Y + orientation, Int.MIN_VALUE)
        set(value) = sp.edit { putInt(KEY_BTN_Y + orientation, value) }

    /**
     * Hides the floating button so a stray tap cannot open the panel mid-game. The panel is
     * then reachable from the notification and from the main screen, and both say so.
     */
    var buttonHidden: Boolean
        get() = sp.getBoolean(KEY_BTN_HIDDEN, false)
        set(value) = sp.edit { putBoolean(KEY_BTN_HIDDEN, value) }

    /** When off, the floating button stays wherever it is dropped, including mid screen. */
    var snapButtonToEdge: Boolean
        get() = sp.getBoolean(KEY_SNAP, false)
        set(value) = sp.edit { putBoolean(KEY_SNAP, value) }

    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_BOOT, false)
        set(value) = sp.edit { putBoolean(KEY_BOOT, value) }

    /** Render through the accessibility service so the crosshair outranks other overlays. */
    var maxPriority: Boolean
        get() = sp.getBoolean(KEY_MAX_PRIORITY, false)
        set(value) = sp.edit { putBoolean(KEY_MAX_PRIORITY, value) }

    /** Recentres the crosshair in the current orientation only. */
    fun resetPlacement() {
        sp.edit {
            putInt(KEY_X + orientation, 0)
            putInt(KEY_Y + orientation, 0)
        }
    }

    companion object {
        // Wide range on purpose: a 4dp aiming dot and a near full screen reticle are both
        // legitimate uses, and every reticle is normalised to the same footprint.
        const val MIN_SIZE = 4
        const val MAX_SIZE = 1200
        const val DEFAULT_SIZE = 96
        const val MIN_OPACITY = 5

        private const val KEY_SELECTED = "selected"
        private const val KEY_COLOR = "crosshair_color"
        private const val KEY_SIZE = "size_dp"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_X = "offset_x"
        private const val KEY_Y = "offset_y"
        private const val KEY_VISIBLE = "visible"
        private const val KEY_BTN_X = "button_x"
        private const val KEY_BTN_Y = "button_y"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_MAX_PRIORITY = "max_priority"
        private const val KEY_SNAP = "snap_button"
        private const val KEY_BTN_HIDDEN = "button_hidden"
    }
}
