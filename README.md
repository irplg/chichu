# 轻译 · Wear OS 离线翻译

> 仓库地址：https://github.com/irplg/chichu

完全离线的 Wear OS 手表翻译应用：**本地中英文语音识别 + 本地双向翻译**。
APK 仅 37MB，模型不内置——首次使用前在设置中**导入**（应用内文件选择器）或**联网下载**（内置多线程下载器），详见随附《模型下载指南.md》。

- 软件名：**轻译** · 包名 `com.easytranslate.chichu` · minSdk 30（Wear OS 3+）
- 处理器支持：**arm64-v8a + armeabi-v7a（32 位老手表）**
- UI：Jetpack Compose for Wear，Material 3 for Wear（`androidx.wear.compose:compose-material3`）
- 图标：灰底白字（用户提供）

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 本地语音听写 | 长按输入框说话，SenseVoiceSmall int8 本地识别，**中英混说、自动语种**，自带标点恢复与 ITN（"九月三十号下午三点半"→"9月30号下午3点半"） |
| 本地翻译 | 输入框下方显示译文；自动判断方向（含中文→中译英，否则→英译中），也可在语言页固定方向 |
| 模型导入 | 应用内文件选择器浏览手表存储，匹配模型文件高亮，点按即导入（大小校验 + 原子改名） |
| 模型下载 | 内置多线程下载器（默认双线程，1/2/4/8 可调），多镜像故障转移，断点续传 |
| 翻译历史 | 左滑两页查看，本地保存最近 200 条 |
| 设置 | 省电模式、下载线程数、释放引擎内存 |
| 关于 | 应用名 / 图标 / QQ 群入口 |
| 极致省电 | 引擎懒加载、无装饰动画、省电模式下降线程与 beam 宽度 |

### 交互（五页横滑，`HorizontalPager`）

> 手势遵循 Wear 惯例：**主页在左，向左滑逐页深入；向右滑返回**（仅返回保持右滑）。

1. **主页**（第 1 页）：点按输入框唤起系统键盘；**长按输入框**开始本地语音听写（松手自动识别并填入）；下方显示译文。
2. **目标语言**：选择 中→英 / 英→中；模型缺失时可导入或下载。
3. **历史**：最近翻译记录。
4. **设置**：模型状态与导入/下载、省电模式、下载线程数、释放引擎内存。
5. **关于**：应用名字 → 应用图标 → QQ 群（点击展示群图片）。

---

## 模型：不内置，导入或下载

| 模型 | 用途 | 大小 | 许可 |
| --- | --- | --- | --- |
| SenseVoiceSmall int8（2024-07-17 版） | 语音识别（中英日韩粤，自动语种） | 239.2 MB | Apache-2.0 |
| opus-mt-en-zh int8 | 英译中 | 115.1 MB | CC-BY-4.0 |
| opus-mt-zh-en int8 | 中译英 | 115.1 MB | CC-BY-4.0 |

- **下载源：GitHub Release**（仓库由本项目维护，永久有效：https://github.com/irplg/chichu/releases/tag/models-v1 ）+ 国内镜像加速：
  `gh-proxy.com` → `ghfast.top` → `ghproxy.net` → `ghproxy.cn` → GitHub 官方直连。
  每个文件独立按序切换源；首字节 Range 探测，镜像不支持分段时自动降级单线程。
- **导入**（设置页/语言页「导入」）：应用内文件选择器浏览 `/sdcard`，文件按目录名/文件名匹配模型包，`.part` 临时文件 + 字节数校验后原子改名。
  Wear OS 需先用电脑授权一次：`adb shell appops set com.easytranslate.chichu MANAGE_EXTERNAL_STORAGE allow`。
- 多线程分段下载：默认双线程、可调 1-8、段级断点续传（`.partN`），全部完成后合并校验。
- 模型存放于应用私有目录 `files/models/<包名>/`，日常使用**完全不联网**。

---

## 构建

环境：JDK 17+、Android SDK（compileSdk 37）。

```bash
cd chichu
./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 签名发布包（R8 压缩，有密钥时自动签名）
```

产物（ABI 分包）：

