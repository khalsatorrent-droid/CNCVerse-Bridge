package com.cncverse.stremiobridge.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Threads for the extensions' own (blocking) calls: search, load, loadLinks, home pages. They used to run on the shared
 * Dispatchers.IO pool (64 threads for the whole app): with 30+ extensions searching under two ids, opening two pages
 * each, and the home pages loading next to them, the calls queued behind each other and most extensions hit the 120 s
 * cap. A view of Dispatchers.IO with its own, larger limit does not take threads from the rest of the app either.
 */
object PluginThreads {
    val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(192)
}
