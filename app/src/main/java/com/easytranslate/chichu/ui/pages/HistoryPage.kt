package com.easytranslate.chichu.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.easytranslate.chichu.data.ModelDownloader
import com.easytranslate.chichu.ui.QingYiViewModel
import com.easytranslate.chichu.ui.theme.BarShapeSmall
import com.easytranslate.chichu.ui.theme.QingYiColors

/**
 * 翻译历史页：最近 200 条，可清空。
 */
@Composable
fun HistoryPage(viewModel: QingYiViewModel) {
    var entries by remember { mutableStateOf(viewModel.history.load()) }
    val listState = rememberScalingLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("翻译历史", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        if (entries.isEmpty()) {
            Text(
                "暂无记录",
                style = MaterialTheme.typography.bodyMedium,
                color = QingYiColors.OnSurfaceVariant,
            )
        } else {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries.size) { i ->
                    val e = entries[i]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            e.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = QingYiColors.OnSurface,
                            maxLines = 2,
                        )
                        Text(
                            e.translated,
                            style = MaterialTheme.typography.labelMedium,
                            color = QingYiColors.Secondary,
                            maxLines = 2,
                        )
                        Text(
                            e.direction,
                            style = MaterialTheme.typography.labelSmall,
                            color = QingYiColors.OnSurfaceVariant,
                        )
                    }
                }
            }
            Button(
                onClick = {
                    viewModel.history.clear()
                    entries = emptyList()
                },
                modifier = Modifier.height(30.dp),
                shape = BarShapeSmall,
            ) { Text("清空历史", fontSize = 11.sp) }
        }
    }
}
