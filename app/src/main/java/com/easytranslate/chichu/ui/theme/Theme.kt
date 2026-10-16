package com.easytranslate.chichu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

/**
 * 轻译主题：极致省电取向
 * - 纯黑背景（AMOLED 手表上黑色像素不发光）
 * - 无动态取色（省去 Palette 计算）
 * - 无明暗双主题切换（手表常亮黑底最省电）
 * - 具体颜色统一走 QingYiColors 常量（界面各处直接引用）
 */
object QingYiColors {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0A)
    val SurfaceVariant = Color(0xFF1A1A1A)
    val Primary = Color(0xFF7DD3C0)      // 薄荷绿：主操作/录音态
    val OnPrimary = Color(0xFF00382E)
    val Secondary = Color(0xFF8AB4F8)    // 淡蓝：翻译结果/次要信息
    val OnSecondary = Color(0xFF062E6B)
    val Error = Color(0xFFF2B8B5)
    val Outline = Color(0xFF484848)
    val OnSurface = Color(0xFFE6E1E5)
    val OnSurfaceVariant = Color(0xFFCAC4D0)
}

/** 全局长条形（圆角矩形）控件形状：替代 Wear 默认的全圆角胶囊 */
val BarShape = RoundedCornerShape(16.dp)

/** 小号长条形（线程数选项等紧凑控件） */
val BarShapeSmall = RoundedCornerShape(10.dp)

@Composable
fun QingYiTheme(content: @Composable () -> Unit) {
    // 固定深色纯黑主题：不跟随系统，确保 AMOLED 省电与一致性
    MaterialTheme(content = content)
}
