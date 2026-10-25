package com.cncverse.stremiobridge.plugin

import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.ServerState
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Closes the popups extensions open on their own (donation / Telegram / "premium plugins" ads shown when they load).
 * Every few hundred ms the app's windows are checked: a dialog that is not this app's own (Compose dialogs, the
 * Cloudflare check) and was not opened from an extension's settings button is dismissed. Setting: Settings > Streams >
 * "Block extension popups" (on by default).
 */
object PopupGuard {
    private const val SCAN_MS = 400L
    private val allowed: MutableSet<Dialog> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    @Volatile private var userUiAt = 0L
    private var activity: WeakReference<Activity>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** A dialog of this app itself (e.g. the Cloudflare check): never closed. */
    fun allow(d: Dialog) { allowed.add(d) }

    /** The user opened an extension's settings: the dialogs it shows now (and while they are open) are kept. */
    fun userOpenedPluginUi() { userUiAt = System.currentTimeMillis() }

    fun start(act: Activity) {
        activity = WeakReference(act)
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop(act: Activity) {
        if (activity?.get() === act) { running = false; handler.removeCallbacks(tick) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { scan() }
            handler.postDelayed(this, SCAN_MS)
        }
    }

    private fun blockingOn(): Boolean = runCatching { StremioServer.streamPrefs.blockExtensionPopups }.getOrDefault(true)

    private fun scan() {
        val act = activity?.get() ?: return
        if (!blockingOn()) return
        val dialogs = foreignDialogs(act)
        if (dialogs.isEmpty()) return
        // dialogs opened right after the user tapped an extension's settings belong to those settings
        val now = System.currentTimeMillis()
        if (now - userUiAt < 5_000L) { dialogs.forEach { allowed.add(it) }; return }
        // while settings the user opened are still on screen, what they open (pickers...) is kept too
        if (synchronized(allowed) { allowed.any { runCatching { it.isShowing }.getOrDefault(false) && it.ownerActivity.let { o -> o == null || o === act } } } &&
            now - userUiAt < 30 * 60_000L) {
            dialogs.forEach { allowed.add(it) }; return
        }
        for (d in dialogs) {
            runCatching { d.dismiss() }
                .onSuccess { ServerState.info("Closed a popup an extension opened (${d.javaClass.name})") }
        }
    }

    private fun foreignDialogs(act: Activity): List<Dialog> {
        val own = act.window?.decorView
        return rootViews().mapNotNull { v ->
            if (v === own || !v.isAttachedToWindow) return@mapNotNull null
            val d = windowOf(v)?.callback as? Dialog ?: return@mapNotNull null
            if (d.javaClass.name.startsWith("androidx.compose.")) return@mapNotNull null
            if (d in allowed) return@mapNotNull null
            if (!d.isShowing) return@mapNotNull null
            d
        }.distinct()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { android.view.inspector.WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
        }
        return runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
            (f.get(inst) as List<View>).toList()
        }.getOrDefault(emptyList())
    }

    private fun windowOf(v: View): Window? {
        var c: Class<*>? = v.javaClass
        while (c != null && c != View::class.java) {
            val w = runCatching { c.getDeclaredField("mWindow").apply { isAccessible = true }.get(v) as? Window }.getOrNull()
            if (w != null) return w
            c = c.superclass
        }
        return null
    }
}
