package com.easytranslate.chichu.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.easytranslate.chichu.R
import com.easytranslate.chichu.ui.theme.BarShape
import com.easytranslate.chichu.ui.theme.QingYiColors

/**
 * 关于页：应用名字 → 应用图标 → QQ 群入口。
 * 点击「QQ 群」展示群图片（点按全屏任意处关闭）。
 */
@Composable
fun AboutPage() {
    var showGroup by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "轻译",
            style = MaterialTheme.typography.titleLarge,
            color = QingYiColors.OnSurface,
        )
        Spacer(Modifier.height(14.dp))
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_round),
            contentDescription = "轻译",
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { showGroup = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            shape = BarShape,
        ) { Text("QQ 群", fontSize = 13.sp) }
    }

    if (showGroup) {
        BackHandler { showGroup = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable { showGroup = false },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.qq_group),
                contentDescription = "QQ 群",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "点按关闭",
                style = MaterialTheme.typography.labelSmall,
                color = QingYiColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}
