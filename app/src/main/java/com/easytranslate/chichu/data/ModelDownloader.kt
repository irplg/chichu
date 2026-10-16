package com.easytranslate.chichu.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * 轻译 · 模型下载器（联网下载通道）
 *
 * APK 不内置模型：用户既可在设置中导入本地模型文件，也可用本类联网下载：
 * - **每文件多源故障转移**：依次尝试 [ModelCatalog.DOWNLOAD_MIRRORS] 各镜像 + 官方直连
 * - **Range 探测**：首字节请求判定镜像是否支持分段；不支持则自动降级单线程流式下载
 * - **多线程分段下载**：每文件按线程数切段，各段并发 Range 请求
 * - **段级断点续传**：每段一个 .partN 文件，中断后按段续传
 * - 下载完成后按目录字节数校验
 * - 默认 2 线程，设置页可调（1-8）
 */
class ModelDownloader(
    private val modelsDir: File,
) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val BUFFER = 256 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        const val DEFAULT_THREADS = 2
        const val MAX_THREADS = 8
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    sealed interface Progress {
        data class Downloading(
            val fileName: String,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : Progress

        data class Done(val pack: ModelCatalog.ModelPack) : Progress
    }

    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 判断某个模型包是否已完整安装（所有文件存在且大小一致） */
    fun isInstalled(pack: ModelCatalog.ModelPack): Boolean =
        pack.files.all { f ->
            val file = File(modelsDir, "${pack.dirName}/${f.fileName}")
            file.isFile && file.length() == f.sizeBytes
        }

    /**
     * 下载并安装一个模型包。
     * @param threads 下载线程数（默认 [DEFAULT_THREADS]，上限 [MAX_THREADS]）
     */
    fun downloadPack(pack: ModelCatalog.ModelPack, threads: Int = DEFAULT_THREADS): Flow<Progress> = channelFlow {
        if (isInstalled(pack)) {
            send(Progress.Done(pack))
            return@channelFlow
        }
        val packDir = File(modelsDir, pack.dirName)
        packDir.mkdirs()
        val downloaded = AtomicLong(0)
        val total = pack.totalBytes

        val progressJob = launchProgress(pack.displayName, total, downloaded) { p -> send(p) }
        try {
            for (f in pack.files) {
                val dest = File(packDir, f.fileName)
                if (dest.isFile && dest.length() == f.sizeBytes) {
                    downloaded.addAndGet(f.sizeBytes)
                    continue
                }
                downloadFileWithFailover(f, dest, threads, downloaded)
            }
        } catch (e: CancellationException) {
            progressJob.cancel()
            throw e
        } catch (e: Exception) {
            progressJob.cancel()
            Log.w(TAG, "download failed: ${pack.id}", e)
            throw DownloadException("下载失败：${e.message ?: e.javaClass.simpleName}", e)
        }
        progressJob.cancel()

        if (!isInstalled(pack)) {
            throw DownloadException("下载后文件校验失败：${pack.displayName}")
        }
        send(Progress.Done(pack))
    }.flowOn(Dispatchers.IO)

    /** 单文件多源故障转移：依次尝试各镜像地址，全部失败才抛错 */
    private suspend fun downloadFileWithFailover(
        file: ModelCatalog.ModelFile,
        dest: File,
        threads: Int,
        counter: AtomicLong,
    ) {
        val errors = mutableListOf<String>()
        for (url in file.urls) {
            try {
                downloadFile(url, file, dest, threads, counter)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source failed: $url", e)
                errors += "$url: ${e.message ?: e.javaClass.simpleName}"
                clearParts(dest)
            }
        }
        throw DownloadException("所有下载源均失败（${file.fileName}）：\n" + errors.joinToString("\n"))
    }

    /** 探测 URL 是否支持 Range：支持→分段下载；不支持→单线程流式下载 */
    private suspend fun downloadFile(
        url: String,
        file: ModelCatalog.ModelFile,
        dest: File,
        threads: Int,
        counter: AtomicLong,
    ) {
        val probe = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(probe).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw DownloadException("HTTP ${resp.code} for $url")
            }
            val supportsRange = resp.code == 206
            // 校验远端文件大小（Content-Range: bytes 0-0/<total> 或 Content-Length）
            val remoteTotal = when {
                supportsRange -> resp.header("Content-Range")
                    ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                else -> resp.body?.contentLength()?.takeIf { it > 0 }
            }
            if (remoteTotal != null && remoteTotal != file.sizeBytes) {
                throw DownloadException("远端文件大小不符：$remoteTotal != ${file.sizeBytes} ($url)")
            }
            if (supportsRange) {
                downloadFileSegmented(url, file, dest, threads, counter)
            } else {
                downloadFileSingleStream(url, file, dest, counter)
            }
        }
    }

    // ---------------- 多线程分段下载 ----------------

    private suspend fun downloadFileSegmented(
        url: String,
        file: ModelCatalog.ModelFile,
        dest: File,
        threads: Int,
        counter: AtomicLong,
    ) {
        val nThreads = threads.coerceIn(1, MAX_THREADS)

        // 分段规划：segCount 段，每段 segSize 字节
        val segSize = (file.sizeBytes + nThreads - 1) / nThreads
        val segCount = ((file.sizeBytes + segSize - 1) / segSize).toInt()
        val partFiles = (0 until segCount).map { File(dest.parentFile, "${dest.name}.part$it") }
        val segDownloaded = LongArray(segCount) { i ->
            partFiles[i].takeIf { it.isFile }?.length()?.coerceAtMost(segSize) ?: 0L
        }
        segDownloaded.forEach { counter.addAndGet(it) }

        coroutineScope {
            val jobs = (0 until segCount).map { i ->
                async(Dispatchers.IO) {
                    val start = i * segSize
                    val end = minOf(start + segSize, file.sizeBytes)
                    downloadSegment(url, partFiles[i], start, end, counter)
                }
            }
            jobs.awaitAll()
        }

        mergeParts(partFiles, dest, file.sizeBytes)
    }

    /** 下载一段 [start, end) 到 part 文件（支持段内续传） */
    private fun downloadSegment(
        url: String,
        partFile: File,
        start: Long,
        end: Long,
        counter: AtomicLong,
    ) {
        var existing = if (partFile.isFile) partFile.length() else 0L
        if (existing >= end - start) return

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${start + existing}-${end - 1}")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw DownloadException("HTTP ${resp.code} for $url [${start},${end})")
            }
            val body = resp.body ?: throw DownloadException("空响应体: $url")
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(existing)
                val buffer = ByteArray(BUFFER)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        existing += read
                        counter.addAndGet(read.toLong())
                    }
                }
                raf.fd.sync()
            }
            if (partFile.length() != end - start) {
                throw DownloadException(
                    "分段大小不符：${partFile.length()} != ${end - start} (${partFile.name})"
                )
            }
        }
    }

    // ---------------- 单线程流式下载（镜像不支持 Range 时降级） ----------------

    private fun downloadFileSingleStream(
        url: String,
        file: ModelCatalog.ModelFile,
        dest: File,
        counter: AtomicLong,
    ) {
        clearParts(dest)
        val tmp = File(dest.parentFile, dest.name + ".stream")
        if (tmp.exists()) tmp.delete()

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw DownloadException("HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw DownloadException("空响应体: $url")
            RandomAccessFile(tmp, "rw").use { raf ->
                val buffer = ByteArray(BUFFER)
                var written = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        counter.addAndGet(read.toLong())
                    }
                }
                raf.fd.sync()
                if (written != file.sizeBytes) {
                    throw DownloadException("下载大小不符：$written != ${file.sizeBytes} ($url)")
                }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    // ---------------- 合并与清理 ----------------

    private fun mergeParts(partFiles: List<File>, dest: File, sizeBytes: Long) {
        val tmp = File(dest.parentFile, dest.name + ".merge")
        RandomAccessFile(tmp, "rw").use { out ->
            out.setLength(sizeBytes)
            var pos = 0L
            for (part in partFiles) {
                if (!part.isFile || part.length() == 0L) {
                    throw DownloadException("分段缺失：${part.name}")
                }
                part.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        pos += read
                    }
                }
                part.delete()
            }
            if (pos != sizeBytes) {
                throw DownloadException("合并后大小不符：$pos != $sizeBytes")
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** 清理某个目标文件的分段临时文件（切换下载源时调用，避免跨源续传说错） */
    private fun clearParts(dest: File) {
        dest.parentFile?.listFiles()?.forEach { f ->
            if (f.name.startsWith("${dest.name}.part") ||
                f.name == "${dest.name}.merge" ||
                f.name == "${dest.name}.stream"
            ) {
                f.delete()
            }
        }
    }

    // ---------------- 进度辅助 ----------------

    private suspend fun launchProgress(
        fileName: String,
        totalBytes: Long,
        counter: AtomicLong,
        emit: suspend (Progress) -> Unit,
    ): kotlinx.coroutines.Job = coroutineScope {
        launch {
            var last = -1L
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val cur = counter.get()
                if (cur != last) {
                    last = cur
                    emit(Progress.Downloading(fileName, cur, totalBytes))
                }
            }
        }
    }
}
