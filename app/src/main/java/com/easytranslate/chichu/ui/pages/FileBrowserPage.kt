package com.easytranslate.chichu.ui.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.easytranslate.chichu.data.ModelCatalog
import com.easytranslate.chichu.ui.QingYiViewModel
import com.easytranslate.chichu.ui.theme.BarShape
import com.easytranslate.chichu.ui.theme.QingYiColors
import java.io.File
import kotlinx.coroutines.delay

/**
 * 应用内文件选择器：浏览外部存储，点按与模型目录匹配的文件即导入。
 *
 * - 根目录为外部存储（/sdcard），需存储访问权限（首次进入时引导授权）：
 *   Android 11+ 走「所有文件访问」设置页；Android 10 及以下走 WRITE_EXTERNAL_STORAGE
 *   运行时弹窗授权（配合 requestLegacyExternalStorage 直接路径访问）
 * - 匹配规则与 [QingYiViewModel.importModel] 一致：父目录名优先，其次按文件名
 * - 导入写 files/models/<dirName>/，.part 临时文件 + 大小校验后原子改名
 */
@Composable
fun FileBrowserPage(viewModel: QingYiViewModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    val context = LocalContext.current

    var hasAccess by remember { mutableStateOf(hasStorageAccess(context)) }

    // Android 10 及以下：WRITE_EXTERNAL_STORAGE 运行时权限弹窗（授予/拒绝后重检）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAccess = granted || hasStorageAccess(context)
    }

    // 从系统设置返回后重新检查权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = hasStorageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasAccess) {
        PermissionPage(
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+：跳系统「所有文件访问」设置页
                    val intent = try {
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    } catch (e: Exception) {
                        null
                    } ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Wear OS 无应用内授权页：提示 adb 授权命令（详见《模型下载指南》）
                        viewModel.showAdbHint()
                    }
                } else {
                    // Android 10 及以下：系统弹窗请求读写权限
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
        )
        return
    }

    val root = remember { Environment.getExternalStorageDirectory() }
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf(listDir(current)) }
    val listState = rememberScalingLazyListState()
    val ui by viewModel.ui.collectAsState()
    val status by viewModel.modelStatus.collectAsState()

    fun navigate(dir: File) {
        current = dir
        entries = listDir(dir)
    }

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("导入模型", style = MaterialTheme.typography.titleMedium)
        }
        item {
            Text(
                current.absolutePath.removePrefix(root.absolutePath).ifEmpty { "/" }.let { "…${root.name}$it" },
                style = MaterialTheme.typography.labelSmall,
                color = QingYiColors.OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                shape = BarShape,
            ) { Text("返回", fontSize = 12.sp) }
        }

        // 上级目录（不允许越过外部存储根目录）
        if (current.absolutePath != root.absolutePath) {
            item {
                Button(
                    onClick = {
                        val parent = current.parentFile
                        if (parent != null && parent.absolutePath.length >= root.absolutePath.length) {
                            navigate(parent)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    shape = BarShape,
                    colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = QingYiColors.SurfaceVariant,
                        contentColor = QingYiColors.OnSurface,
                    ),
                ) { Text(".. 上级", fontSize = 12.sp) }
            }
        }

        // 空目录提示
        if (entries.isEmpty()) {
            item {
                Text(
                    "空目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = QingYiColors.OnSurfaceVariant,
                )
            }
        }

        // 目录
        val dirs = entries.filter { it.isDirectory }
        dirs.forEach { dir ->
            item(key = "d:${dir.absolutePath}") {
                Button(
                    onClick = { navigate(dir) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    shape = BarShape,
                    colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = QingYiColors.SurfaceVariant,
                        contentColor = QingYiColors.OnSurface,
                    ),
                ) {
                    Text(
                        "${dir.name}/",
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 文件：仅与模型目录匹配的可导入（高亮），其余置灰不可点
        val files = entries.filter { it.isFile }
        files.forEach { file ->
            val match = matchModelFile(file)
            item(key = "f:${file.absolutePath}") {
                if (match != null) {
                    Button(
                        onClick = { viewModel.importModel(file) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        shape = BarShape,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                file.name,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${match.first.displayName} · ${formatSize(file.length())}",
                                fontSize = 10.sp,
                                color = QingYiColors.OnPrimary,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            file.name,
                            fontSize = 11.sp,
                            color = QingYiColors.OnSurfaceVariant.copy(alpha = 0.4f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 导入进度
        if (ui.packId != null) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        progress = { ui.packProgress },
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "${ui.packLabel} ${(ui.packProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // 提示（错误/adb 授权指引等）
        ui.message?.let { msg ->
            item {
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = QingYiColors.Error,
                )
                LaunchedEffect(msg) {
                    delay(6000)
                    viewModel.consumeMessage()
                }
            }
        }

        // 状态提示
        item {
            Text(
                buildString {
                    append(if (status.asrReady) "语音识别 ✓" else "语音识别 ✗")
                    append("  ")
                    append(if (status.enToZh) "英译中 ✓" else "英译中 ✗")
                    append("  ")
                    append(if (status.zhToEn) "中译英 ✓" else "中译英 ✗")
                },
                style = MaterialTheme.typography.labelSmall,
                color = QingYiColors.OnSurfaceVariant,
            )
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/**
 * 存储访问权限检查（按系统版本分流）：
 * - Android 11+（API 30+）：「所有文件访问」MANAGE_EXTERNAL_STORAGE
 * - Android 10 及以下：WRITE_EXTERNAL_STORAGE 运行时权限（配合 requestLegacyExternalStorage）
 */
private fun hasStorageAccess(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

/** 未授予存储访问权限时的引导页（Android 11+ Wear OS 无应用内授权页，需 adb 授权一次） */
@Composable
private fun PermissionPage(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("需要存储权限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "浏览并导入模型文件",
            style = MaterialTheme.typography.labelSmall,
            color = QingYiColors.OnSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onGrant,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            shape = BarShape,
        ) { Text("去授权", fontSize = 12.sp) }
    }
}

/** 列出目录内容：目录在前、文件在后，各自按名称排序 */
private fun listDir(dir: File): List<File> {
    val children = dir.listFiles() ?: return emptyList()
    return children.filter { it.canRead() }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
}

/** 判断文件是否匹配某个模型包的文件（父目录名优先，其次按文件名；与 ViewModel 同规则） */
private fun matchModelFile(file: File): Pair<ModelCatalog.ModelPack, ModelCatalog.ModelFile>? {
    val parentName = file.parentFile?.name?.lowercase()
    if (parentName != null) {
        val byDir = ModelCatalog.all.firstOrNull { it.dirName == parentName }
        if (byDir != null) {
            val f = byDir.files.firstOrNull { it.fileName.equals(file.name, ignoreCase = true) }
            if (f != null) return byDir to f
        }
    }
    val candidates = ModelCatalog.all.mapNotNull { pack ->
        pack.files.firstOrNull { it.fileName.equals(file.name, ignoreCase = true) }?.let { pack to it }
    }
    return when {
        candidates.isEmpty() -> null
        candidates.size == 1 -> candidates[0]
        else -> candidates[0] // 同名文件（如 source.spm）交给 ViewModel 按缺失情况分配
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1fGB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.0fMB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}
