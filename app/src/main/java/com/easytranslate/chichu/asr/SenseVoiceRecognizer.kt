package com.easytranslate.chichu.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.util.Log
import com.easytranslate.chichu.data.TraditionalSimplified
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * 轻译 · SenseVoiceSmall 语音识别引擎（int8 ONNX，非自回归 CTC）
 *
 * 已验证事实（Python 基准与 ONNX 端到端跑通）：
 * 1. 输入：x [1,T,560]（LFR+CMVN 后的特征）、x_length [1] int32、
 *    language [1] int32（0=auto）、text_norm [1] int32（14=withitn）
 * 2. 输出：logits [1,T,25055]（CTC）
 * 3. CTC 解码：argmax → 去相邻重复 → 去 blank(0)；
 *    **只保留 token id ∈ [3, 24884)**——≥24884 为语言/情感/事件/ITN 特殊 token，必须过滤
 * 4. token 文本中 '▁'(U+2581) 表示空格
 * 5. 模型选型：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17
 *    （阿里原始导出；2025-09-09 为粤语微调版，英文大小写与拼写质量下降，已弃用）
 * 6. language/text_norm 是 embed 查询 id，不是 token id
 */
class SenseVoiceRecognizer private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokens: Array<String>,
) {
    companion object {
        private const val TAG = "SenseVoiceRecognizer"
        private const val VOCAB_SIZE = 25055
        private const val TEXT_TOKEN_MAX = 24884   // [3, 24884) 为文本 token

        /** language embed 查询 id */
        const val LANG_AUTO = 0
        const val LANG_ZH = 3
        const val LANG_EN = 4
        const val LANG_YUE = 7
        const val LANG_JA = 11
        const val LANG_KO = 12

        /** text_norm embed 查询 id */
        const val TEXT_NORM_WITH_ITN = 14
        const val TEXT_NORM_WITHOUT_ITN = 15

        fun load(dir: File, numThreads: Int = 2): SenseVoiceRecognizer {
            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(
                File(dir, "model.int8.onnx").absolutePath, opts
            )
            val tokens = Array(VOCAB_SIZE) { "" }
            File(dir, "tokens.txt").forEachLine { line ->
                val idx = line.lastIndexOf(' ')
                if (idx > 0) {
                    val id = line.substring(idx + 1).trim().toIntOrNull()
                    if (id != null && id in 0 until VOCAB_SIZE) {
                        tokens[id] = line.substring(0, idx)
                    }
                }
            }
            Log.i(TAG, "loaded from ${dir.name}: tokens=${tokens.size}")
            return SenseVoiceRecognizer(env, session, tokens)
        }
    }

    /**
     * 识别一段完整音频（16kHz 单声道 [-1,1]）。
     * @param waveform 采样点
     * @param language 语种查询 id（默认 auto 自动检测）
     * @return 识别文本（已过滤特殊 token、繁简已转换）
     */
    fun recognize(
        waveform: FloatArray,
        language: Int = LANG_AUTO,
        textNorm: Int = TEXT_NORM_WITH_ITN,
    ): String {
        val feats = SenseVoiceFrontend.extract(waveform)  // [T, 560]
        if (feats.isEmpty()) return ""
        val t = feats.size
        val flat = FloatArray(t * 560)
        for (i in 0 until t) System.arraycopy(feats[i], 0, flat, i * 560, 560)

        val argmax = runModel(flat, t, language, textNorm)
        // CTC：去相邻重复 + 去 blank + 过滤特殊 token
        val sb = StringBuilder()
        var prev = -1
        for (v in argmax) {
            if (v != prev && v >= 3 && v < TEXT_TOKEN_MAX) sb.append(tokens[v])
            prev = v
        }
        return TraditionalSimplified.convert(
            sb.toString().replace('\u2581', ' ').trim()
        )
    }

    /** 跑 ONNX：x [1, T, 560] → argmax 序列 [T] */
    private fun runModel(feats: FloatArray, t: Int, language: Int, textNorm: Int): IntArray {
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(feats), longArrayOf(1, t.toLong(), 560)
        ).use { xT ->
            OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(t)), longArrayOf(1)
            ).use { lenT ->
                OnnxTensor.createTensor(
                    env, IntBuffer.wrap(intArrayOf(language)), longArrayOf(1)
                ).use { langT ->
                    OnnxTensor.createTensor(
                        env, IntBuffer.wrap(intArrayOf(textNorm)), longArrayOf(1)
                    ).use { normT ->
                        val feeds = mapOf(
                            "x" to xT, "x_length" to lenT,
                            "language" to langT, "text_norm" to normT,
                        )
                        session.run(feeds).use { res ->
                            val logitsTensor = res[0] as OnnxTensor
                            val buf = logitsTensor.floatBuffer
                            val vocab = logitsTensor.info.shape[2].toInt()
                            val out = IntArray(t)
                            for (frame in 0 until t) {
                                val base = frame * vocab
                                var best = 0
                                var bestVal = Float.NEGATIVE_INFINITY
                                for (v in 0 until vocab) {
                                    val value = buf.get(base + v)
                                    if (value > bestVal) {
                                        bestVal = value
                                        best = v
                                    }
                                }
                                out[frame] = best
                            }
                            return out
                        }
                    }
                }
            }
        }
    }

    fun close() {
        runCatching { session.close() }
    }
}
