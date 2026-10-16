package com.easytranslate.chichu.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.easytranslate.chichu.ui.QingYiViewModel
import com.easytranslate.chichu.ui.theme.BarShape
import com.easytranslate.chichu.ui.theme.QingYiColors
import kotlinx.coroutines.delay

/**
 * 主页：输入框（点按唤起键盘 / 长按语音听写）+ 翻译按钮 + 结果区。
 */
@Composable
fun HomePage(viewModel: QingYiViewModel, pagerState: PagerState) {
    val ui by viewModel.ui.collectAsState()
    val status by viewModel.modelStatus.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            // 标题行：当前方向 + 状态灯
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (viewModel.repository.targetLang == "en") "中 → 英" else "英 → 中",
                    style = MaterialTheme.typography.labelMedium,
                    color = QingYiColors.Secondary,
                )
                val asrOk = status.asrReady
                Text(
                    text = if (asrOk) "● 就绪" else "○ 模型缺失",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (asrOk) QingYiColors.Primary else QingYiColors.Error,
                )
            }

            // 输入框：点按聚焦（系统键盘），长按启动语音
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                            onLongPress = { viewModel.startVoiceInput() },
                        )
                    },
            ) {
                BasicTextField(
                    value = ui.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .padding(6.dp),
                    textStyle = TextStyle(
                        color = QingYiColors.OnSurface,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Start,
                    ),
                    cursorBrush = SolidColor(QingYiColors.Primary),
                    singleLine = false,
                    maxLines = 3,
                    decorationBox = { inner ->
                        Box {
                            if (ui.input.isEmpty() && !ui.recognizing) {
                                Text(
                                    "点按输入 / 长按说话",
                                    color = QingYiColors.OnSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            // 录音态：音量波形
            if (ui.recognizing) {
                Text(
                    "聆听中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = QingYiColors.Primary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(28.dp),
                ) {
                    repeat(5) { i ->
                        val h = (6f + 20f * ui.volume * (0.4f + 0.6f * ((i % 3) / 2f))).coerceIn(3f, 26f)
                        Box(
                            Modifier
                                .height(h.dp)
                                .width(4.dp)
                                .background(QingYiColors.Primary)
                        )
                    }
                }
                Button(onClick = viewModel::stopVoiceInput, shape = BarShape) {
                    Text("停止", fontSize = 12.sp)
                }
            }

            // 翻译按钮 / 进度
            when {
                ui.translating -> {
                    CircularProgressIndicator(modifier = Modifier.height(28.dp))
                    Text("翻译中…", style = MaterialTheme.typography.labelSmall, color = QingYiColors.OnSurfaceVariant)
                }
                else -> {
                    Button(
                        onClick = viewModel::translate,
                        enabled = ui.input.isNotBlank(),
                        shape = BarShape,
                    ) { Text("翻译", fontSize = 14.sp) }
                }
            }

            // 结果区
            if (ui.result.isNotEmpty()) {
                Text(
                    text = ui.result,
                    style = MaterialTheme.typography.bodyLarge,
                    color = QingYiColors.Secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 错误/提示
            ui.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = QingYiColors.Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                LaunchedEffect(msg) {
                    delay(3000)
                    viewModel.consumeMessage()
                }
            }

            // 模型安装/下载进度
            ui.packId?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { ui.packProgress },
                        modifier = Modifier.height(24.dp),
                    )
                    Text(
                        "${ui.packLabel} ${(ui.packProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = QingYiColors.OnSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 模型缺失引导
            if (!status.asrReady || !status.mtReady(viewModel.repository.targetLang)) {
                Text(
                    "模型缺失，左滑导入",
                    style = MaterialTheme.typography.labelSmall,
                    color = QingYiColors.OnSurfaceVariant,
                )
            }
        }
    }
}
