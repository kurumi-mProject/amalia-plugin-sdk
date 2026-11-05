package com.example.amalia.music

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** Ресурсы принадлежат пакету плагина, а не таблице Android R хоста. */
class PlayerStrings(private val context: Context) {
    private var language = ""
    private var values = JSONObject()

    init { reload() }

    @Synchronized
    private fun reload() {
        val requested = context.resources.configuration.locales[0]?.language ?: "en"
        if (language == requested) return
        val packageFile = File(context.filesDir, "plugins/com.example.amalia.music/plugin.amaliaplug")
        try {
            values = ZipFile(packageFile).use { zip ->
                fun read(tag: String): JSONObject? = zip.getEntry("assets/strings/$tag.json")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                }
                val base = read("en") ?: error("Missing plugin strings: en")
                read(requested)?.let { localized ->
                    localized.keys().forEach { key -> base.put(key, localized.getString(key)) }
                }
                base
            }
            language = requested
        } catch (error: Exception) {
            Log.e("MusicPlayer", "Cannot read plugin strings", error)
        }
    }

    @Synchronized
    operator fun get(key: String): String {
        reload()
        return values.optString(key, key)
    }

    fun format(key: String, vararg args: Any): String =
        String.format(Locale.forLanguageTag(language.ifBlank { "en" }), get(key), *args)
}
