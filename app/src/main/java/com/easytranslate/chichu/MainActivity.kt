package com.easytranslate.chichu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import com.easytranslate.chichu.data.TraditionalSimplified
import com.easytranslate.chichu.ui.QingYiViewModel
import com.easytranslate.chichu.ui.pages.AboutPage
import com.easytranslate.chichu.ui.pages.FileBrowserPage
import com.easytranslate.chichu.ui.pages.HistoryPage
import com.easytranslate.chichu.ui.pages.HomePage
import com.easytranslate.chichu.ui.pages.LanguagePage
import com.easytranslate.chichu.ui.pages.SettingsPage
import com.easytranslate.chichu.ui.theme.QingYiColors
import com.easytranslate.chichu.ui.theme.QingYiTheme

/**
 * 轻译 · 主界面
 *
 * 五页横滑导航（Wear 惯例：主页在左，逐页向左滑深入；向右滑返回）：
 *   主页(0) → 目标语言(1) → 翻译历史(2) → 设置(3) → 关于(4)
 * 文件选择器（模型导入）以全屏浮层呈现，返回键/系统返回手势关闭。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: QingYiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TraditionalSimplified.load(applicationContext)
        setContent {
            QingYiTheme {
                QingYiApp(viewModel)
            }
        }
    }
}

@Composable
fun QingYiApp(viewModel: QingYiViewModel) {
    var showBrowser by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        val pagerState = rememberPagerState(initialPage = 0, pageCount = { 5 })
        HorizontalPager(state = pagerState) { page ->
            when (page) {
                0 -> HomePage(viewModel, pagerState = pagerState)
                1 -> LanguagePage(viewModel, onImport = { showBrowser = true })
                2 -> HistoryPage(viewModel)
                3 -> SettingsPage(viewModel, onImport = { showBrowser = true })
                else -> AboutPage()
            }
        }

        // 模型导入：应用内文件选择器（全屏浮层）
        if (showBrowser) {
            BackHandler { showBrowser = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(QingYiColors.Black),
            ) {
                FileBrowserPage(
                    viewModel = viewModel,
                    onClose = { showBrowser = false },
                )
            }
        }
    }
}
