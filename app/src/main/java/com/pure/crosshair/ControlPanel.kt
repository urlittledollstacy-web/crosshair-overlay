package com.pure.crosshair

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.pure.crosshair.databinding.OverlayPanelBinding
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The tuning panel that opens from the floating button.
 *
 * It is its own window rather than an activity so the app underneath keeps running and
 * stays visible while the crosshair is being lined up.
 */
class ControlPanel(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** A value changed and the crosshair needs redrawing. */
        fun onConfigChanged()

        /** Move mode was switched on or off. */
        fun onMoveModeChanged(enabled: Boolean)

        /** The user asked to shut the overlay down. */
        fun onStopRequested()
    }

    private val wm = context.windowManager()
    private var binding: OverlayPanelBinding? = null
    private var themed: Context? = null

    /** One handler for the whole panel. The steppers used to create one each, never cleaned up. */
    private val handler = Handler(Looper.getMainLooper())

    /** Guards against slider listeners firing while we push values in programmatically. */
    private var syncing = false

    var moveMode = false
        private set

    val isShowing: Boolean get() = binding != null

    fun toggle() = if (isShowing) hide() else show()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        val themed = ContextThemeWrapper(context, R.style.Theme_Crosshair_Overlay)
        this.themed = themed
        val b = OverlayPanelBinding.inflate(LayoutInflater.from(themed))
        binding = b

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            CrosshairLayer.APP_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            windowAnimations = 0
        }

        buildPicker(b)
        buildColors(b)
        wireControls(b)
        syncFromPrefs()

        // Tap anywhere outside the card, or press back, to close.
        b.panelRoot.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hide()
                true
            } else {
                false
            }
        }
        b.panelRoot.isFocusableInTouchMode = true
        b.panelRoot.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hide()
                true
            } else {
                false
            }
        }

        val added = runCatching { wm.addView(b.panelRoot, lp) }.isSuccess
        if (!added) {
            binding = null
            return
        }
        b.panelRoot.requestFocus()

        // The card holds a strip plus five sliders, so on a short screen it can outgrow the
        // display. Once measured, cap the window and let the card scroll inside it.
        //
        // The cap is measured against the card, not the root: the root is the card plus its
        // margins, so comparing the root against the cap left the panel taller than intended
        // by exactly those margins.
        b.panelCard.post {
            val cap = (prefs.screen.y * MAX_HEIGHT_FRACTION).toInt()
            if (b.panelCard.height > cap) {
                lp.height = cap + b.panelRoot.height - b.panelCard.height
                b.panelCard.layoutParams = (b.panelCard.layoutParams as FrameLayout.LayoutParams)
                    .apply { height = FrameLayout.LayoutParams.MATCH_PARENT }
                runCatching { wm.updateViewLayout(b.panelRoot, lp) }
            }
        }

        b.reticleScroll.post {
            val index = CrosshairCatalog.all.indexOfFirst { it.id == prefs.selected }
            val child = b.reticleRow.getChildAt(index)
            if (child != null) {
                b.reticleScroll.scrollTo(
                    (child.left - b.reticleScroll.width / 2 + child.width / 2).coerceAtLeast(0),
                    0
                )
            }
        }

        b.panelCard.post {
            b.panelCard.translationY = b.panelCard.height.toFloat()
            b.panelCard.alpha = 0f
            b.panelCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(200)
                .start()
        }
    }

    fun hide() {
        val b = binding ?: return
        binding = null
        handler.removeCallbacksAndMessages(null)
        if (moveMode) setMoveMode(false, b)
        runCatching { wm.removeViewImmediate(b.panelRoot) }
    }

    /** Called while the crosshair is dragged so the X/Y sliders keep up. */
    fun syncPosition() {
        val b = binding ?: return
        val screen = prefs.screen
        syncing = true
        b.seekX.progress = (prefs.offsetX + screen.x / 2).coerceIn(0, screen.x)
        b.seekY.progress = (prefs.offsetY + screen.y / 2).coerceIn(0, screen.y)
        syncing = false
        b.valueX.text = signed(prefs.offsetX)
        b.valueY.text = signed(prefs.offsetY)
    }

    fun syncFromPrefs() {
        val b = binding ?: return
        val screen = prefs.screen

        syncing = true
        b.seekSize.max = SIZE_STEPS
        b.seekSize.progress = sizeToProgress(prefs.sizeDp)

        b.seekOpacity.max = 100 - Prefs.MIN_OPACITY
        b.seekOpacity.progress = prefs.opacity - Prefs.MIN_OPACITY

        b.seekX.max = screen.x
        b.seekX.progress = (prefs.offsetX + screen.x / 2).coerceIn(0, screen.x)

        b.seekY.max = screen.y
        b.seekY.progress = (prefs.offsetY + screen.y / 2).coerceIn(0, screen.y)
        syncing = false

        b.valueSize.text = context.getString(R.string.value_dp, prefs.sizeDp)
        b.valueOpacity.text = context.getString(R.string.value_percent, prefs.opacity)
        b.valueX.text = signed(prefs.offsetX)
        b.valueY.text = signed(prefs.offsetY)

        b.btnVisible.isSelected = prefs.visible
        b.btnVisible.setText(if (prefs.visible) R.string.crosshair_on else R.string.crosshair_off)
        b.btnMove.isSelected = moveMode

        updatePickerSelection(b)
        updateOpacityWarning(b)
    }

    /**
     * Android 12 blocks touches that pass through an overlay more than 80% opaque, to stop
     * tapjacking. Accessibility overlays are exempt, so max priority mode is the way to get a
     * solid crosshair that still lets taps through. Warn rather than cap: on Android 11 and
     * below, and in max priority mode, 100% is completely fine.
     */
    private fun updateOpacityWarning(b: OverlayPanelBinding) {
        val trusted = prefs.maxPriority && Bridge.maxPriorityConnected
        val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            prefs.opacity > MAX_PASS_THROUGH_OPACITY &&
            !trusted
        b.opacityWarning.visibility = if (blocked) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- internals

    /**
     * The reticle strip.
     *
     * One scrolling row rather than a grid. A grid of all 35 tiles was taller than every other
     * control put together, which pushed the sliders below the fold and made the panel read as
     * a wall of thumbnails instead of a tuning surface.
     */
    private fun buildPicker(b: OverlayPanelBinding) {
        val themed = this.themed ?: return
        val gap = context.dp(8)
        val cell = context.dp(RETICLE_CELL_DP)

        b.reticleRow.removeAllViews()

        for (model in CrosshairCatalog.all) {
            val tile = ImageView(themed).apply {
                layoutParams = LinearLayout.LayoutParams(cell, cell).also { it.marginEnd = gap }
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(model.drawable(prefs.crosshairColor))
                background = context.getDrawable(R.drawable.bg_thumb)
                isSelected = model.id == prefs.selected
                contentDescription = model.name
                setOnClickListener {
                    prefs.selected = model.id
                    updatePickerSelection(b)
                    callbacks.onConfigChanged()
                }
            }
            b.reticleRow.addView(tile)
        }
    }

    /** The colour row: a ring per colour, filled with that colour. */
    private fun buildColors(b: OverlayPanelBinding) {
        val themed = this.themed ?: return
        val slot = context.dp(36)
        val fill = context.dp(26)
        val gap = context.dp(8)

        b.colorRow.removeAllViews()

        for ((name, color) in CrosshairColors.all) {
            val swatch = FrameLayout(themed).apply {
                layoutParams = LinearLayout.LayoutParams(slot, slot).also { it.marginEnd = gap }
                background = context.getDrawable(R.drawable.bg_swatch_slot)
                isSelected = color == prefs.crosshairColor
                contentDescription = name
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.crosshairColor = color
                    updatePickerSelection(b)
                    callbacks.onConfigChanged()
                }
            }
            swatch.addView(
                View(themed).apply {
                    layoutParams = FrameLayout.LayoutParams(fill, fill, Gravity.CENTER)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                }
            )
            b.colorRow.addView(swatch)
        }
    }

    private fun updatePickerSelection(b: OverlayPanelBinding) {
        val models = CrosshairCatalog.all
        for (i in models.indices) {
            b.reticleRow.getChildAt(i)?.isSelected = models[i].id == prefs.selected
        }
        // Swatches sit in the same order as the palette, so index lookup is enough.
        val colorIndex = CrosshairColors.all.indexOfFirst { it.second == prefs.crosshairColor }
        for (i in 0 until b.colorRow.childCount) {
            b.colorRow.getChildAt(i).isSelected = i == colorIndex
        }
        // Retint in place rather than rebuilding 35 drawables on every colour change.
        for (i in models.indices) {
            ((b.reticleRow.getChildAt(i) as? ImageView)?.drawable as? CrosshairDrawable)
                ?.setColor(prefs.crosshairColor)
        }

        // The header mark is the reticle you actually have selected, in the colour you
        // actually chose. It is the one place the panel shows the product rather than
        // describing it, and it updates as you change either.
        val model = CrosshairCatalog.byId(prefs.selected) ?: CrosshairCatalog.default
        b.panelMark.setImageDrawable(model.drawable(prefs.crosshairColor))
        b.valueCrosshair.text = model.name
    }

    private fun wireControls(b: OverlayPanelBinding) {
        b.panelClose.setOnClickListener { hide() }

        b.btnVisible.setOnClickListener {
            prefs.visible = !prefs.visible
            b.btnVisible.isSelected = prefs.visible
            b.btnVisible.setText(if (prefs.visible) R.string.crosshair_on else R.string.crosshair_off)
            callbacks.onConfigChanged()
        }

        b.btnMove.setOnClickListener { setMoveMode(!moveMode, b) }

        b.btnCenter.setOnClickListener {
            prefs.resetPlacement()
            syncFromPrefs()
            callbacks.onConfigChanged()
        }

        b.btnStop.setOnClickListener { callbacks.onStopRequested() }

        b.seekSize.setOnSeekBarChangeListener(onChange { value ->
            prefs.sizeDp = progressToSize(value)
            b.valueSize.text = context.getString(R.string.value_dp, prefs.sizeDp)
        })

        b.seekOpacity.setOnSeekBarChangeListener(onChange { value ->
            prefs.opacity = Prefs.MIN_OPACITY + value
            b.valueOpacity.text = context.getString(R.string.value_percent, prefs.opacity)
            updateOpacityWarning(b)
        })

        b.seekX.setOnSeekBarChangeListener(onChange { value ->
            prefs.offsetX = value - prefs.screen.x / 2
            b.valueX.text = signed(prefs.offsetX)
        })

        b.seekY.setOnSeekBarChangeListener(onChange { value ->
            prefs.offsetY = value - prefs.screen.y / 2
            b.valueY.text = signed(prefs.offsetY)
        })

        repeatWhileHeld(b.stepSizeMinus) { nudgeSize(b, -1) }
        repeatWhileHeld(b.stepSizePlus) { nudgeSize(b, 1) }
        repeatWhileHeld(b.stepXMinus) { nudgeX(b, -1) }
        repeatWhileHeld(b.stepXPlus) { nudgeX(b, 1) }
        repeatWhileHeld(b.stepYMinus) { nudgeY(b, -1) }
        repeatWhileHeld(b.stepYPlus) { nudgeY(b, 1) }
    }

    /**
     * The size range spans 4dp to 1200dp. Mapped linearly onto a slider a few hundred pixels
     * wide, one pixel of travel is several dp, which makes any specific value unreachable.
     * A logarithmic curve gives the small sizes most of the track, where the useful values are,
     * and compresses the rarely used top end.
     */
    private fun sizeToProgress(sizeDp: Int): Int {
        val min = Prefs.MIN_SIZE.toDouble()
        val ratio = Prefs.MAX_SIZE.toDouble() / min
        val clamped = sizeDp.coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
        return ((ln(clamped / min) / ln(ratio)) * SIZE_STEPS).roundToInt()
            .coerceIn(0, SIZE_STEPS)
    }

    private fun progressToSize(progress: Int): Int {
        val min = Prefs.MIN_SIZE.toDouble()
        val ratio = Prefs.MAX_SIZE.toDouble() / min
        val t = progress.toDouble() / SIZE_STEPS
        return (min * ratio.pow(t)).roundToInt().coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
    }

    /** Exactly one dp per press, so the slider gets you close and these land it. */
    private fun nudgeSize(b: OverlayPanelBinding, delta: Int) {
        prefs.sizeDp = (prefs.sizeDp + delta).coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
        syncing = true
        b.seekSize.progress = sizeToProgress(prefs.sizeDp)
        syncing = false
        b.valueSize.text = context.getString(R.string.value_dp, prefs.sizeDp)
        callbacks.onConfigChanged()
    }

    /**
     * Fires once on press, then repeats with an accelerating rate while held, so covering a
     * few hundred dp does not mean a few hundred taps.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatWhileHeld(view: View, action: () -> Unit) {
        var pending: Runnable? = null

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    val repeater = object : Runnable {
                        var delay = REPEAT_FIRST_MS
                        override fun run() {
                            action()
                            delay = (delay * REPEAT_DECAY).toLong().coerceAtLeast(REPEAT_MIN_MS)
                            handler.postDelayed(this, delay)
                        }
                    }
                    pending = repeater
                    handler.postDelayed(repeater, REPEAT_FIRST_MS)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    pending = null
                    v.isPressed = false
                    true
                }

                else -> true
            }
        }
    }

    private fun nudgeX(b: OverlayPanelBinding, delta: Int) {
        val half = prefs.screen.x / 2
        prefs.offsetX = (prefs.offsetX + delta).coerceIn(-half, half)
        syncing = true
        b.seekX.progress = prefs.offsetX + half
        syncing = false
        b.valueX.text = signed(prefs.offsetX)
        callbacks.onConfigChanged()
    }

    private fun nudgeY(b: OverlayPanelBinding, delta: Int) {
        val half = prefs.screen.y / 2
        prefs.offsetY = (prefs.offsetY + delta).coerceIn(-half, half)
        syncing = true
        b.seekY.progress = prefs.offsetY + half
        syncing = false
        b.valueY.text = signed(prefs.offsetY)
        callbacks.onConfigChanged()
    }

    private fun setMoveMode(enabled: Boolean, b: OverlayPanelBinding) {
        moveMode = enabled
        b.btnMove.isSelected = enabled
        b.btnMove.setText(if (enabled) R.string.move_done else R.string.move)
        b.moveHint.visibility = if (enabled) View.VISIBLE else View.GONE
        callbacks.onMoveModeChanged(enabled)
    }

    private fun onChange(apply: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (syncing) return
            apply(progress)
            callbacks.onConfigChanged()
        }

        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }

    private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

    companion object {
        /** Matches the platform default for InputManager#getMaximumObscuringOpacityForTouch. */
        private const val MAX_PASS_THROUGH_OPACITY = 80

        /** Slider resolution. Combined with the log curve this is well under 1dp per step
         *  through the range people actually use. */
        private const val SIZE_STEPS = 1000

        /** Reticle tile edge in the strip. */
        private const val RETICLE_CELL_DP = 48

        /** Tallest the panel may get before the card starts scrolling. */
        private const val MAX_HEIGHT_FRACTION = 0.86f

        private const val REPEAT_FIRST_MS = 380L
        private const val REPEAT_MIN_MS = 35L
        private const val REPEAT_DECAY = 0.80f
    }
}
