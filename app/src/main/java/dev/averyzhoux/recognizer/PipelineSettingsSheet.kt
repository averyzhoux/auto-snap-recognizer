package dev.averyzhoux.recognizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 各条管线的可调参数。
 *
 * 只活在内存里的**当前值**；落盘由 [PipelineSettingsStore] 负责（SharedPreferences，
 * 全 App 一份）。字段的默认值就是「从没改过 / 磁盘上还没这个 key」时的取值，
 * 所以加字段时改这里一处即可，Store 的 `load()` 会自动跟上。
 */
internal data class PipelineSettings(
    /**
     * 「流式」两帧之间至少间隔多少毫秒；`0` = 不限速，来一帧处理一帧。
     *
     * 流式是相机推帧、处理完立刻拿下一帧，所以默认满速。调大纯粹是为了省电——
     * 扫标签时其实不需要每秒看 5 次。
     */
    val analysisIntervalMs: Long = 0L,

    /**
     * 「降采样」喂给 ML Kit 前，把长边缩到这个像素数。
     *
     * 越小越快、越省内存，但远处的小字可能认不出。默认沿用 [Pipeline.DOWNSCALE_LONG_EDGE]。
     */
    val downscaleLongEdge: Int = Pipeline.DOWNSCALE_LONG_EDGE,

    /**
     * 「降采样」在**自动模式**下每隔多久抓一帧。
     *
     * 和 [analysisIntervalMs] 是两个独立的值，别合并：
     * 那个是「画面来得太快就把多余的丢掉」（`0` = 不限速），
     * 这个是「没人按快门时，多久自己抓一次」（`0` = 用默认的 [AUTO_CAPTURE_INTERVAL_MS]）。
     * 一个的 0 表示「不限制」，另一个的 0 表示「用默认」，语义相反，共用一个字段迟早出错。
     *
     * 手动模式按快门不受它影响。
     */
    val captureIntervalMs: Long = AUTO_CAPTURE_INTERVAL_MS,

    /** 连续推帧时，相册两次保存之间的最小间隔 */
    val gallerySaveIntervalMs: Long = GALLERY_SAVE_INTERVAL_MS,

    /** 只把**有命中**的帧存进相册（没命中的一帧不留），用来压相册体积 */
    val saveHitsOnly: Boolean = false,

    /**
     * 各管线**各自**一组：**条件暂停**——命中这些颜色的项就自动停下来。
     *
     * 字段本身全局一份，但值是按 [Pipeline] 分开存的——和 [captureIntervalMs] 一样：
     * 功能相同、设置互不影响。换条管线调它不会动到别人的值。
     *
     * 空集 = 这条管线不开这个功能（默认全空，要人主动去勾）。
     *
     * ★ 「暂停」在这条管线上的含义由 [Pipeline.usesAnalysis] 决定，不在这里：
     *   流式把 `analysisPaused` 置 true，其余四条把 `autoMode` 置 false。
     *   这里只回答「命中什么颜色要停」，不回答「怎么停」。
     */
    val autoPauseColors: Map<Pipeline, Set<HitColor>> = emptyMap()
)

/** 「流式 · 分析间隔」的可选项（毫秒 → 显示文字）。 */
private val ANALYSIS_INTERVALS = listOf(
    0L to "不限",
    200L to "0.2 秒",
    500L to "0.5 秒",
    1000L to "1 秒"
)

/** 「降采样 · 长边」的可选项（像素 → 显示文字）。 */
private val DOWNSCALE_EDGES = listOf(
    1280 to "1280",
    1600 to "1600",
    2048 to "2048",
    2560 to "2560"
)

/** 「降采样 · 分析间隔」（自动模式抓帧周期）的可选项。 */
private val CAPTURE_INTERVALS = listOf(
    500L to "0.5 秒",
    1000L to "1 秒",
    2000L to "2 秒",
    3000L to "3 秒"
)

/** 相册保存间隔的可选项。 */
private val GALLERY_INTERVALS = listOf(
    1000L to "1 秒",
    2000L to "2 秒",
    3000L to "3 秒",
    5000L to "5 秒"
)

/**
 * 管线设置面板：从底部上拉展开。
 *
 * 入口是**再点一次**底部模式行里已经选中的那条管线——它的标签正上方有个上拉箭头示意。
 * 只有 [Pipeline.configurable] 的管线有面板；面板内容按管线不同：
 * - 流式 → 分析间隔
 * - 降采样 → 长边像素数
 *
 * 后两项（相册保存间隔 / 只保存有命中的帧）跟「连续推帧」有关，两者共用。
 */
