package com.lagradost.cloudstream3

import android.content.Context
import com.cncverse.stremiobridge.repo.AndroidContextHolder
import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.repo.saveExtensionSettings
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class CloudStreamApp {
    companion object {
        @PublishedApi
        internal var inMemorySettings: MutableMap<String, String>? = null

        @PublishedApi
        internal fun getSettings(): MutableMap<String, String> {
            inMemorySettings?.let { return it }
            // Several extensions load at the same time and all read the settings: only one copy may ever exist,
            // otherwise a save from one copy wipes what the other wrote (settings "not saving")
            synchronized(this) {
                return inMemorySettings ?: java.util.concurrent.ConcurrentHashMap<String, String>().also { map ->
                    map.putAll(loadExtensionSettings())
                    inMemorySettings = map
                }
            }
        }

        inline fun <reified T> getKey(path: String): T? {
            val strValue = getSettings()[path] ?: return null
            return when (T::class) {
                String::class -> unquote(strValue) as T
                Int::class -> strValue.toIntOrNull() as? T
                Boolean::class -> strValue.toBooleanStrictOrNull() as? T
                Float::class -> strValue.toFloatOrNull() as? T
                Long::class -> strValue.toLongOrNull() as? T
                Double::class -> strValue.toDoubleOrNull() as? T
                else -> null
            }
        }
        
        /**
         * Extensions read their settings with the real Cloudstream `getKey`, which JSON-decodes the stored text. The real
         * `setKey` stores JSON, so a text that is itself a JSON list or object (NetNaija's switched-off sources) is stored
         * as a quoted JSON string, and other objects (sets, lists, data classes) as JSON. Before, they were stored as
         * `toString()` and could never be read back: the switches always went back to their old values.
         */
        @PublishedApi
        internal fun encodeValue(value: Any): String = when (value) {
            is String -> {
                val t = value.trim()
                if ((t.startsWith("[") || t.startsWith("{")) &&
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(t) }.isSuccess
                ) kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), value) else value
            }
            is Number, is Boolean -> value.toString()
            else -> runCatching { value.toJson() }.getOrElse { value.toString() }
        }

        @PublishedApi
        internal fun unquote(v: String): String {
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                runCatching { kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.serializer<String>(), v) }
                    .getOrNull()?.let { return it }
            }
            return v
        }

        fun setKey(path: String, value: Any?) {
            synchronized(this) {
                val settings = getSettings()
                if (value == null) {
                    settings.remove(path)
                } else {
                    settings[path] = encodeValue(value)
                }
                saveExtensionSettings(HashMap(settings))
            }
        }

        fun getContext(): Context? {
            return AndroidContextHolder.appContext
        }
    }
}
