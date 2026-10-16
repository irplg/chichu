package com.easytranslate.chichu.data

import android.content.Context

/**
 * 轻译 · 繁简转换（OpenCC TSCharacters 单字映射子集，3553 条）
 * opus-mt-en-zh 输出繁体，转简体后展示。
 */
object TraditionalSimplified {

    @Volatile private var map: Map<Char, Char>? = null

    fun load(context: Context) {
        if (map != null) return
        synchronized(this) {
            if (map != null) return
            val m = HashMap<Char, Char>(4096)
            context.assets.open("sensevoice/t2s.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.length >= 3) {
                        m[line[0]] = line[2]
                    }
                }
            }
            map = m
        }
    }

    /** 繁 → 简（逐字查表，未命中保持原字） */
    fun convert(text: String): String {
        val m = map ?: return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(m[c] ?: c)
        return sb.toString()
    }
}
