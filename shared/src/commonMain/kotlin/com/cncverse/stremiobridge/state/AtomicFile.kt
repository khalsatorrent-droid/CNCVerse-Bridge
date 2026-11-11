package com.cncverse.stremiobridge.state

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes a settings / state file so that it is always either the old version or the new one, never half of it.
 *
 * `writeText` empties the file first and then fills it. Android closes this app whenever it likes (and a crash or a flat battery
 * does the same): closed in between, the file was left empty or cut off, it could not be read at the next start, and what it
 * held - the installed extensions, the stream settings, the profiles - silently went back to nothing. Here the text goes to a
 * file of its own first and takes the real file's place in one step.
 */
fun File.writeTextAtomic(text: String) {
    // a name of its own: two saves at the same moment must not write into one temporary file
    val tmp = File(parentFile, "$name.${System.nanoTime()}.tmp")
    try {
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (tmp.exists()) runCatching { tmp.delete() }
    }
}