@Composable
internal fun PipelineSettingsSheet(
    pipeline: Pipeline,
    settings: PipelineSettings,
    /** 相机输出的短边/长边之比（4:3 竖屏 = 0.75），用来推算缩完之后的尺寸 */
    sourceAspect: Float,
    onChange: (PipelineSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF1A1A1E),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 18.dp)
        ) {
            // 抓手：告诉用户这块是可以拉下来的
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            ) {
                Text(
                    text = "${pipeline.label}设置",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "关闭",
                    color = XiaomiYellow,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // 一句话说清这条管线是干什么的——进面板先知道「它是什么」，再往下才是「能调什么」
            Text(
                text = pipeline.description,
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )

            // ---------- 第一个设置：间隔（5 条管线都有）----------
            //
            // ★ 名字按驱动方式分：
            //   流式是相机自己推帧，调的是「来得太快就丢掉」→ **分析间隔**
            //   其余四条是快门/定时器驱动，调的是「多久自己抓一帧」→ **抓帧间隔**
            //   同一个名字会让人以为调的是同一件事，所以分开叫。
            if (pipeline.usesAnalysis) {
                SettingSection(
                    title = "分析间隔",
                    hint = "两帧之间至少等这么久。也可以直接在右边填毫秒数；不限 = 来一帧处理一帧",
                    trailing = {
                        NumberInput(
                            value = settings.analysisIntervalMs.toInt(),
                            placeholder = "自填",
                            onValueChange = { onChange(settings.copy(analysisIntervalMs = it.toLong())) }
                        )
                        UnitLabel("毫秒")
                    }
                ) {
                    OptionChips(
                        options = ANALYSIS_INTERVALS,
                        selected = settings.analysisIntervalMs,
                        onSelect = { onChange(settings.copy(analysisIntervalMs = it)) }
                    )
                }
            } else {
                SettingSection(
                    title = "抓帧间隔",
                    hint = "自动模式下每隔这么久抓一帧；手动模式按快门，不受这个影响",
                    trailing = {
                        NumberInput(
                            value = settings.captureIntervalMs.toInt(),
                            placeholder = "自填",
                            onValueChange = { onChange(settings.copy(captureIntervalMs = it.toLong())) }
                        )
                        UnitLabel("毫秒")
                    }
                ) {
                    OptionChips(
                        options = CAPTURE_INTERVALS,
                        selected = settings.captureIntervalMs,
                        onSelect = { onChange(settings.copy(captureIntervalMs = it)) }
                    )
                }
            }

            // ---------- 条件暂停：命中指定颜色就停（5 条管线都有，各自独立）----------
            //
            // 刻意做成**一行**而不是 SettingSection：面板已经有 4 节，多一节要多占
            // 近 90dp，小屏上就要滚了。这里把说明压成一行小字，右侧直接放三个色块，
            // 整块只占约 58dp。
            AutoPauseColorRow(
                selected = settings.autoPauseColors[pipeline].orEmpty(),
                onToggle = { color ->
                    val next = settings.autoPauseColors[pipeline].orEmpty()
                        .let { if (color in it) it - color else it + color }
                    onChange(settings.copy(autoPauseColors = settings.autoPauseColors + (pipeline to next)))
                }
            )

            // ---------- 管线自己的那些设置 ----------
            when (pipeline) {
                // 相册那两项只在流式里出现：它由相机连续推帧，不节流相册几秒钟就写满。
                // 另外四条是快门一帧一帧，每次按快门本来就该留一张，
                // 那两项对它们没有意义（保存间隔无从谈起、「只存命中」还会把
                // 手动拍的没命中的照片吃掉）。
                Pipeline.Analysis -> {
                    SettingSection(
                        title = "相册保存间隔",
                        hint = "连续推帧时最多隔这么久存一张，免得相册几秒钟就写满"
                    ) {
                        OptionChips(
                            options = GALLERY_INTERVALS,
                            selected = settings.gallerySaveIntervalMs,
                            onSelect = { onChange(settings.copy(gallerySaveIntervalMs = it)) }
                        )
                    }

                    SettingSection(
                        title = "只保存有命中的帧",
                        hint = "打开后，一行都没对上的帧不进相册"
                    ) {
                        OptionChips(
                            options = listOf(false to "关", true to "开"),
                            selected = settings.saveHitsOnly,
                            onSelect = { onChange(settings.copy(saveHitsOnly = it)) }
                        )
                    }
                }

                Pipeline.Downscaled -> SettingSection(
                    title = "长边像素",
                    hint = "喂给识别前先把图缩到这个尺寸。越小越快、越省内存，但远处的小字可能认不出",
                    trailing = {
                        NumberInput(
                            value = settings.downscaleLongEdge,
                            placeholder = "自填",
                            onValueChange = { onChange(settings.copy(downscaleLongEdge = it)) }
                        )
                        UnitLabel("px")
                    },
                    footer = { DownscaleSummary(longEdge = settings.downscaleLongEdge, aspect = sourceAspect) }
                ) {
                    OptionChips(
                        options = DOWNSCALE_EDGES,
                        selected = settings.downscaleLongEdge,
                        onSelect = { onChange(settings.copy(downscaleLongEdge = it)) }
                    )
                }

                // 基础 / 省内存 / 小型图：只有上面那个「抓帧间隔」
                else -> Unit
            }
        }
    }
}

