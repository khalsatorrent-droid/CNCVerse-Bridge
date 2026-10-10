package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp

object DataStore {
    fun Context.getSharedPrefs(): android.content.SharedPreferences {
        return JsonSafePrefs(getSharedPreferences("cnc_ext_settings", Context.MODE_PRIVATE))
    }

    /**
     * Extensions read their settings with the real Cloudstream `getKey`, which JSON-decodes the stored text.
     * Values the bridge saved as plain text ("https://animepahe.pw", a user agent, a cookie, "p_a,p_b") are not
     * valid JSON and made every such read fail, which is why AnimePahe found nothing. Plain text is handed out
     * as a JSON string instead; valid JSON (numbers, booleans, objects) is left untouched.
     */
    class JsonSafePrefs(private val base: android.content.SharedPreferences) : android.content.SharedPreferences by base {
        override fun getString(key: String?, defValue: String?): String? {
            val v = base.getString(key, null) ?: return defValue
            if (v.isEmpty()) return v
            val ok = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(v) }.isSuccess
            return if (ok) v else kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), v)
        }
    }

    fun getFolderName(folder: String, path: String): String {
        return "${folder}/${path}"
    }

    fun <T> Context.setKey(path: String, value: T) {
        CloudStreamApp.setKey(path, value)
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        return CloudStreamApp.getKey<T>(path) ?: defVal
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? {
        return getKey(path, null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? {
        return getKey(getFolderName(folder, path), null)
    }
    
    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        return null
    }
}