```
app/build/outputs/apk/release/app-arm64-v8a-release.apk     # 37 MB（64 位，主流手表）
app/build/outputs/apk/release/app-armeabi-v7a-release.apk   # 27 MB（32 位老手表）
app/build/outputs/apk/release/app-universal-release.apk     # 143 MB（双 ABI）
```

APK 主体为 ONNX Runtime 原生库（arm64 约 34MB）；模型不随包分发，故体积从 509MB 降到 37MB。

### 签名

**签名密钥不随公开仓库分发**（安全要求）。release 签名为条件启用：把 PKCS12 密钥放到
`app/keystore/qingyi.jks`（alias `qingyi`，store/key 密码 `qingyi2026`）后，
`assembleRelease` 自动签名；克隆仓库无该文件时产出未签名包，需自行 `apksigner` 签名后才能安装。
分发到应用商店前请替换为自己的密钥并妥善保管密码。

安装：`adb install app-arm64-v8a-release.apk`（按手表架构二选一），
或在手表侧文件管理器直接安装。

---

## 技术要点（均有实验基准支撑）

### 语音识别：SenseVoice int8 ONNX

- **前端**（Kotlin 手写，与 funasr/torchaudio 逐位对齐，四语言 maxdiff < 4e-4）：
  kaldi fbank（25ms/10ms、hamming 窗、去直流、预加重 0.97、512 点 FFT、80 路三角 mel、`log(max(·, eps))`）
  → LFR(m=7, n=6，左 padding 3 帧) → CMVN（am.mvn 的 AddShift/Rescale，560 维）
- mel 滤波器组与 CMVN 统计量随 APK assets 下发（`sensevoice/mel_filters.bin` 82KB、`cmvn.bin` 4.5KB）
- **输入**：`x [1,T,560]`、`x_length [1]`、`language [1]`（0=auto）、`text_norm [1]`（14=带 ITN）
- **CTC 解码**：argmax → 去相邻重复 → 去 blank(0) → 只保留 token id ∈ [3, 24884)
- **版本选型**：2024-07-17 原始导出版优于 2025-09-09 粤语微调版（后者英文大小写/拼写退化）
- 实测：中文「开饭时间早上9点至下午5点。」粤语「呢几个字都表达唔到我想讲嘅意思。」

### 翻译：Marian opus-mt int8 ONNX

- SentencePiece Viterbi 分词与 HF tokenizer 逐 token 一致；**编码器尾部 `</s>`(id 0) 不可省略**
- 编码前 NFKC 规范化；beam=6、length_penalty=1.0、禁 `<pad>`(65000)、每 beam 独立 KV cache
- **no_repeat_ngram_size=4**：抑制 Marian 尾部退化重复（实测 en→zh 8 句从 5 句可用提升到 7 句优秀；zh→en 8/8）
- en→zh 输出繁体经内置 OpenCC 子集（3553 条）转简体

### 模型导入 / 多线程下载引擎

- **ModelRepository.importFile**（外部文件 → 私有目录）：流式拷贝 + 字节数校验 + `.part` 原子改名，失败不留半成品
- **ModelDownloader**（网络）：每文件按 `gh-proxy.com → ghfast.top → ghproxy.net → ghproxy.cn → 官方直连`
  顺序故障转移；首字节 Range 探测，不支持分段的镜像自动降级单线程流式；
  支持分段时每文件切 N 段并发 Range 请求，每段独立 `.partN` 文件段级断点续传，全部完成后合并校验
- 线程数默认 2，设置页可调（1-8），持久化于 SharedPreferences

---

## 工程结构

```
chichu/
├── app/src/main/
│   ├── assets/sensevoice/           # 前端资产：mel_filters.bin / cmvn.bin / t2s.txt
│   ├── java/com/easytranslate/chichu/
│   │   ├── asr/                     # SenseVoiceFrontend（fbank/LFR/CMVN/FFT）
│   │   │                            # SenseVoiceRecognizer（ONNX + CTC）、AudioRecorder
│   │   ├── mt/                      # MarianTranslator（beam search）、SentencePieceModel
│   │   ├── data/                    # ModelCatalog / ModelDownloader
│   │   │                            # ModelRepository（导入+下载+引擎）/ HistoryStore / TraditionalSimplified
│   │   ├── ui/                      # 五页 UI + ViewModel + 主题
│   │   │   └── pages/               # Home / Language / History / Settings / About / FileBrowser
│   │   ├── MainActivity.kt          # 五页 HorizontalPager + 文件选择器浮层
│   │   └── QingYiApplication.kt
│   ├── res/mipmap-*/                # 应用图标（灰底白字）
│   ├── res/drawable/qq_group.jpg    # 关于页 QQ 群图片
│   └── AndroidManifest.xml
├── app/keystore/qingyi.jks          # 发布签名密钥（不入公开仓库，见上文）
└── gradle/wrapper/                  # distributionUrl = 腾讯云 Gradle 镜像
```

