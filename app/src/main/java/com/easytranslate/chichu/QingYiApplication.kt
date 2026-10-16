package com.easytranslate.chichu

import android.app.Application
import com.easytranslate.chichu.asr.SenseVoiceFrontend
import com.easytranslate.chichu.data.ModelDownloader
import com.easytranslate.chichu.data.ModelRepository
import com.easytranslate.chichu.data.TraditionalSimplified
import java.io.File

/**
 * 轻译 · Application
 * 持有模型目录与下载器（进程内单例），避免重复初始化 ONNX Runtime。
 * 同时初始化 assets 前端（mel 组 / CMVN / 繁简表）。
 * 模型本体不随 APK 内置：由设置页导入（文件选择器）或联网下载。
 */
class QingYiApplication : Application() {

    lateinit var modelRepository: ModelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        SenseVoiceFrontend.init(assets)
        TraditionalSimplified.load(this)
        val modelsDir = File(filesDir, "models").also { it.mkdirs() }
        modelRepository = ModelRepository(
            context = this,
            modelsDir = modelsDir,
            downloader = ModelDownloader(modelsDir),
        )
    }
}
