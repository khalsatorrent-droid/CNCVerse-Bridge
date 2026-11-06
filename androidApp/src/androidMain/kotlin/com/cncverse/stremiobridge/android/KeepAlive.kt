package com.cncverse.stremiobridge.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Brings the bridge back when Android (or the phone maker's battery saver) closed it while it should be running:
 *  - after a restart of the phone and after an app update (the server is started again if it was on before),
 *  - every 15 minutes (an alarm that also fires in Doze's maintenance windows): if the service is gone, it is started again,
 *  - a few seconds after the app is swiped away, in case the service could not restart itself.
 * Pressing Stop switches all of this off until the server is started again by hand.
 * Starting a service from the background needs the battery-optimisation exemption on Android 12+ (Settings -> Keep running).
 */
object KeepAlive {
    private const val TAG = "KeepAlive"
    private const val PREFS = "bridge_prefs"
    private const val KEY = "keep_running"
    const val ACTION_CHECK = "com.cncverse.stremiobridge.KEEP_ALIVE_CHECK"
    private const val EVERY_MS = 15 * 60 * 1000L

    fun wanted(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setWanted(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) schedule(ctx) else cancel(ctx)
    }

    private fun pending(ctx: Context, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, KeepAliveReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context) = runCatching {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY_MS, EVERY_MS, pending(ctx, 1))
    }

    /** One check soon (after the app was swiped away). */
    fun checkSoon(ctx: Context, delayMs: Long = 5_000) = runCatching {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending(ctx, 2))
    }

    fun cancel(ctx: Context) = runCatching {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, 1)); am.cancel(pending(ctx, 2))
    }

    /** Starts the service when it should run and is not running; false when Android did not allow it. */
    fun startIfNeeded(ctx: Context, why: String): Boolean {
        if (!wanted(ctx) || StremioForegroundService.alive) return true
        return try {
            val i = Intent(ctx, StremioForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            Log.i(TAG, "Bridge started again ($why)")
            com.cncverse.stremiobridge.state.ServerState.info("[Keep running] the bridge was closed, started it again ($why)")
            true
        } catch (e: Exception) {
            // Android 12+: not allowed from the background without the battery-optimisation exemption
            Log.w(TAG, "Could not start the bridge ($why): ${e.message}")
            false
        }
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!KeepAlive.wanted(ctx)) return
                KeepAlive.schedule(ctx)
                KeepAlive.startIfNeeded(ctx, if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "after the app update" else "after the phone restarted")
            }
            KeepAlive.ACTION_CHECK -> KeepAlive.startIfNeeded(ctx, "it had been closed by the system")
        }
    }
}
