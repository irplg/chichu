package com.easytranslate.chichu.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easytranslate.chichu.asr.AudioRecorder
import com.easytranslate.chichu.data.HistoryStore
import com.easytranslate.chichu.data.ModelCatalog
import com.easytranslate.chichu.data.ModelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 轻译 · 主 ViewModel：翻译/识别流程、模型导入/下载状态、历史。
 */
class QingYiViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "QingYiVM" }

    val repository: ModelRepository = (app as com.easytranslate.chichu.QingYiApplication).modelRepository
    val history = HistoryStore(app)
    private val recorder = AudioRecorder(app)

    data class UiState(
        val input: String = "",
        val result: String = "",
        val translating: Boolean = false,
        val recognizing: Boolean = false,
        val volume: Float = 0f,
        val message: String? = null,          // 一次性提示（错误/状态）
        // 模型导入/下载（统一走同一状态位）
        val packId: String? = null,           // 正在导入/下载的模型 id
        val packProgress: Float = 0f,         // 0..1
        val packLabel: String = "",
    ) {
        val busy: Boolean get() = packId != null
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val modelStatus = repository.status

    private var recordJob: Job? = null

    init {
        repository.refreshStatus()
    }

    // ---------- 输入 ----------

    fun onInputChange(text: String) { _ui.value = _ui.value.copy(input = text, result = "") }

    // ---------- 翻译 ----------

    fun translate() {
        val text = _ui.value.input.trim()
        if (text.isEmpty()) return
        val target = repository.targetLang
        viewModelScope.launch {
            _ui.value = _ui.value.copy(translating = true, message = null)
            try {
                val out = repository.translate(text, target)
                _ui.value = _ui.value.copy(translating = false, result = out)
                history.add(HistoryStore.Entry(text, out, dirLabel(target), System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.w(TAG, "translate failed", e)
                _ui.value = _ui.value.copy(
                    translating = false,
                    message = e.message ?: "翻译失败",
                )
            }
        }
    }

    private fun dirLabel(target: String) = if (target == "en") "中 → 英" else "英 → 中"

    // ---------- 语音识别 ----------

    fun startVoiceInput() {
        if (_ui.value.recognizing) return
        recordJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(recognizing = true, volume = 0f, message = null)
            try {
                val waveform = recorder.record { v ->
                    _ui.value = _ui.value.copy(volume = v)
                }
                _ui.value = _ui.value.copy(recognizing = false, volume = 0f)
                if (waveform.isEmpty()) {
                    _ui.value = _ui.value.copy(message = "未录到音频")
                    return@launch
                }
                val text = repository.recognize(waveform)
                if (text.isBlank()) {
                    _ui.value = _ui.value.copy(message = "未识别到内容")
                } else {
                    _ui.value = _ui.value.copy(input = text, result = "")
                }
            } catch (e: Exception) {
                Log.w(TAG, "recognize failed", e)
                _ui.value = _ui.value.copy(
                    recognizing = false, volume = 0f,
                    message = e.message ?: "识别失败",
                )
            }
        }
    }

    fun stopVoiceInput() { recorder.stop() }

    // ---------- 模型导入 / 下载 ----------

    /**
     * 文件选择器回调：把用户选中的文件导入对应模型包。
     * 目标包的判定：父目录名优先，其次按文件名匹配（source.spm 等同名文件取首个缺失的包）。
     */
    fun importModel(source: File) {
        if (_ui.value.busy) return
        val match = matchPack(source) ?: run {
            _ui.value = _ui.value.copy(message = "无法识别该文件对应的模型")
            return
        }
        val (pack, file) = match
        viewModelScope.launch {
            _ui.value = _ui.value.copy(packId = pack.id, packProgress = 0f, packLabel = "正在导入 ${pack.displayName}", message = null)
            try {
                repository.collectProgress(repository.importFile(pack, file, source)) { _, frac ->
                    _ui.value = _ui.value.copy(packProgress = frac)
                }
                _ui.value = _ui.value.copy(packId = null, message = "${pack.displayName}模型已就绪")
            } catch (e: Exception) {
                Log.w(TAG, "import failed", e)
                _ui.value = _ui.value.copy(packId = null, message = e.message ?: "导入失败")
            }
        }
    }

    /** 判定导入文件属于哪个模型包的哪个文件 */
    private fun matchPack(source: File): Pair<ModelCatalog.ModelPack, ModelCatalog.ModelFile>? {
        val parentName = source.parentFile?.name?.lowercase()
        // 1) 父目录名与模型目录名一致 → 精确命中
        if (parentName != null) {
            val byDir = ModelCatalog.all.firstOrNull { it.dirName == parentName }
            if (byDir != null) {
                val f = byDir.files.firstOrNull { it.fileName.equals(source.name, ignoreCase = true) }
                if (f != null) return byDir to f
            }
        }
        // 2) 按文件名匹配：唯一命中直接用；同名文件（如 source.spm）取首个缺失该文件的包
        val candidates = ModelCatalog.all.mapNotNull { pack ->
            pack.files.firstOrNull { it.fileName.equals(source.name, ignoreCase = true) }?.let { pack to it }
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        return candidates.firstOrNull { (pack, _) -> !repository.isInstalled(pack) } ?: candidates[0]
    }

    /** 联网下载全部未就绪的模型（多线程，设置页用） */
    fun downloadMissingModels() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            for (pack in ModelCatalog.all) {
                if (repository.isInstalled(pack)) continue
                _ui.value = _ui.value.copy(packId = pack.id, packProgress = 0f, packLabel = "正在下载 ${pack.displayName}", message = null)
                try {
                    repository.collectProgress(repository.downloadPack(pack)) { _, frac ->
                        _ui.value = _ui.value.copy(packProgress = frac)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "download failed", e)
                    _ui.value = _ui.value.copy(packId = null, message = "下载失败：${e.message}")
                    return@launch
                }
            }
            _ui.value = _ui.value.copy(packId = null, message = "模型已就绪")
        }
    }

    /** 联网下载单个模型包（语言页用） */
    fun downloadModel(pack: ModelCatalog.ModelPack) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(packId = pack.id, packProgress = 0f, packLabel = "正在下载 ${pack.displayName}", message = null)
            try {
                repository.collectProgress(repository.downloadPack(pack)) { _, frac ->
                    _ui.value = _ui.value.copy(packProgress = frac)
                }
                _ui.value = _ui.value.copy(packId = null, message = "${pack.displayName}模型已就绪")
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                _ui.value = _ui.value.copy(packId = null, message = "下载失败：${e.message}")
            }
        }
    }

    // ---------- 设置 ----------

    fun setPowerSave(on: Boolean) { repository.powerSaveMode = on }
    fun setTargetLang(lang: String) { repository.targetLang = lang }
    fun setThreads(n: Int) { repository.threads = n }
    fun releaseEngines() { repository.releaseEngines() }

    /** Wear OS 无应用内授权页：提示 adb 授权命令（详见《模型下载指南》） */
    fun showAdbHint() {
        _ui.value = _ui.value.copy(
            message = "请用电脑执行一次：adb shell appops set com.easytranslate.chichu MANAGE_EXTERNAL_STORAGE allow",
        )
    }

    fun consumeMessage() { _ui.value = _ui.value.copy(message = null) }
}
