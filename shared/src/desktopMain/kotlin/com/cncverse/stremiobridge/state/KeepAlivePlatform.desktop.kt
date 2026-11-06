package com.cncverse.stremiobridge.state

actual object KeepAlivePlatform {
    actual val supported: Boolean = false
    actual fun batteryExempt(): Boolean = true
    actual fun openBatterySettings() {}
    actual fun openAutostartSettings(): Boolean = false
    actual fun openAppSettings() {}
}
