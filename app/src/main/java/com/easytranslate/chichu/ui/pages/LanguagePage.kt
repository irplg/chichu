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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.easytranslate.chichu.data.ModelCatalog
import com.easytranslate.chichu.ui.QingYiViewModel
import com.easytranslate.chichu.ui.theme.BarShape
import com.easytranslate.chichu.ui.theme.BarShapeSmall
import com.easytranslate.chichu.ui.theme.QingYiColors

/**
 * 目标语言页：选择翻译方向（中→英 / 英→中），缺失模型可导入或联网下载。
 */
@Composable
fun LanguagePage(viewModel: QingYiViewModel, onImport: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val status by viewModel.modelStatus.collectAsState()
    val current = viewModel.repository.targetLang
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
            Text("目标语言", style = MaterialTheme.typography.titleMedium)
        }

        // 中 → 英
        item {
            DirectionBar(
                label = "中文 → English",
                selected = current == "en",
                installed = status.zhToEn,
                busy = ui.packId == ModelCatalog.zhToEn.id,
                progress = if (ui.packId == ModelCatalog.zhToEn.id) ui.packProgress else 0f,
                onSelect = { viewModel.setTargetLang("en") },
                onImport = onImport,
                onDownload = { viewModel.downloadModel(ModelCatalog.zhToEn) },
            )
        }

        // 英 → 中
        item {
            DirectionBar(
                label = "English → 中文",
                selected = current == "zh",
                installed = status.enToZh,
                busy = ui.packId == ModelCatalog.enToZh.id,
                progress = if (ui.packId == ModelCatalog.enToZh.id) ui.packProgress else 0f,
                onSelect = { viewModel.setTargetLang("zh") },
                onImport = onImport,
                onDownload = { viewModel.downloadModel(ModelCatalog.enToZh) },
            )
        }

        // 语音识别模型（两个方向共用）
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("语音识别", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (status.senseVoice) "已就绪" else "未导入",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.senseVoice) QingYiColors.Primary else QingYiColors.OnSurfaceVariant,
                    )
                }
                when {
                    status.senseVoice -> {}
                    ui.packId == ModelCatalog.senseVoice.id -> CircularProgressIndicator(
                        progress = { ui.packProgress },
                        modifier = Modifier.size(18.dp),
                    )
                    else -> {
                        Button(
                            onClick = onImport,
                            modifier = Modifier.height(30.dp),
                            shape = BarShapeSmall,
                        ) { Text("导入", fontSize = 11.sp) }
                        Button(
                            onClick = { viewModel.downloadModel(ModelCatalog.senseVoice) },
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

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 方向选择条：选中态主色高亮；模型缺失时提供导入/下载 */
@Composable
private fun DirectionBar(
    label: String,
    selected: Boolean,
    installed: Boolean,
    busy: Boolean,
    progress: Float,
    onSelect: () -> Unit,
    onImport: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = onSelect,
            enabled = installed,
            modifier = Modifier
                .weight(1f)
                .height(34.dp),
            shape = BarShape,
            colors = if (selected) {
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
            Text(
                if (selected) "✓ $label" else label,
                fontSize = 12.sp,
            )
        }
        when {
            busy -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(18.dp),
            )
            !installed -> {
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
