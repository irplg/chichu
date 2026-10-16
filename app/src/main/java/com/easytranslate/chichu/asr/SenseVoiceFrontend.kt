package com.easytranslate.chichu.asr

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * 轻译 · SenseVoice 前端（kaldi fbank + LFR + CMVN + 繁简转换）
 *
 * 数值与 torchaudio.compliance.kaldi.fbank（funasr WavFrontend 调用链）逐位对齐，
 * Python 基准四语言 maxdiff < 4e-4（float32 累加顺序差异，可忽略）。
 *
 * 流程：分帧(400/160, snip_edges) → 去直流 → 预加重 0.97(replicate 左填充) →
 *       hamming 窗 → 补零到 512 点 FFT → 功率谱 → 80 路三角 mel → log(max(·, eps))
 *       → LFR(m=7,n=6, 左 padding 3 帧首帧) → CMVN((x+mean)*var)
 *
 * mel 组与 CMVN 统计量随 APK assets 下发（sensevoice/mel_filters.bin、cmvn.bin），
 * 无需联网——仅 ONNX 模型本体走下载。
 */
object SenseVoiceFrontend {

    private const val WIN = 400          // 25ms @16kHz
    private const val SHIFT = 160        // 10ms
    private const val N_FFT = 512        // 补零后的 FFT 长度（400 → 512）
    private const val NUM_MEL = 80
    private const val PREEMPHASIS = 0.97f
    private const val EPS = 1.1920929e-7f   // finfo(float32).eps，torchaudio 的 floor
    private const val LFR_M = 7
    private const val LFR_N = 6

    private lateinit var assets: AssetManager

    /** mel 组 (80, 257)，最后一列为 0（torchaudio pad 行为） */
    private val melFilters: Array<FloatArray> by lazy {
        val raw = readAssetFloats("sensevoice/mel_filters.bin")
        require(raw.size == NUM_MEL * (N_FFT / 2 + 1)) { "mel_filters.bin 尺寸异常: ${raw.size}" }
        Array(NUM_MEL) { m -> FloatArray(N_FFT / 2 + 1) { raw[m * (N_FFT / 2 + 1) + it] } }
    }

    /** CMVN means(560)：am.mvn 的 AddShift（即 -mean） */
    private val cmvnMeans: FloatArray by lazy {
        readAssetFloats("sensevoice/cmvn.bin").copyOfRange(0, 560)
    }

    /** CMVN vars(560)：am.mvn 的 Rescale（即 1/std） */
    private val cmvnVars: FloatArray by lazy {
        readAssetFloats("sensevoice/cmvn.bin").copyOfRange(560, 1120)
    }

    /** hamming 窗（periodic=False）：0.54 - 0.46*cos(2πn/(N-1)) */
    private val window: FloatArray = FloatArray(WIN) {
        (0.54 - 0.46 * cos(2.0 * PI * it / (WIN - 1))).toFloat()
    }

    fun init(assetManager: AssetManager) {
        assets = assetManager
    }

    private fun readAssetFloats(path: String): FloatArray {
        val bytes = assets.open(path).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()) { buf.get() }
    }

    /**
     * 完整特征提取：16kHz 单声道 float [-1,1] → [T][560]（LFR + CMVN）。
     * T = ceil((1 + (n-400)/160) / 6)。
     */
    fun extract(waveform: FloatArray): Array<FloatArray> {
        val fbank = computeFbank(waveform)
        if (fbank.isEmpty()) return emptyArray()
        return applyLfrCmvn(fbank)
    }

    /** 梅尔频谱（未 LFR/CMVN）：[frames][80] */
    fun computeFbank(waveform: FloatArray): Array<FloatArray> {
        val n = waveform.size
        if (n < WIN) return emptyArray()
        val m = 1 + (n - WIN) / SHIFT
        val mel = melFilters
        val out = Array(m) { FloatArray(NUM_MEL) }
        val frame = FloatArray(N_FFT)
        val power = FloatArray(N_FFT / 2 + 1)
        for (f in 0 until m) {
            val base = f * SHIFT
            // 取帧 + 去直流
            var mean = 0f
            for (i in 0 until WIN) mean += waveform[base + i]
            mean /= WIN
            // 预加重 0.97（replicate 左填充：pre[0]=自身 → 首样本 *= (1-0.97)）+ 加窗
            val d0 = waveform[base] - mean
            var prev = d0
            frame[0] = d0 * (1f - PREEMPHASIS) * window[0]
            for (i in 1 until WIN) {
                val cur = waveform[base + i] - mean
                frame[i] = (cur - PREEMPHASIS * prev) * window[i]
                prev = cur
            }
            for (i in WIN until N_FFT) frame[i] = 0f
            powerSpectrum(frame, power)
            for (b in 0 until NUM_MEL) {
                val w = mel[b]
                var acc = 0f
                for (k in 0..N_FFT / 2) acc += power[k] * w[k]
                out[f][b] = ln(max(acc, EPS))
            }
        }
        return out
    }

    /**
     * LFR（m=7, n=6，左 padding 3 帧首帧）+ CMVN。
     * 输入 [T][80] → 输出 [ceil(T/6)][560]，逐元素 (x + mean) * var。
     */
    private fun applyLfrCmvn(fbank: Array<FloatArray>): Array<FloatArray> {
        val t = fbank.size
        val tLfr = (t + LFR_N - 1) / LFR_N
        val featDim = NUM_MEL
        val leftPad = (LFR_M - 1) / 2   // 3
        val means = cmvnMeans
        val vars = cmvnVars
        val out = Array(tLfr) { FloatArray(LFR_M * featDim) }
        for (i in 0 until tLfr) {
            val row = out[i]
            val src = i * LFR_N
            var w = 0
            for (k in 0 until LFR_M) {
                val fi = src + k - leftPad
                // 左 padding 复制首帧；右越界复制末帧（与 funasr LFR 的 padding 语义一致）
                val fr = when {
                    fi < 0 -> fbank[0]
                    fi >= t -> fbank[t - 1]
                    else -> fbank[fi]
                }
                for (d in 0 until featDim) {
                    row[w] = (fr[d] + means[w]) * vars[w]
                    w++
                }
            }
        }
        return out
    }

    /**
     * 512 点实数 FFT 功率谱：基-2 Cooley-Tukey 迭代实现（bit-reversal + butterfly）。
     * 输入实数帧 frame[512]，输出 power[257]（|X[k]|²，k=0..256）。
     */
    private fun powerSpectrum(frame: FloatArray, power: FloatArray) {
        val n = N_FFT
        val re = FloatArray(n) { frame[it] }
        val im = FloatArray(n)
        // bit-reversal 置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vRe = re[b] * curRe - im[b] * curIm
                    val vIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - vRe
                    im[b] = im[a] - vIm
                    re[a] += vRe
                    im[a] += vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        for (k in 0..n / 2) power[k] = re[k] * re[k] + im[k] * im[k]
    }
}