## 权限

| 权限 | 用途 |
| --- | --- |
| `RECORD_AUDIO` | 长按听写 |
| `MANAGE_EXTERNAL_STORAGE` | 文件选择器浏览手表存储（Wear OS 需 adb 授权一次，见《模型下载指南》） |
| `INTERNET` | 仅模型网络下载通道 |

## 使用的开源项目

以下开源项目均为 **「轻译」专门使用**（本应用的关键依赖；模型权重为对上游公开资产的重新分发，与上游逐字节一致）：

| 开源项目 | 作者 / 组织 | 原地址 | 轻译中的用途 |
| --- | --- | --- | --- |
| SenseVoiceSmall（语音识别模型，Apache-2.0） | 阿里 FunAudioLLP / FunASR 团队 | https://github.com/FunAudioLLP/SenseVoice | 轻译专门使用：本地中英混说语音识别（int8 ONNX 版） |
| sherpa-onnx（SenseVoice 的 ONNX 导出与端侧参考实现，Apache-2.0） | k2-fsa 社区（csukuangfj 等） | https://github.com/k2-fsa/sherpa-onnx | 轻译专门使用：导出版模型与前端参数对齐基准 |
| opus-mt-en-zh / opus-mt-zh-en（机器翻译模型，CC-BY-4.0） | University of Helsinki / Helsinki-NLP（OPUS-MT 项目） | https://github.com/Helsinki-NLP/OPUS-MT | 轻译专门使用：本地英中 / 中英互译（int8 ONNX 版） |
| opus-mt ONNX int8 量化版 | Xenova | https://huggingface.co/Xenova/opus-mt-en-zh · https://huggingface.co/Xenova/opus-mt-zh-en | 轻译专门使用：int8 量化权重来源（已重新分发至本仓库 Release） |
| ONNX Runtime（MIT） | Microsoft | https://github.com/microsoft/onnxruntime | 轻译专门使用：端侧 ONNX 推理引擎 |
| OkHttp（Apache-2.0） | Square | https://github.com/square/okhttp | 轻译专门使用：模型多线程分段下载 |
| Jetpack Compose / Wear Compose Material3（Apache-2.0） | Google / Android Open Source Project | https://developer.android.com/jetpack/compose · https://developer.android.com/wear | 轻译专门使用：手表 UI（Material 3 for Wear） |
| kotlinx.serialization（Apache-2.0） | JetBrains | https://github.com/Kotlin/kotlinx.serialization | 轻译专门使用：翻译历史本地存储 |
| SentencePiece（Apache-2.0） | Google | https://github.com/google/sentencepiece | 轻译专门使用：Marian 分词算法基准（Viterbi 实现） |
| OpenCC（Apache-2.0） | BYVoid 等 | https://github.com/BYVoid/OpenCC | 轻译专门使用：英译中输出繁体转简体（内置 3553 条子集） |
| Android Gradle Plugin / AndroidX（Apache-2.0） | Google / Android Open Source Project | https://developer.android.com/build | 轻译专门使用：构建与基础组件 |

模型下载源（GitHub Release + 国内镜像）见上文「模型」一节与随附《模型下载指南.md》。

## 已知限制

- opus-mt 为轻量通用模型，**适合口语短句**；长句/专业文本质量有限
- 语音听写上限 30 秒/次
- 三个模型包合计约 465MB，导入后请确保手表剩余空间充足

## 许可

- 轻译源码：随本仓库分发（作者 irplg）
- 使用的开源项目及许可见上方「使用的开源项目」一览（均为轻译专门使用）
- 模型权重为上游公开资产的重新分发，版权归原项目所有（Apache-2.0 / CC-BY-4.0）
