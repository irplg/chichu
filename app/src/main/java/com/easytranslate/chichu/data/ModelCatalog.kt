package com.easytranslate.chichu.data

/**
 * 轻译 · 模型目录
 *
 * APK 不内置模型（控制包体），模型由用户在设置中导入（应用内文件选择器）
 * 或联网下载。下载源为 **GitHub Release**（模型仓库由本项目管理，链接永久有效），
 * 经多个国内镜像加速，GitHub 官方直连兜底。
 *
 * 模型全部为 int8 量化 ONNX，支持 armeabi-v7a（32 位）与 arm64-v8a（64 位）。
 *
 * 安装位置（应用私有空间 files/models/）：
 *   sensevoice/   语音识别（中英混说，自动语种，自带标点与 ITN）
 *   mt-en-zh/     英译中
 *   mt-zh-en/     中译英
 */
object ModelCatalog {

    const val DIR_SENSEVOICE = "sensevoice"
    const val DIR_EN_ZH = "mt-en-zh"
    const val DIR_ZH_EN = "mt-zh-en"

    /**
     * 模型发布地址（GitHub Release，仓库 irplg/chichu 由本项目维护，链接永久有效）。
     * 切换下载源只需改这一个常量（例如换成自建仓库的 Release）。
     */
    const val RELEASE_BASE =
        "https://github.com/irplg/chichu/releases/download/models-v1/"

    /**
     * 下载镜像（前缀拼接在 Release 地址前）：
     * 前三个支持 Range（多线程分段下载），ghproxy.cn 不支持 Range（自动降级单线程），
     * 最后为空串 = GitHub 官方直连兜底。
     *
     * 时效性说明：以上镜像于 2026-10-15 实测可用；镜像服务随时可能变迁，
     * 故多镜像并列 + 官方直连，任一可用即可完成下载。
     */
    val DOWNLOAD_MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://ghproxy.cn/",
        "",
    )

    data class ModelFile(
        val fileName: String,
        val sizeBytes: Long,
        /** Release 资产名（默认与本地文件名一致） */
        val assetName: String = fileName,
    ) {
        /** 全部候选下载地址：国内镜像优先，官方直连兜底 */
        val urls: List<String> get() = DOWNLOAD_MIRRORS.map { "$it$RELEASE_BASE$assetName" }
    }

    data class ModelPack(
        val id: String,
        val dirName: String,
        val displayName: String,
        val files: List<ModelFile>,
        val required: Boolean,   // 是否首次启动就必须（false = 按需安装）
    ) {
        val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    }

    /**
     * SenseVoiceSmall 多语言语音识别（中英日韩粤，自动语种，非自回归一次出结果）。
     * 输入 560 维 LFR 梅尔特征，CTC 解码，自带标点恢复与 ITN。
     *
     * 版本说明：2024-07-17 为阿里原始导出（质量与 funasr 官方一致）；
     * 2025-09-09 为粤语微调版（中英文质量下降），故采用 2024-07-17。
     * 上游：k2-fsa/sherpa-onnx Release（tag: asr-models，滚动更新，永久有效）。
     */
    val senseVoice = ModelPack(
        id = "sensevoice-small-int8",
        dirName = DIR_SENSEVOICE,
        displayName = "语音识别",
        required = true,
        files = listOf(
            ModelFile("model.int8.onnx", 239_233_841),
            ModelFile("tokens.txt", 315_894),
        ),
    )

    // ---------------- 英译中（Helsinki-NLP/opus-mt-en-zh，int8） ----------------
    val enToZh = ModelPack(
        id = "opus-mt-en-zh-int8",
        dirName = DIR_EN_ZH,
        displayName = "英译中",
        required = true,
        files = listOf(
            ModelFile("encoder_model.onnx", 52_726_553, assetName = "mt-en-zh_encoder_model.onnx"),
            ModelFile("decoder_model_merged.onnx", 60_013_018, assetName = "mt-en-zh_decoder_model_merged.onnx"),
            ModelFile("vocab.json", 1_747_795, assetName = "mt-en-zh_vocab.json"),
            ModelFile("source.spm", 806_435, assetName = "mt-en-zh_source.spm"),
            ModelFile("target.spm", 804_600, assetName = "mt-en-zh_target.spm"),
        ),
    )

    // ---------------- 中译英（Helsinki-NLP/opus-mt-zh-en，int8） ----------------
    val zhToEn = ModelPack(
        id = "opus-mt-zh-en-int8",
        dirName = DIR_ZH_EN,
        displayName = "中译英",
        required = false,
        files = listOf(
            ModelFile("encoder_model.onnx", 52_726_552, assetName = "mt-zh-en_encoder_model.onnx"),
            ModelFile("decoder_model_merged.onnx", 60_013_017, assetName = "mt-zh-en_decoder_model_merged.onnx"),
            ModelFile("vocab.json", 1_747_906, assetName = "mt-zh-en_vocab.json"),
            ModelFile("source.spm", 804_677, assetName = "mt-zh-en_source.spm"),
            ModelFile("target.spm", 806_530, assetName = "mt-zh-en_target.spm"),
        ),
    )

    val all: List<ModelPack> = listOf(senseVoice, enToZh, zhToEn)
}
