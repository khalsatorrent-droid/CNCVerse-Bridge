package com.cncverse.stremiobridge.state

import java.io.File

actual fun shareLogFile(path: String): Boolean = runCatching {
    java.awt.Desktop.getDesktop().open(File(path).parentFile)
    true
}.getOrDefault(false)
