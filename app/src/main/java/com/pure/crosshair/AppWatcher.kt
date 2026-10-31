package com.pure.crosshair

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

/**
 * Reports which app is currently in the foreground.
 *
 * Uses UsageStats, which needs a one time "usage access" grant the user gives in system
 * settings. That is the only supported way for a normal app to see the foreground package on
 * modern Android; reading the task stack was closed off years ago.
 *
 * Polling rather than a listener, because there is no callback for this that survives Doze and
 * works on every OEM. The poll runs on its own thread: it is a binder call into system_server,
 * and doing it on the main thread would risk janking the overlay it exists to drive.
 */
class AppWatcher(
    private val context: Context,
    private val shouldPoll: () -> Boolean,
    private val onForegroundChanged: (String?) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var running = false

    /** Last value handed to the callback, so an unchanged poll costs nothing downstream. */
    private var lastReported: String? = null

    /**
     * The package currently in front, or null when it is not known.
     *
     * Read by both renderers so they resolve the same answer from one poll rather than each
     * running their own query.
     */
    @Volatile
    var foreground: String? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            // Skip the query entirely when nothing is listening for the answer. The poll is a
            // binder call, and per-app auto-show is off by default, so this keeps the cost at
            // zero for everyone who does not use it.
            if (shouldPoll()) {
                val foreground = currentForeground()
                this@AppWatcher.foreground = foreground
                if (foreground != lastReported) {
                    lastReported = foreground
                    // Back to the main thread: the callback touches window state.
                    main.post { if (running) onForegroundChanged(foreground) }
                }
            }
            if (running) worker?.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        // Reset so the first poll always reports, even if it matches the value from last time.
        lastReported = null

        val t = HandlerThread("app-watcher").also { it.start() }
        thread = t
        worker = Handler(t.looper).also { it.post(tick) }
    }

    fun stop() {
        running = false
        worker?.removeCallbacksAndMessages(null)
        worker = null
        thread?.quitSafely()
        thread = null
        main.removeCallbacksAndMessages(null)
    }

    private fun currentForeground(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()

        // The last foreground transition is exactly what "in front right now" means, and unlike
        // lastTimeUsed it is not confused by an app that is merely still resident.
        var candidate: String? = null
        runCatching {
            val events = usm.queryEvents(now - EVENT_WINDOW_MS, now) ?: return@runCatching
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                val resumed = type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        type == UsageEvents.Event.ACTIVITY_RESUMED)
                if (resumed && event.packageName != null) candidate = event.packageName
            }
        }
        if (candidate != null) return candidate

        // Nothing in the last half minute, which is the normal case when the phone has been
        // sitting on one app. The most recently used package is the same answer for our
        // purposes, and it also covers a device that has just booted.
        return runCatching {
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - STATS_WINDOW_MS,
                now
            )?.filter { it.lastTimeUsed > 0 }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
        }.getOrNull()
    }

    companion object {
        /**
         * How often to look. Fast enough that switching apps feels instant, slow enough that
         * the cost is negligible: the query is a few milliseconds against a small window.
         */
        private const val POLL_MS = 700L

        /** Window for the event scan. Long enough to catch the transition that put us here. */
        private const val EVENT_WINDOW_MS = 30_000L

        /** Window for the fallback. A day is what the daily interval bucket expects. */
        private const val STATS_WINDOW_MS = 24 * 60 * 60 * 1000L

        /**
         * Whether the user has granted usage access.
         *
         * Checked through AppOps rather than by trying a query, because a query without access
         * returns an empty list instead of failing, which is indistinguishable from a phone
         * where nothing is running.
         */
        fun hasAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                }
            }.getOrNull()
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
