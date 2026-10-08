package dev.averyzhoux.recognizer

import androidx.compose.ui.graphics.Color

/** 小米相机选中态的那个黄：也用于「近似命中」 */
internal val XiaomiYellow = Color(0xFFFFC800)

/** 「完全相同命中」用的绿。刻意和黄色区分开，一眼能看出这个命中是否精确 */
internal val MatchGreen = Color(0xFF3DDC84)

/**
 * 「已手动标记」用的蓝。
 *
 * 和绿色一样都在深色面板上读得清，但色相离得远，不会和精确/近似两种命中混掉。
 */
internal val MatchBlue = Color(0xFF4FC3F7)

/**
 * 命中项该用什么颜色：
 * - 表格项被手动标记过「已识别」→ 蓝色（优先级最高，人工标记压过自动判定）
 * - 完全相同（归一化后一致）→ 绿色
 * - 近似（包含 / 模糊 / 别名）→ 黄色
 * - 没命中 → null，由调用方决定灰显
 */
internal fun matchColor(match: LineMatch): Color? = when {
    !match.isHit -> null
    match.entry?.marked == true -> MatchBlue
    match.isExact -> MatchGreen
    else -> XiaomiYellow
}
