package com.pure.crosshair

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.hypot

/**
 * Owns the crosshair window.
 *
 * The window carries FLAG_NOT_TOUCHABLE, so every touch falls straight through to whatever
 * is underneath no matter how opaque the crosshair is. Move mode drops that flag for as long
 * as the user is dragging, then puts it straight back.
 *
 * The same class is used by both renderers; only [windowType] differs.
 */
class CrosshairLayer(
    private val context: Context,
    private val windowType: Int
) {

    private val wm = context.windowManager()
    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    /** What is currently drawn, so slider drags do not rebuild the artwork. */
    private var loadedId: String? = null
    private var loadedColor: Int = 0
    private var loadedDrawable: CrosshairDrawable? = null

    /** Last values pushed to the window, so an unchanged update is skipped entirely. */
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastSide = -1
    private var lastAlpha = -1f
    private var lastFlags = -1

    /** Called with the new centre offset while the crosshair is being dragged. */
    var onMoved: ((x: Int, y: Int) -> Unit)? = null

    private var draggable = false

    val isAttached: Boolean get() = view != null

    /**
     * Hides this crosshair while our own UI is up, for whichever renderer that UI cannot outrank.
     *
     * Window types rank by number, lower on top:
     *
     *   TYPE_ACCESSIBILITY_OVERLAY 2032   crosshair in max priority mode
     *   TYPE_APPLICATION_OVERLAY   2038   crosshair normally, and the tuning panel
     *   TYPE_APPLICATION              1   an activity, and so the picker dialog
     *
     * Both crosshair types outrank an activity, so the crosshair is painted over the picker
     * dialog in either mode. The tuning panel is itself an app overlay, so it only outranks the
     * normal crosshair by being added later, and the accessibility crosshair beats it outright.
     *
     * So: hide for the picker always, and hide for the panel unless this is the normal renderer,
     * which already sits behind it. State comes from [Bridge] rather than a field here, because a
     * layer is created lazily: switching max priority on builds a new one, and it has to know
     * what is already open.
     */
    private fun applySuppression() {
        val v = view ?: return
        val hidden = when {
            Bridge.pickerOpen -> true
            Bridge.panelOpen && windowType == ACCESSIBILITY_OVERLAY -> true
            else -> false
        } && !Bridge.moveMode
        val wanted = if (hidden) View.INVISIBLE else View.VISIBLE
        if (v.visibility != wanted) v.visibility = wanted
    }

    /** Adds the window if needed, then pushes the current settings into it. */
    fun show(prefs: Prefs) {
        if (view == null) attach()
        update(prefs)
    }

    fun update(prefs: Prefs) {
        val v = view ?: return
        val p = params ?: return

        val side = context.dp(prefs.sizeDp)
        val x = prefs.offsetX
        val y = prefs.offsetY
        val alpha = prefs.opacity / 100f
        val flags = flags(clickThrough = !draggable)

        val model = CrosshairCatalog.byId(prefs.selected) ?: CrosshairCatalog.default
        val color = prefs.crosshairColor

        // Before the early return below: whether the panel is up is independent of the window
        // values, so a suppression change must not be skipped as "nothing changed".
        applySuppression()

        // Nothing changed, so nothing to redraw and no layout pass to ask for. This is the
        // common case: a colour or opacity change touches one field, and a repeated refresh
        // from the app should not cost a window update at all.
        val sameArt = loadedId == model.id && loadedColor == color
        if (sameArt && side == lastSide && x == lastX && y == lastY &&
            alpha == lastAlpha && flags == lastFlags
        ) {
            return
        }

        if (!sameArt) {
            if (loadedDrawable != null && loadedId == model.id) {
                // Same reticle, new colour: retint in place instead of rebuilding the drawable.
                loadedDrawable?.setColor(color)
            } else {
                val drawable = CrosshairDrawable(model, color)
                loadedDrawable = drawable
                v.setImageDrawable(drawable)
            }
            loadedId = model.id
            loadedColor = color
        }

        p.width = side
        p.height = side
        p.x = x
        p.y = y
        p.flags = flags

        // Window level alpha, deliberately not View.setAlpha or ImageView.imageAlpha.
        // Android 12+ measures the *window* alpha when deciding whether a click-through
        // overlay is obscuring the app below. A view level fade looks identical on screen
        // but leaves the window at alpha 1.0, so taps would get blocked at every setting.
        p.alpha = alpha

        lastSide = side
        lastX = x
        lastY = y
        lastAlpha = alpha
        lastFlags = flags

        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeViewImmediate(v) } }
        view = null
        params = null
        loadedId = null
        loadedColor = 0
        loadedDrawable = null
        lastSide = -1
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
        lastAlpha = -1f
        lastFlags = -1
    }

    /**
     * Turns the crosshair into a draggable target. Call [update] afterwards to push the
     * flag change through to the window manager.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setDraggable(enabled: Boolean) {
        draggable = enabled
        val v = view ?: return

        if (!enabled) {
            v.setOnTouchListener(null)
            return
        }

        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f

        v.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (event.rawX - downRawX).toInt()
                    p.y = startY + (event.rawY - downRawY).toInt()
                    runCatching { wm.updateViewLayout(v, p) }
                    onMoved?.invoke(p.x, p.y)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // A tap that never moved snaps the crosshair back to dead centre.
                    val travelled = hypot(event.rawX - downRawX, event.rawY - downRawY)
                    if (travelled < context.dp(4)) {
                        p.x = 0
                        p.y = 0
                        runCatching { wm.updateViewLayout(v, p) }
                        onMoved?.invoke(0, 0)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun attach() {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            isFocusable = false
        }

        val lp = WindowManager.LayoutParams(
            1,
            1,
            windowType,
            flags(clickThrough = true),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        runCatching { wm.addView(image, lp) }
            .onSuccess {
                view = image
                params = lp
                // Window alpha is driven by the opacity setting, so the view alpha stays at 1.
                // A view-level fade here would be overwritten by the next update anyway.
                image.alpha = 1f
                // The panel may already be open when this renderer takes the crosshair over.
                applySuppression()
            }
    }

    private fun flags(clickThrough: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (clickThrough) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    companion object {
        /** Highest window type a normal app can ask for. */
        val APP_OVERLAY = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        /** Ranks above app overlays. Only an accessibility service may use it. */
        @Suppress("DEPRECATION")
        val ACCESSIBILITY_OVERLAY = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }
}