/** 设置面板里的一节：标题（右侧可挂控件）+ 说明 + 内容 + 尾注。 */
@Composable
private fun SettingSection(
    title: String,
    hint: String,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            // ★ 这里的容器必须是 Row：trailing 里通常有两个 composable
            //   （输入框 + 单位文字），放进 Box 会**叠在一起**，单位就跑到框上去了。
            //   位置也紧挨着标题，不推到屏幕右边——离太远看不出属于哪一项。
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp)
                ) { trailing() }
            }
        }
        Text(
            text = hint,
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        Box(modifier = Modifier.padding(top = 10.dp)) { content() }
        if (footer != null) {
            Box(modifier = Modifier.padding(top = 8.dp)) { footer() }
        }
    }
}

/** 紧跟数字输入框后面的单位文字。 */
@Composable
private fun UnitLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.45f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 6.dp)
    )
}

/** 最大公约数，用来把「1536 : 2048」约成「3 : 4」。 */
private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/**
 * 「降采样」下面那行自动算出来的信息。
 *
 * 使用者只需要定长边，短边是按**相机实际输出的比例**推出来的——比例是设备/传感器的
 * 属性，不该让人再填一遍。像素数和比例都跟着长边一起变，所以放在这里实时显示，
 * 让人一眼看到「长边调小之后到底缩成多大」。
 */
@Composable
private fun DownscaleSummary(longEdge: Int, aspect: Float) {
    val long = longEdge.coerceAtLeast(1)
    val short = (long * aspect).roundToInt().coerceIn(1, long)
    val megapixels = long.toLong() * short / 1_000_000f
    val divisor = gcd(long, short)
    val ratio = "${short / divisor} : ${long / divisor}"

    Text(
        text = "短边 $short px · %.1f MP · 比例 %s".format(megapixels, ratio),
        color = XiaomiYellow.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelSmall
    )
}


/**
 * 标题右边那个自填数字的框（分析间隔的毫秒数、降采样长边的像素数都用它）。
 *
 * 和左边的预设标签是**并联**关系，不是替代：点标签会把这个框填上，
 * 自己打字则会让标签全部取消选中（因为值不在预设里）。
 * 清空 = `0`。
 *
 * 用 [BasicTextField] 而不是 `OutlinedTextField`：后者的最小高度是 56dp，
 * 放在标题行右边会把整行撑高，和面板其它地方不协调。
 */
@Composable
private fun NumberInput(
    value: Int,
    placeholder: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 0 显示成空，而不是一个「0」
    val text = if (value == 0) "" else value.toString()
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            // 只收数字，最多 5 位；清空当作 0
            val digits = raw.filter { it.isDigit() }.take(5)
            onValueChange(digits.toIntOrNull() ?: 0)
        },
        singleLine = true,
        // textAlign 居中：默认是紧靠左边，填个「300」会贴在框的左沿上很难看
        textStyle = MaterialTheme.typography.labelMedium.copy(
            color = Color.White,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(XiaomiYellow),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.width(58.dp),
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.10f))
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                inner()
            }
        }
    )
}

/**
 * 一组单选标签。
 *
 * 刻意不用 `horizontalScroll`：选项都挑过，数量固定、放得下；
 * 能滑动反而会让人以为还有别的选项藏在右边。
 */
