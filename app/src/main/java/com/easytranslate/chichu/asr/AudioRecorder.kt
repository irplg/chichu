package com.easytranslate.chichu.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 轻译 · 录音器（16kHz 单声道 PCM16）
 *
 * 手表场景：最长录 30 秒（SenseVoice 前端按 30s 设计），
 * 实时返回音量 RMS（驱动 UI 波形动画），录音结束返回 [-1,1] 波形。
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 30
        const val MAX_SAMPLES = SAMPLE_RATE * MAX_SECONDS
    }

    private var recorder: AudioRecord? = null
    @Volatile private var recording = false

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 开始录音并收集波形。
     * @param onVolume 实时音量 RMS（0..1 量级），每 ~100ms 回调一次；用于波形动画
     * @param onAutoStop 达到最长时长或用户停止时调用
     * @return 完整波形 [-1,1]
     */
    suspend fun record(
        onVolume: (Float) -> Unit,
    ): FloatArray = withContext(Dispatchers.IO) {
        check(hasPermission) { "RECORD_AUDIO permission not granted" }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf, SAMPLE_RATE / 10 * 2)  // ≥100ms
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        recorder = rec
        val out = ArrayList<Float>(SAMPLE_RATE * 5)
        val buf = ShortArray(SAMPLE_RATE / 10)  // 100ms
        var sumSq = 0.0
        var count = 0
        recording = true
        rec.startRecording()
        try {
            while (recording && out.size < MAX_SAMPLES) {
                val read = rec.read(buf, 0, buf.size)
                if (read <= 0) break
                for (i in 0 until read) {
                    val v = buf[i] / 32768.0f
                    out.add(v)
                    sumSq += (v * v).toDouble()
                }
                count += read
                if (count >= SAMPLE_RATE / 10) {
                    onVolume(sqrt(sumSq / count).toFloat().coerceIn(0f, 1f))
                    sumSq = 0.0; count = 0
                }
            }
        } finally {
            recording = false
            runCatching { rec.stop() }
            rec.release()
            recorder = null
        }
        out.toFloatArray()
    }

    fun stop() { recording = false }
}
