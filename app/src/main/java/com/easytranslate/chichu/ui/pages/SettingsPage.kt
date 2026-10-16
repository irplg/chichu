package com.easytranslate.chichu.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.easytranslate.chichu.data.ModelCatalog
import com.easytranslate.chichu.ui.QingYiViewModel
import com.easytranslate.chichu.ui.theme.BarShape
import com.easytranslate.chichu.ui.theme.BarShapeSmall
import com.easytranslate.chichu.ui.theme.QingYiColors

/**
 * 设置页：模型导入/下载、省电模式、下载线程数、释放内存。
 * 全部使用 Wear Compose Material3 组件，长条形（圆角矩形）样式。
 */
@Composable
fun SettingsPage(viewModel: QingYiViewModel, onImport: () -> Unit) {
    var powerSave by remember { mutableStateOf(viewModel.repository.powerSaveMode) }
    var threads by remember { mutableStateOf(viewModel.repository.threads) }
    val ui by viewModel.ui.collectAsState()
    val status by viewModel.modelStatus.collectAsState()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.titleMedium)
        }

        // ---------- 模型 ----------
        item {
            ModelRow(
                label = ModelCatalog.senseVoice.displayName,
                installed = status.senseVoice,
                busy = ui.packId == ModelCatalog.senseVoice.id,
                progress = if (ui.packId == ModelCatalog.senseVoice.id) ui.packProgress else 0f,
                onImport = onImport,
                onDownload = { viewModel.downloadModel(ModelCatalog.senseVoice) },
            )
        }
        item {
            ModelRow(
                label = ModelCatalog.enToZh.displayName,
                installed = status.enToZh,
                busy = ui.packId == ModelCatalog.enToZh.id,
                progress = if (ui.packId == ModelCatalog.enToZh.id) ui.packProgress else 0f,
                onImport = onImport,
                onDownload = { viewModel.downloadModel(ModelCatalog.enToZh) },
            )
        }
        item {
            ModelRow(
                label = ModelCatalog.zhToEn.displayName,
                installed = status.zhToEn,
                busy = ui.packId == ModelCatalog.zhToEn.id,
                progress = if (ui.packId == ModelCatalog.zhToEn.id) ui.packProgress else 0f,
                onImport = onImport,
                onDownload = { viewModel.downloadModel(ModelCatalog.zhToEn) },
            )
        }

        // ---------- 省电 ----------
        item {
            SwitchButton(
                checked = powerSave,
                onCheckedChange = {
                    powerSave = it
                    viewModel.setPowerSave(it)
                },
                label = { Text("省电模式", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.fillMaxWidth(),
                shape = BarShape,
            )
        }

        // ---------- 线程数 ----------
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "下载线程数：$threads",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    listOf(1, 2, 4, 8).forEach { n ->
                        Button(
                            onClick = {
                                threads = n
                                viewModel.setThreads(n)
                            },
                            modifier = Modifier.height(30.dp),
                            shape = BarShapeSmall,
                            colors = if (threads == n) {
                                ButtonDefaults.buttonColors(
                                    containerColor = QingYiColors.Primary,
                                    contentColor = QingYiColors.OnPrimary,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = QingYiColors.SurfaceVariant,
                                    contentColor = QingYiColors.OnSurface,
                                )
                            },
                        ) {
                            Text("$n", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ---------- 释放内存 ----------
        item {
            Button(
                onClick = { viewModel.releaseEngines() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                shape = BarShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = QingYiColors.SurfaceVariant,
                    contentColor = QingYiColors.OnSurface,
                ),
            ) { Text("释放引擎内存", fontSize = 12.sp) }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 单个模型行：名称 + 状态 + 导入/下载操作 */
@Composable
private fun ModelRow(
    label: String,
    installed: Boolean,
    busy: Boolean,
    progress: Float,
    onImport: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                if (installed) "已就绪" else "未导入",
                style = MaterialTheme.typography.labelSmall,
                color = if (installed) QingYiColors.Primary else QingYiColors.OnSurfaceVariant,
            )
        }
        when {
            busy -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(18.dp),
            )
            installed -> {}
            else -> {
                Button(
                    onClick = onImport,
                    modifier = Modifier.height(30.dp),
                    shape = BarShapeSmall,
                ) { Text("导入", fontSize = 11.sp) }
                Button(
                    onClick = onDownload,
                    modifier = Modifier.height(30.dp),
                    shape = BarShapeSmall,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QingYiColors.SurfaceVariant,
                        contentColor = QingYiColors.OnSurface,
                    ),
                ) { Text("下载", fontSize = 11.sp) }
            }
        }
    }
}