@Composable
private fun <T> OptionChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            val on = value == selected
            Text(
                text = text,
                color = if (on) Color.Black else Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) XiaomiYellow else Color.White.copy(alpha = 0.12f))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * 「条件暂停」那一行：左边标题 + 说明，右边三个可多选的色块 + 各自的名字。
 *
 * 勾中的颜色一旦在画面里出现，就自动停下来（流式 = 暂停，其余 = 转手动）。
 * **空集 = 不开这个功能**，所以默认全不勾，不另设开关。
 *
 * ★ 说明文字里那句「设置互不影响」是刻意的：值按管线分开存，
 *   用户在流式里勾了什么，换到降采样看到的还是原样。
 */
@Composable
private fun AutoPauseColorRow(
    selected: Set<HitColor>,
    onToggle: (HitColor) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        // 三行：标题 → 说明 → 选项。说明单独占一行、不跟标题挤在同一行，
        // 是因为它比标题长得多；并排时右边那截要么换行、要么把色块推出去。
        Text(
            text = "条件暂停",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "命中即停，每条模式各自设置",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp)
        )

        // 选项行：三个色块，右侧跟一句图例。图例写全「绿 = 精确 / 黄 = 模糊 /
        // 蓝 = 已标记」，因为光看色环只认得出颜色、认不出它对应什么命中。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HitColor.entries.forEach { kind ->
                    HitColorChip(
                        kind = kind,
                        on = kind in selected,
                        onClick = { onToggle(kind) }
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HitColor.entries.forEach { kind ->
                    Text(
                        text = kind.explain,
                        color = kind.color.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * 一个可勾选的色块。
 *
 * 两种状态：**都带对应颜色的环**，区别只在里面填没填。
 * - 选中：整块填该颜色 + 对勾（勾的黑白由 [HitColor.checkMarkColor] 决定）
 * - 未选中：只有一圈该颜色的环，中间是空的
 *
 * ★ 环的颜色**不是白色**：三个白圈看上去一模一样，得靠下面的文字才知道哪个是哪个；
 *   用各自的颜色，色环本身就是图例。
 * ★ 触摸区 [CHIP_TOUCH_SIZE] 比色块 [CHIP_DOT_SIZE] 大一圈：色块本身太小，
 *   按 18dp 算命中区的话很难点中。多出来的部分靠 `contentAlignment` 居中，
 *   视觉位置不受影响。
 */
@Composable
private fun HitColorChip(kind: HitColor, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CHIP_TOUCH_SIZE)
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                onClickLabel = if (on) "取消${kind.label}色" else "勾选${kind.label}色"
            )
    ) {
        Canvas(modifier = Modifier.size(CHIP_DOT_SIZE)) {
            val radius = size.minDimension / 2f
            val stroke = CHIP_RING_WIDTH.toPx()
            if (on) {
                drawCircle(color = kind.color, radius = radius)
            } else {
                // 描边压在圆的内侧，不然会被 size() 的边界裁掉半个像素
                drawCircle(
                    color = kind.color.copy(alpha = 0.75f),
                    radius = radius - stroke / 2f,
                    style = Stroke(width = stroke)
                )
            }
        }
        if (on) {
            // 对勾画在 Canvas 外面、用同一个尺寸的叠层，这样不用在 drawScope 里
            // 手算路径——两个 Box 都是 CHIP_DOT_SIZE 且居中，坐标天然对齐
            CheckMark(color = kind.checkMarkColor)
        }
    }
}

/** 色块直径 */
private val CHIP_DOT_SIZE = 18.dp

/**
 * 色块触摸区直径。
 *
 * ★ 这个值同时决定**色块的视觉位置**：圆只有 18dp，靠 `contentAlignment` 坐在
 *   这个方盒子的正中，所以盒子比圆大多少，圆的行内重心就被压低多少。
 *   取 32dp 的话圆心比右侧图例文字低约 5dp，一眼就看得出歪；收到 26dp 后只差约 2dp。
 *   再往下（24dp）视觉更准，但那已经小于舒适点击区了——宁可留 2dp 的偏差。
 */
private val CHIP_TOUCH_SIZE = 26.dp

/** 未选中时那圈彩色环的宽度 */
private val CHIP_RING_WIDTH = 1.5.dp

/** 一个「✓」，自绘。用画的而不是打 `✓`：字体里有没有这个字形没法保证。 */
@Composable
private fun CheckMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(CHIP_DOT_SIZE)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawLine(
            color = color,
            start = Offset(w * 0.28f, h * 0.52f),
            end = Offset(w * 0.44f, h * 0.68f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.44f, h * 0.68f),
            end = Offset(w * 0.72f, h * 0.34f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
