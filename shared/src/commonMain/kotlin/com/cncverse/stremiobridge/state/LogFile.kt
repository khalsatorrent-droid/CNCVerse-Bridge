package com.cncverse.stremiobridge.state

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Every log line is also written to a file (<app data>/logs/bridge.log), so the log of a run that crashed or was killed is
 * still there on the next start (Logs -> share button).
 *
 * Kept off the hot path: [append] only puts the line in a queue (no formatting, no disk); one low-priority thread writes the
 * lines in batches. Each batch is handed to the system at once, so a crash loses at most the last few milliseconds; the
 * crash handler calls [flushNow] for the rest. The file is cut at 2 MB (the previous part is kept as bridge.1.log), so the
 * two never take more than 4 MB.
 */
object LogFile {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_PENDING = 3_000

    private class Entry(val time: Long, val level: LogLevel, val message: String)

    private val queue = LinkedBlockingQueue<Entry>(10_000)
    /** lines that came before the app's data folder was known */
    private val pending = ArrayList<Entry>()
    @Volatile private var started = false
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** The current log file, or null before the app's data folder is known. */
    fun file(): File? = dir()?.let { File(it, "bridge.log") }

    private fun dir(): File? {
        val base = com.cncverse.stremiobridge.server.BridgeRuntime.cacheDir
        if (base.isBlank()) return null
        return File(base, "logs").also { if (!it.isDirectory) it.mkdirs() }
    }

    fun append(time: Long, level: LogLevel, message: String) {
        queue.offer(Entry(time, level, message)) // full queue (a flood): the line is dropped, never waited for
        if (!started) start()
    }

    private fun start() = synchronized(this) {
        if (started) return
        started = true
        Thread({
            queue.offer(Entry(System.currentTimeMillis(), LogLevel.INFO, "==================== app started ===================="))
            val batch = ArrayList<Entry>(512)
            while (true) {
                try {
                    val first = queue.poll(2, TimeUnit.SECONDS)
                    if (first != null) { batch.add(first); queue.drainTo(batch, 2_000) }
                    if (batch.isNotEmpty() || pending.isNotEmpty()) write(batch)
                } catch (_: Throwable) {
                    // a full disk or a missing folder must never take the app down
                } finally {
                    batch.clear()
                }
            }
        }, "log-file").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Writes whatever is still queued, on the calling thread (the crash handler, just before the process ends). */
    fun flushNow() {
        runCatching {
            val batch = ArrayList<Entry>()
            queue.drainTo(batch)
            write(batch)
        }
    }

    @Synchronized
    private fun write(batch: List<Entry>) {
        val f = file()
        if (f == null) {
            pending.addAll(batch)
            if (pending.size > MAX_PENDING) pending.subList(0, pending.size - MAX_PENDING).clear()
            return
        }
        if (f.length() > MAX_BYTES) {
            val old = File(f.parentFile, "bridge.1.log")
            old.delete()
            f.renameTo(old)
        }
        val sb = StringBuilder(4096)
        fun line(e: Entry) {
            sb.append(fmt.format(Date(e.time))).append(' ').append(e.level.name.padEnd(5)).append(' ').append(e.message).append('\n')
        }
        pending.forEach(::line)
        pending.clear()
        batch.forEach(::line)
        if (sb.isEmpty()) return
        java.io.FileOutputStream(f, true).use { it.write(sb.toString().toByteArray()) }
    }
}

/** Opens the system's share sheet (Android) or the folder (desktop) for the log file; false when it could not. */
expect fun shareLogFile(path: String): Boolean
