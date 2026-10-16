package com.easytranslate.chichu.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.easytranslate.chichu.asr.SenseVoiceRecognizer
import com.easytranslate.chichu.mt.MarianTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻译 · 模型仓库：管理模型导入（文件选择器）/ 网络下载、引擎加载与翻译/识别入口。
 *
 * 设计：
 * - APK 不内置模型：模型经应用内文件选择器导入（files/models/<dirName>/），或联网多线程下载
 * - 引擎懒加载（首次使用时才载入 ONNX，省内存与电量）
 * - 语种自动检测：识别结果含中文 → 走中译英；否则英译中
 * - 省电模式：beam 宽度 6→4、推理线程 2→1
 */
class ModelRepository(
    private val context: Context,
    private val modelsDir: File,
    private val downloader: ModelDownloader,
) {
    companion object {
        private const val TAG = "ModelRepository"
        private const val PROGRESS_INTERVAL_MS = 250L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("chichu_settings", Context.MODE_PRIVATE)

    // ---------- 设置 ----------

    var powerSaveMode: Boolean
        get() = prefs.getBoolean("power_save", false)
        set(v) = prefs.edit().putBoolean("power_save", v).apply()

    /** 目标语言："en"（中→英）或 "zh"（英→中），默认 en */
    var targetLang: String
        get() = prefs.getString("target_lang", "en") ?: "en"
        set(v) = prefs.edit().putString("target_lang", v).apply()

    /** 下载/安装线程数：默认双线程，设置页可调（1-8） */
    var threads: Int
        get() = prefs.getInt("threads", ModelDownloader.DEFAULT_THREADS)
            .coerceIn(1, ModelDownloader.MAX_THREADS)
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, ModelDownloader.MAX_THREADS)).apply()

    // ---------- 模型安装状态 ----------

    data class ModelStatus(
        val senseVoice: Boolean = false,
        val enToZh: Boolean = false,
        val zhToEn: Boolean = false,
    ) {
        val asrReady: Boolean get() = senseVoice
        fun mtReady(target: String): Boolean =
            if (target == "en") zhToEn else enToZh
        val allReady: Boolean get() = senseVoice && enToZh && zhToEn
    }

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /** 判断某个模型包是否已完整安装（所有文件存在且大小一致） */
    fun isInstalled(pack: ModelCatalog.ModelPack): Boolean =
        pack.files.all { f ->
            val file = File(modelsDir, "${pack.dirName}/${f.fileName}")
            file.isFile && file.length() == f.sizeBytes
        }

    private fun readStatus() = ModelStatus(
        senseVoice = isInstalled(ModelCatalog.senseVoice),
        enToZh = isInstalled(ModelCatalog.enToZh),
        zhToEn = isInstalled(ModelCatalog.zhToEn),
    )

    fun refreshStatus() { _status.value = readStatus() }

    /** 是否有任一模型未安装 */
    val needsInstall: Boolean get() = !readStatus().allReady

    /** 网络下载一个模型包（多线程 + 断点续传） */
    fun downloadPack(pack: ModelCatalog.ModelPack): Flow<ModelDownloader.Progress> =
        downloader.downloadPack(pack, threads)

    /** 安装进度统一转发（供 UI 层消费） */
    suspend fun collectProgress(flow: Flow<Any>, onProgress: (String, Float) -> Unit) {
        flow.collect { p ->
            when (p) {
                is ModelDownloader.Progress.Downloading ->
                    onProgress(p.fileName, if (p.totalBytes > 0) p.downloadedBytes.toFloat() / p.totalBytes else 0f)
                is ModelDownloader.Progress.Done -> onProgress("", 1f)
            }
            if (p is ModelDownloader.Progress.Done) refreshStatus()
        }
    }

    // ---------- 模型导入（应用内文件选择器） ----------

    sealed interface ImportProgress {
        data class Copying(val fileName: String, val copiedBytes: Long, val totalBytes: Long) : ImportProgress
        data class Done(val pack: ModelCatalog.ModelPack) : ImportProgress
    }

    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 从外部文件导入一个模型文件到 files/models/<pack.dirName>/。
     * 先写 .part 临时文件，大小校验通过后原子改名，避免半成品被当成已安装。
     */
    fun importFile(pack: ModelCatalog.ModelPack, file: ModelCatalog.ModelFile, source: File): Flow<ImportProgress> =
        channelFlow {
            val destDir = File(modelsDir, pack.dirName)
            destDir.mkdirs()
            val dest = File(destDir, file.fileName)
            val tmp = File(destDir, file.fileName + ".part")

            if (!source.isFile) throw ImportException("文件不存在：${source.name}")
            if (source.length() != file.sizeBytes) {
                throw ImportException("文件大小不符：${source.name}（需要 ${file.sizeBytes} 字节）")
            }
            if (dest.isFile && dest.length() == file.sizeBytes) {
                send(ImportProgress.Done(pack))
                return@channelFlow
            }
            if (tmp.exists()) tmp.delete()

            val copied = AtomicLong(0)
            val total = source.length()
            val progressJob = launch {
                var last = -1L
                while (isActive) {
                    kotlinx.coroutines.delay(PROGRESS_INTERVAL_MS)
                    val cur = copied.get()
                    if (cur != last) {
                        last = cur
                        send(ImportProgress.Copying(file.fileName, cur, total))
                    }
                }
            }
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(512 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied.addAndGet(read.toLong())
                        }
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                progressJob.cancel()
                tmp.delete()
                Log.w(TAG, "import failed: ${file.fileName}", e)
                throw ImportException("导入失败：${e.message ?: e.javaClass.simpleName}", e)
            }
            progressJob.cancel()

            if (tmp.length() != file.sizeBytes || !tmp.renameTo(dest)) {
                tmp.delete()
                throw ImportException("导入校验失败：${file.fileName}")
            }
            refreshStatus()
            send(ImportProgress.Copying(file.fileName, total, total))
            send(ImportProgress.Done(pack))
        }.flowOn(Dispatchers.IO)

    // ---------- 引擎（懒加载） ----------

    @Volatile private var recognizer: SenseVoiceRecognizer? = null
    private val translators = HashMap<String, MarianTranslator>()

    private fun ensureRecognizer(): SenseVoiceRecognizer =
        recognizer ?: synchronized(this) {
            recognizer ?: SenseVoiceRecognizer.load(
                File(modelsDir, ModelCatalog.DIR_SENSEVOICE),
                numThreads = if (powerSaveMode) 1 else 2,
            ).also { recognizer = it }
        }

    private fun ensureTranslator(dirName: String): MarianTranslator =
        synchronized(translators) {
            translators.getOrPut(dirName) {
                MarianTranslator.load(File(modelsDir, dirName), numThreads = if (powerSaveMode) 1 else 2)
            }
        }

    /** 语音转文字（16kHz 单声道 [-1,1] 波形） */
    suspend fun recognize(waveform: FloatArray): String = withContext(Dispatchers.Default) {
        ensureRecognizer().recognize(
            waveform,
            language = SenseVoiceRecognizer.LANG_AUTO,
            textNorm = SenseVoiceRecognizer.TEXT_NORM_WITH_ITN,
        )
    }

    /**
     * 翻译。自动检测源语言（含中文 → 中译英，否则英译中）。
     * @param text 源文本
     * @param target "en" 或 "zh"
     */
    suspend fun translate(text: String, target: String = targetLang): String =
        withContext(Dispatchers.Default) {
            val isChinese = containsChinese(text)
            val dirName = if (isChinese) ModelCatalog.DIR_ZH_EN else ModelCatalog.DIR_EN_ZH
            val pack = ModelCatalog.all.first { it.dirName == dirName }
            if (!isInstalled(pack)) {
                throw IllegalStateException("${pack.displayName}模型未导入")
            }
            val beams = if (powerSaveMode) 4 else 6
            val raw = ensureTranslator(dirName).translate(text, beams = beams)
            if (dirName == ModelCatalog.DIR_EN_ZH) postProcessZh(raw) else raw
        }

    /** opus-mt-en-zh 输出繁体，转简体；顺手清理 Marian 偶发的英文尾巴 */
    private fun postProcessZh(text: String): String {
        var out = TraditionalSimplified.convert(text)
        // Marian 偶发在中文后混入英文句子（如 "… I want a cup of coffee."）→ 截断
        val idx = out.indexOf(" I ")
        if (idx > 0) out = out.substring(0, idx)
        return out.trim()
    }

    private fun containsChinese(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF }

    /** 释放全部引擎（设置页"清理内存"用） */
    fun releaseEngines() {
        synchronized(this) {
            recognizer?.close(); recognizer = null
            synchronized(translators) {
                translators.values.forEach { runCatching { it.close() } }
                translators.clear()
            }
        }
    }
}
