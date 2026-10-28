package com.cncverse.stremiobridge.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.cncverse.stremiobridge.repo.AndroidContextHolder

const val NOTIF_CHANNEL_ID   = "stremio_bridge_service"
const val NOTIF_CHANNEL_NAME = "Stremio Bridge"

class StremioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        AndroidContextHolder.appContext = this
        // The Cloudstream library's WebViewResolver (Cloudflare-protected sites) needs this, otherwise
        // every call fails with "No base context in WebViewResolver"
        // CloudStream library from Oct 2026 on: setContext(Any?) keeps its own WeakReference; older copies took one
        // (setCtx / setContext(WeakReference)) - whichever this library has is called
        runCatching {
            val helper = Class.forName("com.lagradost.api.ContextHelper_androidKt")
            runCatching { helper.getMethod("setContext", Any::class.java).invoke(null, this) }
            runCatching { helper.getMethod("setCtx", java.lang.ref.WeakReference::class.java).invoke(null, java.lang.ref.WeakReference<Any>(this)) }
            runCatching { helper.getMethod("setContext", java.lang.ref.WeakReference::class.java).invoke(null, java.lang.ref.WeakReference<Any>(this)) }
        }
        // Shared bridge runtime (same lifecycle code as the desktop app); the scope lives
        // as long as the process so health sweeps / updates outlive the activity
        com.cncverse.stremiobridge.server.BridgeRuntime.cacheDir = filesDir.absolutePath
        com.cncverse.stremiobridge.server.BridgeRuntime.appScope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        createNotificationChannel()
        installCrashLog()
    }

    /**
     * Writes what killed the previous run into the log (Logs tab): an uncaught exception is saved to a file before the
     * process ends, and Android's own record of why a process died (crash, out of memory, a foreground service that ran
     * into its time limit, ...) is read on the next start.
     */
    private fun installCrashLog() {
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile.writeText("${System.currentTimeMillis()} thread=${t.name}\n" + e.stackTraceToString().take(6000))
            }
            previous?.uncaughtException(t, e)
        }
        runCatching {
            if (crashFile.exists()) {
                val text = crashFile.readText()
                crashFile.delete()
                com.cncverse.stremiobridge.state.ServerState.warn("[Crash] The app stopped with an uncaught error last time:\n" + text.take(1500))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(packageName, 0, 3).forEach { r ->
                val reason = when (r.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
                    android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped by the user"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
                    android.app.ApplicationExitInfo.REASON_OTHER -> "other"
                    else -> "code ${r.reason}"
                }
                if (r.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED)
                    com.cncverse.stremiobridge.state.ServerState.info("[Exit] earlier run ended: $reason (${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(r.timestamp))})" + (r.description?.let { " - $it" } ?: ""))
            }
        }
    }

    /**
     * Android asks running apps to give memory back when the phone runs short (other apps would be closed otherwise):
     * the bridge drops its cached extension pages and older searches - they are loaded again when needed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val critical = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        @Suppress("DEPRECATION")
        // not on UI_HIDDEN (the app's screen was just left - normal while watching in another app)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) runCatching { com.cncverse.stremiobridge.server.StremioServer.trimMemory(critical) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { com.cncverse.stremiobridge.server.StremioServer.trimMemory(true) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while the Stremio addon server is running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var appContext: Application
    }
}