/** Keeps the two renderers aware of each other so only one ever owns the crosshair. */
object Bridge {

    @Volatile
    var overlay: OverlayService? = null

    @Volatile
    var maxPriority: MaxPriorityService? = null

    val maxPriorityConnected: Boolean get() = maxPriority != null

    /**
     * True while the tuning panel is up.
     *
     * The crosshair steps aside for the duration. In max priority mode it is an accessibility
     * overlay, which outranks the panel's window, so it would otherwise be painted over the
     * reticle strip and the controls.
     *
     * This lives here rather than in either service because a layer can be created at any time:
     * turning max priority on hands the crosshair to a brand new layer, and that layer has to
     * know the panel is already open.
     */
    @Volatile
    var panelOpen: Boolean = false

    /**
     * True while the "choose a crosshair" dialog is up.
     *
     * That is a normal activity dialog, so it ranks below both crosshair window types and the
     * crosshair would otherwise be painted over it.
     */
    @Volatile
    var pickerOpen: Boolean = false

    /** Move mode needs the crosshair on screen even with the panel open. */
    @Volatile
    var moveMode: Boolean = false

    /**
     * The package currently in front, or null when it is not known.
     *
     * Set by the watcher on [OverlayService]. Both renderers read it so per-app auto-show
     * resolves from a single poll rather than two independent queries.
     */
    @Volatile
    var foreground: String? = null

    /** Re-reads settings in whichever renderers are alive. */
    fun refresh() {
        overlay?.applyConfig()
        maxPriority?.applyConfig()
    }
}
