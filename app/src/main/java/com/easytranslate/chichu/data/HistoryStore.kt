package com.easytranslate.chichu.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 轻译 · 翻译历史（本地 JSON 文件，上限 200 条，无需数据库）
 */
class HistoryStore(private val context: Context) {

    @Serializable
    data class Entry(
        val source: String,
        val translated: String,
        val direction: String,   // "zh→en" / "en→zh"
        val timestamp: Long,
    )

    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun load(): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<List<Entry>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun add(entry: Entry) {
        val list = load().toMutableList()
        list.add(0, entry)
        val trimmed = list.take(200)
        runCatching { file.writeText(json.encodeToString(trimmed)) }
    }

    fun remove(timestamp: Long) {
        val list = load().filterNot { it.timestamp == timestamp }
        runCatching { file.writeText(json.encodeToString(list)) }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
