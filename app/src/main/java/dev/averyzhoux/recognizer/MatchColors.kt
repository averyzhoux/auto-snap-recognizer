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
 * - **完全相同 or 包含**（[LineMatch.isStrong]）→ 绿色
 * - 模糊命中 → 黄色
 * - 没命中 → null，由调用方决定灰显
 *
 * 「包含」也算绿：`6TH-6983` 里就是 `TH-6983`，表格项确实在这行里，
 * 和「可能是它、但认错了字母」的模糊命中不是一回事。
 * 判据是 [LineMatch.kind] 这个**种类**，不是相似度阈值——原因见 [MatchKind]。
 */
internal fun matchColor(match: LineMatch): Color? = when {
    !match.isHit -> null
    match.entry?.marked == true -> MatchBlue
    match.isStrong -> MatchGreen
    else -> XiaomiYellow
}

/**
 * 结果面板上一条命中**显示成什么颜色**。
 *
 * 专门用来做「条件暂停」的判据：命中某种颜色就停下来。
 *
 * ★ 判据是**显示颜色**，不是 [MatchKind]。两者在「已手动标记」这项上会分叉：
 *   一个已标记的精确命中显示蓝色（见 [matchColor] 的优先级），
 *   所以它属于 [Blue]，不属于 [Green]。用户勾「绿」时它不会触发——
 *   这看起来像漏判，其实是刻意的：面板上它就是蓝的，按看到的颜色说话。
 */
internal enum class HitColor(
    /** 面板上那个色块下面用的单字（只在无障碍描述里还会用到） */
    val label: String,
    val color: Color,
    /** 面板右侧那句图例，写成「绿 = 精确」这种一律对齐的三段式 */
    val explain: String
) {
    /** 完全相同 / 包含命中，且没被手动标记过 */
    Green("绿", MatchGreen, "绿 = 精确"),

    /** 模糊命中（相似度高但不等），且没被手动标记过。最容易误触发，见面板注释 */
    Yellow("黄", XiaomiYellow, "黄 = 模糊"),

    /** 该表格项已被手动标记「已识别」，优先级最高，压过绿黄 */
    Blue("蓝", MatchBlue, "蓝 = 已标记")
}

/** 这条命中显示成哪种颜色；没命中给 null。和 [matchColor] 的判断顺序必须一致。 */
internal fun hitColorOf(match: LineMatch): HitColor? = when {
    !match.isHit -> null
    match.entry?.marked == true -> HitColor.Blue
    match.isStrong -> HitColor.Green
    else -> HitColor.Yellow
}

/**
 * 色块上那个对勾该用黑还是白。
 *
 * 黄、绿都偏亮，白勾糊在底色里看不清，得用黑勾；蓝偏暗，用白勾。
 *
 * 刻意**不**去算 `Color.luminance()`：那个 API 目前挂着 `@ExperimentalGraphicsApi`，
 * 为三种写死的颜色引入一个实验性依赖不划算。颜色就三个，改了色值顺手核对这里即可。
 */
internal val HitColor.checkMarkColor: Color
    get() = when (this) {
        HitColor.Green, HitColor.Yellow -> Color.Black
        HitColor.Blue -> Color.White
    }
