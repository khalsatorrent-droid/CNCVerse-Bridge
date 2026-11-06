package com.cncverse.stremiobridge.state

/** The phone settings that decide whether Android may close the bridge in the background (nothing on desktop). */
expect object KeepAlivePlatform {
    val supported: Boolean
    /** Exempt from battery optimisation (needed for the bridge to restart itself in the background). */
    fun batteryExempt(): Boolean
    fun openBatterySettings()
    /** The phone maker's "auto-start" / "background activity" screen (Xiaomi, Oppo, Vivo, Huawei...); false when there is none. */
    fun openAutostartSettings(): Boolean
    fun openAppSettings()
}
