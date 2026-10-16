package com.easytranslate.chichu.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtLoggingLevel
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * 轻译 · Marian 翻译引擎（Helsinki-NLP/opus-mt，int8 ONNX）
 *
 * 已验证的关键实现细节（与 HF transformers 逐 token 对齐）：
 * 1. 源序列编码：SentencePiece Viterbi 切分，映射到 vocab.json（65001 共享词表）的 id，
 *    **尾部必须追加 </s>(id 0)**——缺失会导致输出退化重复（HF tokenizer 的 add_eos_token 行为）
 * 2. 编码前先做 NFKC 规范化（SentencePiece normalizer，全角→半角）
 * 3. Beam search：宽度 6（官方 generation_config），length_penalty 1.0，max_len 48，
 *    禁止 <pad>(65000)，EOS=0，decoder_start=65000
 * 4. 每 beam 独立 KV cache；step 0 全序列 use_cache_branch=false，
 *    step>0 仅末 token use_cache_branch=true 且复用 encoder present
 * 5. 英文→中文输出为繁体，需繁简转换（由上层处理）
 */
class MarianTranslator private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val spm: SentencePieceModel,
    private val vocab: Map<String, Int>,     // piece -> id（vocab.json）
    private val idToPiece: Map<Int, String>,
    private val decoderOutputNames: List<String>,
) {
    companion object {
        private const val TAG = "MarianTranslator"
        const val BOS = 65000L
        const val EOS = 0L
        const val PAD = 65000L
        private const val DEFAULT_BEAMS = 6
        private const val DEFAULT_MAX_LEN = 48
        private const val DEFAULT_LENGTH_PENALTY = 1.0

        /** 从模型目录加载（files/models/<dirName>/） */
        fun load(dir: File, numThreads: Int = 2): MarianTranslator {
            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val encoder = env.createSession(
                File(dir, "encoder_model.onnx").absolutePath, opts
            )
            val decoder = env.createSession(
                File(dir, "decoder_model_merged.onnx").absolutePath, opts
            )
            val spm = SentencePieceModel(File(dir, "source.spm"))
            val vocabJson = File(dir, "vocab.json").readText()
            val vocabMap = parseVocabJson(vocabJson)
            val idToPiece = HashMap<Int, String>(vocabMap.size * 2)
            for ((k, v) in vocabMap) idToPiece[v] = k
            val outNames = decoder.outputNames.toList().toList()
            Log.i(TAG, "loaded from ${dir.name}: vocab=${vocabMap.size}, decoderOuts=${outNames.size}")
            return MarianTranslator(env, encoder, decoder, spm, vocabMap, idToPiece, outNames)
        }

        /** 极简 JSON 对象解析：{"piece": id, ...}（词表无嵌套结构，避免引完整 JSON 库热路径） */
        private fun parseVocabJson(json: String): Map<String, Int> {
            val map = HashMap<String, Int>(70_000)
            var i = 1
            val n = json.length
            fun skipWs() { while (i < n && (json[i].isWhitespace() || json[i] == ',')) i++ }
            while (i < n - 1) {
                skipWs()
                if (i >= n || json[i] != '"') break
                i++
                val sb = StringBuilder()
                while (i < n && json[i] != '"') {
                    if (json[i] == '\\' && i + 1 < n) {
                        when (val c = json[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (i + 5 < n) {
                                    sb.append(json.substring(i + 2, i + 6).toInt(16).toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(c)
                        }
                        i += 2
                    } else {
                        sb.append(json[i]); i++
                    }
                }
                i++ // 跳过收尾引号
                skipWs()
                if (i < n && json[i] == ':') i++
                skipWs()
                val numStart = i
                while (i < n && (json[i].isDigit() || json[i] == '-')) i++
                if (numStart < i) {
                    map[sb.toString()] = json.substring(numStart, i).toInt()
                }
            }
            return map
        }
    }

    /** 空 past 缓存（step 0 使用）：形状由 decoder 输入 schema 推导 */
    private val emptyPast: Map<String, OnnxTensor> by lazy {
        val map = LinkedHashMap<String, OnnxTensor>()
        for (name in decoder.inputNames) {
            if (name.startsWith("past_")) {
                // Marian: [batch=1, heads=8, past_len=0, head_dim=64]
                map[name] = OnnxTensor.createTensor(
                    env, FloatBuffer.allocate(0), longArrayOf(1, 8, 0, 64)
                )
            }
        }
        map
    }

    /**
     * SentencePiece Viterbi 编码（对齐 HF MarianTokenizer）：
     * NFKC 规范化 → ▁ 处理 → 在 vocab.json 词表上做最大分数切分 → 追加尾部 </s>
     */
    fun encode(text: String): LongArray {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.trim().replace(' ', '\u2581')
        t = "\u2581$t"
        val n = t.length
        if (n == 0) return longArrayOf(0)
        val NEG = -1e30
        val best = DoubleArray(n + 1) { NEG }
        val back = IntArray(n + 1)
        best[0] = 0.0
        val maxLen = minOf(spm.maxPieceLen, n)
        for (i in 1..n) {
            val from = maxOf(0, i - maxLen)
            for (j in from until i) {
                if (best[j] == NEG) continue
                val sub = t.substring(j, i)
                val id = vocab[sub] ?: continue
                val score = spm.pieceToScore[sub]?.toDouble() ?: 0.0
                if (best[j] + score > best[i]) {
                    best[i] = best[j] + score
                    back[i] = j
                }
            }
        }
        if (best[n] == NEG) return longArrayOf(1, 0) // <unk>, </s>
        val ids = ArrayList<Long>()
        var i = n
        while (i > 0) {
            val j = back[i]
            ids.add((vocab[t.substring(j, i)] ?: 1).toLong())
            i = j
        }
        ids.reverse()
        ids.add(EOS) // 尾部 </s>：关键，不可省略
        return ids.toLongArray()
    }

    fun decode(ids: LongArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == BOS || id == PAD || id == EOS) continue
            val piece = idToPiece[id.toInt()] ?: continue
            sb.append(piece)
        }
        return sb.toString().replace('\u2581', ' ').trim()
    }

    /**
     * 翻译。线程安全（每次调用独立状态）。
     * @param text 源文本
     * @param beams beam 宽度（省电模式可降至 4）
     * @param lengthPenalty 长度惩罚（官方默认 1.0）
     * @param noRepeatNgram 禁止重复的 n-gram 大小（0=关闭；实测 4 可消除 Marian 尾部退化重复）
     */
    fun translate(
        text: String,
        beams: Int = DEFAULT_BEAMS,
        lengthPenalty: Double = DEFAULT_LENGTH_PENALTY,
        noRepeatNgram: Int = 4,
    ): String {
        val srcIds = encode(text)
        val encOut = runEncoder(srcIds)

        // beam: (累计 logprob, token 序列, 该 beam 的 past 缓存)
        var live = ArrayList<Beam>()
        val done = ArrayList<Beam>()

        val first = decoderStep(BOS, encOut, emptyPast, srcIds.size)
        val lp0 = logSoftmax(first.logits)
        lp0[PAD.toInt()] = -1e30
        val top = topK(lp0, beams)
        for (t in top) live.add(Beam(lp0[t], longArrayOf(t.toLong()), first.past))

        for (step in 0 until DEFAULT_MAX_LEN) {
            val candidates = ArrayList<Beam>()
            for (b in live) {
                val stepRes = decoderStep(b.tokens.last(), encOut, b.past, srcIds.size)
                val lp = logSoftmax(stepRes.logits)
                lp[PAD.toInt()] = -1e30
                // 抑制历史出现过的 n-gram 续接 token（消除 Marian 退化重复）
                if (noRepeatNgram > 0 && b.tokens.size >= noRepeatNgram - 1) {
                    val n1 = noRepeatNgram - 1
                    val lastGram = LongArray(n1) { b.tokens[b.tokens.size - n1 + it] }
                    for (i in 0..b.tokens.size - noRepeatNgram) {
                        var match = true
                        for (k in 0 until n1) {
                            if (b.tokens[i + k] != lastGram[k]) { match = false; break }
                        }
                        if (match) {
                            val banned = b.tokens[i + n1].toInt()
                            if (banned in lp.indices) lp[banned] = -1e30
                        }
                    }
                }
                val k = 2 * beams
                for (t in topK(lp, k)) {
                    if (t.toLong() == EOS) {
                        done.add(Beam(b.score + lp[t], b.tokens, stepRes.past))
                    } else {
                        val seq = LongArray(b.tokens.size + 1)
                        System.arraycopy(b.tokens, 0, seq, 0, b.tokens.size)
                        seq[b.tokens.size] = t.toLong()
                        candidates.add(Beam(b.score + lp[t], seq, stepRes.past))
                    }
                }
            }
            if (candidates.isEmpty()) break
            candidates.sortByDescending { it.score }
            val seen = HashSet<List<Long>>()
            val next = ArrayList<Beam>()
            for (c in candidates) {
                if (seen.add(c.tokens.toList())) {
                    next.add(c)
                    if (next.size >= beams) break
                }
            }
            live = next
        }

        val pool = if (done.isNotEmpty()) done else live
        val bestBeam = pool.maxByOrNull { b ->
            b.score / Math.pow(maxOf(1, b.tokens.size).toDouble(), lengthPenalty)
        }!!
        return decode(bestBeam.tokens)
    }

    private class Beam(val score: Double, val tokens: LongArray, val past: Map<String, OnnxTensor>)

    private class StepResult(val logits: FloatArray, val past: Map<String, OnnxTensor>)

    private fun runEncoder(srcIds: LongArray): OnnxTensor {
        val len = srcIds.size.toLong()
        val idsBuf = LongBuffer.wrap(srcIds)
        val mask = LongArray(srcIds.size) { 1L }
        OnnxTensor.createTensor(env, LongBuffer.wrap(srcIds), longArrayOf(1, len)).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, len)).use { maskT ->
                encoder.run(mapOf("input_ids" to idsT, "attention_mask" to maskT)).use { res ->
                    return res[0] as OnnxTensor
                }
            }
        }
    }

    /**
     * 单步解码。
     * @param lastToken 上一步生成的 token
     * @param useCache 是否为增量步（>0 步）
     */
    private fun decoderStep(
        lastToken: Long,
        encOut: OnnxTensor,
        past: Map<String, OnnxTensor>,
        srcLen: Int,
    ): StepResult {
        val useCache = past !== emptyPast
        val idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(lastToken)), longArrayOf(1, 1))
        val maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(srcLen) { 1L }), longArrayOf(1, srcLen.toLong()))
        // use_cache_branch 为 tensor(bool)：Java API 无 boolean[] 重载，用 ByteBuffer + BOOL
        val boolBuf = ByteBuffer.allocateDirect(1).apply {
            put(if (useCache) 1.toByte() else 0.toByte())
        }
        boolBuf.rewind()
        val cacheT = OnnxTensor.createTensor(env, boolBuf, longArrayOf(1), OnnxJavaType.BOOL)
        try {
            val feeds = HashMap<String, OnnxTensor>()
            feeds["input_ids"] = idsT
            feeds["encoder_hidden_states"] = encOut
            feeds["encoder_attention_mask"] = maskT
            feeds["use_cache_branch"] = cacheT
            feeds.putAll(past)

            val results = decoder.run(feeds)
            val logitsTensor = results[0] as OnnxTensor
            val logits = logitsTensor.floatBuffer
            val vocabSize = 65001
            val out = FloatArray(vocabSize)
            logits.get(out)

            // 新 past：继承 encoder 项，更新 decoder 项
            val newPast = LinkedHashMap<String, OnnxTensor>(past)
            for (i in 1 until results.size()) {
                val name = decoderOutputNames[i]
                if (useCache && name.contains("encoder")) continue
                val tensor = results[i] as OnnxTensor
                val pastName = name.replace("present.", "past_key_values.")
                newPast[pastName] = tensor
            }
            logitsTensor.close()
            return StepResult(out, newPast)
        } finally {
            runCatching { idsT.close() }
            runCatching { maskT.close() }
            runCatching { cacheT.close() }
        }
    }

    private fun logSoftmax(logits: FloatArray): DoubleArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        val out = DoubleArray(logits.size)
        for (i in logits.indices) {
            out[i] = Math.exp((logits[i] - max).toDouble())
            sum += out[i]
        }
        val logSum = Math.log(sum)
        for (i in out.indices) out[i] -= logSum
        return out
    }

    private fun topK(arr: DoubleArray, k: Int): IntArray {
        val idx = IntArray(arr.size) { it }
        // 部分选择排序取前 k（k 很小）
        val kk = minOf(k, arr.size)
        val result = IntArray(kk)
        val pool = idx.clone()
        for (i in 0 until kk) {
            var best = i
            for (j in i until pool.size) if (arr[pool[j]] > arr[pool[best]]) best = j
            val tmp = pool[i]; pool[i] = pool[best]; pool[best] = tmp
            result[i] = pool[i]
        }
        return result
    }

    fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }
}
