package com.pure.crosshair

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/**
 * Keeps the overlay alive while other apps are in the foreground.
 *
 * Owns the floating button and the tuning panel at all times. It also owns the crosshair
 * itself, unless max priority mode is on and [MaxPriorityService] has taken over.
 */
class OverlayService : Service(), ControlPanel.Callbacks {

    private lateinit var prefs: Prefs
    private val wm by lazy { windowManager() }
    private val handler = Handler(Looper.getMainLooper())

    private var button: ImageView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var panel: ControlPanel? = null
    private var layer: CrosshairLayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        createChannel()
        goForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        Bridge.overlay = this
        // Must be set before the first refresh: MaxPriorityService checks it to decide
        // whether it should be drawing anything.
        isRunning = true

        panel = ControlPanel(this, prefs, this)
        addButton()

        // Refresh both renderers, not just ours, so a hand off to max priority mode
        // happens on the very first start rather than on the next settings change.
        Bridge.refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                prefs.visible = !prefs.visible
                Bridge.refresh()
                panel?.syncFromPrefs()
            }

            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Windows keep their pixel coordinates across a rotation, so anything positioned near an edge
     * in one orientation lands off screen in the other. Positions are stored per orientation, and
     * this pulls the new set in once the display metrics have caught up.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The callback can arrive before WindowManager reports the new size, so run it twice.
        handler.post { reposition() }
        handler.postDelayed({ reposition() }, ROTATION_SETTLE_MS)
    }

    private fun reposition() {
        val screen = screenSize()

        button?.let { v ->
            buttonParams?.let { lp ->
                val savedX = prefs.buttonX
                val savedY = prefs.buttonY
                lp.x = (if (savedX == Int.MIN_VALUE) screen.x - lp.width - dp(8) else savedX)
                    .coerceIn(0, (screen.x - lp.width).coerceAtLeast(0))
                lp.y = (if (savedY == Int.MIN_VALUE) screen.y / 3 else savedY)
                    .coerceIn(0, (screen.y - lp.height).coerceAtLeast(0))
                runCatching { wm.updateViewLayout(v, lp) }
                prefs.buttonX = lp.x
                prefs.buttonY = lp.y
            }
        }

        // Re-reads the offsets for the orientation we are now in.
        Bridge.refresh()
        // Slider ranges depend on screen width and height, so they change too.
        panel?.syncFromPrefs()
    }

    override fun onDestroy() {
        isRunning = false
        Bridge.overlay = null
        panel?.hide()
        layer?.hide()
        layer = null
        // If the accessibility service is drawing the crosshair, it has to let go as well,
        // otherwise stopping the overlay would leave it stranded on screen.
        Bridge.maxPriority?.applyConfig()
        button?.let { v -> runCatching { wm.removeViewImmediate(v) } }
        button = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Re-reads settings and decides whether this service should be drawing the crosshair.
     * When max priority mode is active the accessibility service draws it instead, so we
     * drop our own window to avoid two crosshairs on screen.
     */
    fun applyConfig() {
        val handedOff = prefs.maxPriority && Bridge.maxPriorityConnected

        if (handedOff) {
            layer?.hide()
            layer = null
        } else {
            val current = layer ?: CrosshairLayer(this, CrosshairLayer.APP_OVERLAY).also {
                it.onMoved = ::onCrosshairMoved
                layer = it
            }
            if (prefs.visible) current.show(prefs) else current.hide()
        }

        updateNotification()
    }

    private fun onCrosshairMoved(x: Int, y: Int) {
        prefs.offsetX = x
        prefs.offsetY = y
        panel?.syncPosition()
    }

    /** Lets the accessibility renderer keep the panel sliders in step while dragging. */
    fun notifyMoved() {
        panel?.syncPosition()
    }

    // ------------------------------------------------------------ panel callbacks

    override fun onConfigChanged() {
        Bridge.refresh()
    }

    override fun onMoveModeChanged(enabled: Boolean) {
        activeLayer()?.let { l ->
            l.setDraggable(enabled)
            l.update(prefs)
        }
    }

    override fun onStopRequested() {
        stopSelf()
    }

    private fun activeLayer(): CrosshairLayer? =
        if (prefs.maxPriority && Bridge.maxPriorityConnected) {
            Bridge.maxPriority?.layer
        } else {
            layer
        }

    // ------------------------------------------------------------ floating button

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val side = dp(BUTTON_DP)
        val screen = screenSize()

        val image = ImageView(this).apply {
            setImageResource(R.drawable.ic_sigil)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = BUTTON_IDLE_ALPHA
            contentDescription = getString(R.string.button_description)
        }

        val lp = WindowManager.LayoutParams(
            side,
            side,
            CrosshairLayer.APP_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.buttonX == Int.MIN_VALUE) screen.x - side - dp(8) else prefs.buttonX
            y = if (prefs.buttonY == Int.MIN_VALUE) screen.y / 3 else prefs.buttonY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        if (runCatching { wm.addView(image, lp) }.isFailure) {
            stopSelf()
            return
        }

        button = image
        buttonParams = lp
        image.setOnTouchListener(buttonTouchListener(image, lp))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buttonTouchListener(
        view: ImageView,
        lp: WindowManager.LayoutParams
    ): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressFired = false

        val longPress = Runnable {
            longPressFired = true
            prefs.visible = !prefs.visible
            Bridge.refresh()
            panel?.syncFromPrefs()
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressFired = false
                    handler.postDelayed(
                        longPress,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                    view.animate().scaleX(0.86f).scaleY(0.86f).alpha(1f)
                        .setDuration(90).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        val screen = screenSize()
                        lp.x = (startX + dx.toInt()).coerceIn(0, screen.x - lp.width)
                        lp.y = (startY + dy.toInt()).coerceIn(0, screen.y - lp.height)
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    view.animate().scaleX(1f).scaleY(1f).alpha(BUTTON_IDLE_ALPHA)
                        .setDuration(140).start()

                    if (dragging) {
                        if (prefs.snapButtonToEdge) {
                            snapToEdge(view, lp)
                        } else {
                            // Leave it exactly where it was dropped, middle of the screen included.
                            prefs.buttonX = lp.x
                            prefs.buttonY = lp.y
                        }
                    } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                        panel?.let { p ->
                            if (p.isShowing) p.hide() else { p.show(); p.syncFromPrefs() }
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** Slides the button to whichever side edge it is closest to. Opt in via settings. */
    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        val screen = screenSize()
        val margin = dp(8)
        val target = if (lp.x + lp.width / 2 < screen.x / 2) margin else screen.x - lp.width - margin

        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                runCatching { wm.updateViewLayout(view, lp) }
            }
            start()
        }

        prefs.buttonX = target
        prefs.buttonY = lp.y
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val state = if (prefs.visible) R.string.notif_showing else R.string.notif_hidden

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(
                0,
                getString(if (prefs.visible) R.string.action_hide else R.string.action_show),
                toggle
            )
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_TOGGLE = "com.pure.crosshair.action.TOGGLE"
        const val ACTION_STOP = "com.pure.crosshair.action.STOP"

        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 41
        private const val BUTTON_DP = 46
        private const val BUTTON_IDLE_ALPHA = 0.85f

        /** Grace period for the display metrics to update after a rotation. */
        private const val ROTATION_SETTLE_MS = 350L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
