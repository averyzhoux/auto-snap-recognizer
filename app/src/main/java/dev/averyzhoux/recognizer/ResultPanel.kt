package dev.averyzhoux.recognizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 顶部状态胶囊。 */
@Composable
internal fun StatusBanner(status: OcrStatus, modifier: Modifier = Modifier) {
    val text = when (status) {
        OcrStatus.Idle -> "对准目标，等待识别"
        is OcrStatus.Recognizing -> {
            val cached = status.cached
            if (cached == null) "正在识别文字…"
            else "命中 ${cached.count { it.isHit }} / ${cached.size}（更新中…）"
        }
        is OcrStatus.Recognized -> when {
            // 引擎出错和「图里没字」是两回事，提示必须分开
            status.ocrFailed -> "OCR 识别失败"
            status.matches.isEmpty() -> "没识别到文字"
            status.hitCount > 0 ->
                "命中 ${status.hitCount} / ${status.matches.size}" +
                    if (status.fromCache) "（画面未变）" else ""
            // 刻意写短：胶囊越窄，顶部越不容易挤
            else -> "无命中 · ${status.matches.size} 行"
        }
        is OcrStatus.Failed -> "失败：${status.message}"
    }
    // ★ 刻意**不加胶囊底色**：只让文字浮在取景画面上。
    //   代价是遇到亮背景（白纸、亮天花板）白字会看不清——
    //   真遇上了给文字加一层阴影，别把背景加回来。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(vertical = 8.dp)
    ) {
        if (status is OcrStatus.Recognizing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            // 被 Row 的 weight 压缩时省略，而不是溢出盖住右侧的数据集入口
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 比对结果面板：逐行显示，命中的行打勾并标出命中的表格项。
 *
 * [continuous] 表示相机在持续推帧（自动模式 / 流式），此时「重拍」没有意义。
 *
 * [onToggleMark] 不为 null 时，命中的行尾多一个「确认标记」小方框（开关语义）。
 * ★ 默认 null = 不显示按钮，所以相册（详情 / 网格）的两个调用点一个字都不用改，
 *   那里天然不会出现这个按钮。
 */
@Composable
internal fun ResultPanel(
    matches: List<LineMatch>,
    ocrFailed: Boolean,
    continuous: Boolean,
    onRetake: () -> Unit,
    onToggleMark: ((Entry) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hitCount = matches.count { it.isHit }
    // 「命中 X / Y」里那个 X 按**显示颜色**拆开：这次命中里绿/黄/蓝各有几条。
    // key 为 null 的是未命中的行，下面按颜色取的时候自然被忽略。
    val colorCounts = matches.groupingBy { hitColorOf(it) }.eachCount()
    Surface(
        color = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(20.dp),
        // 面板整体高度必须封顶：识别行数多的时候不能无限长，
        // 否则会盖住底部快门栏
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "命中 ",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "$hitCount",
                    color = XiaomiYellow,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = " / ${matches.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleSmall
                )

                // 命中的构成：黄/蓝/绿各几条。紧跟在「命中 X / Y」右边，
                // 所以那个 X 一眼就能拆开看（三组数字之和正好等于 X）。
                HitColorCounts(
                    counts = colorCounts,
                    modifier = Modifier.padding(start = 10.dp)
                )

                // 「重拍」放在这一行最右侧，而不是单占底部一行——
                // 它本来就只有两个小字，单开一行白占高度。
                // 持续推帧（自动 / 流式）时它没有意义，所以不显示。
                if (!continuous) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "重拍",
                        color = XiaomiYellow,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onRetake)
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }

            if (matches.isEmpty()) {
                Text(
                    text = if (ocrFailed) {
                        "OCR 识别失败，请重试"
                    } else {
                        "没有识别到文字，试试靠近一点、让文字占满画面"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                // weight(1f) 让列表吃掉标题之外的全部空间，超出部分内部滚动
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    matches.forEach { match ->
                        MatchRow(match = match, onToggleMark = onToggleMark)
                    }
                }
            }
        }
    }
}

/**
 * 「命中 X / Y」右边那几组「颜色点 + 数字」：这次命中里各颜色各有几条。
 *
 * 例：黄 1 条、绿 1 条 → `● 1  ● 1`（两个点分别是黄和绿）。
 *
 * ★ 点数**不随数量增加**，一个颜色就一个点，数量由后面的数字表达——
 *   所以这里宽度是固定的（最多三组），不会因为命中 11 条就把这一行撑爆。
 *
 * ★ 顺序是需求指定的 **黄 → 蓝 → 绿**，和 [HitColor] 的枚举顺序、以及「条件暂停」
 *   那排提示点的顺序（绿黄蓝）**都不一样**。看着别扭也别顺手「修正」成枚举顺序。
 *
 * 数量为 0 的颜色整组不画（没命中的颜色不需要占位）。
 */
@Composable
private fun HitColorCounts(counts: Map<HitColor?, Int>, modifier: Modifier = Modifier) {
    val ordered = HIT_COUNT_ORDER.mapNotNull { kind ->
        counts[kind]?.takeIf { it > 0 }?.let { kind to it }
    }
    if (ordered.isEmpty()) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        ordered.forEach { (kind, count) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 点和它自己的数字贴紧，组和组之间才拉开（就是上面那个 8dp）
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(HIT_COUNT_DOT)
                        .clip(CircleShape)
                        .background(kind.color)
                )
                Text(
                    text = "$count",
                    // 数字和点同色：一眼能看出这一对是一体的，也对得上结果行里的颜色
                    color = kind.color,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/** 「命中 X / Y」右边那几组的显示顺序：需求指定，不是枚举顺序 */
private val HIT_COUNT_ORDER = listOf(HitColor.Yellow, HitColor.Blue, HitColor.Green)

/** 颜色点直径（= 半径 × 2）。刻意画得很小，只当颜色标记，不抢戏 */
private val HIT_COUNT_DOT = 3.dp

/**
 * 单行的比对结果：命中 = 高亮 + 打勾，未命中 = 灰显。整条**只占一行**。
 *
 * [onToggleMark] 不为 null 且这一行命中时，行尾多一个「确认标记」小方框；
 * 未命中的行没有表格项可标记，所以不给按钮。相册不传这个参数，那里不会出现按钮。
 */
@Composable
internal fun MatchRow(
    match: LineMatch,
    onToggleMark: ((Entry) -> Unit)? = null
) {
    // 完全相同 / 包含 → 绿，模糊 → 黄，未命中 → 灰
    val color = matchColor(match)
    val entry = match.entry
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color?.copy(alpha = 0.16f) ?: Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (match.isHit) "✓" else "·",
            color = color ?: Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.bodyMedium
        )
        // ★ 一行显示：识别文字和命中说明挤在同一行，不换行。
        //   两段都给 weight(fill = false)：谁短就按需取宽，谁长就在**自己那份**里省略号，
        //   不会出现「一段把另一段挤没」。两段加起来还放不下时各自收窄，仍然保持一行。
        //
        //   外面再套一层 weight(1f) 的 Row，是为了把行尾的按钮**顶到最右**：
        //   两段文字都是 fill = false、剩多少才占多少，不套这一层的话按钮会紧跟在
        //   文字后面，每行的位置都不一样。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = match.rawText,
                color = if (match.isHit) Color.White else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (match.isHit) {
                Text(
                    text = "→ " + entry?.name + match.noteText(),
                    color = color ?: XiaomiYellow,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        // 行尾的确认标记。★ 它改的是**数据集里那一项**（[Entry.marked]），不是这一帧的
        // 临时状态，所以和数据集编辑页是同一个开关。
        if (entry != null && onToggleMark != null) {
            MarkToggle(
                marked = entry.marked,
                onClick = { onToggleMark(entry) },
                size = 20.dp
            )
        }
    }
}

/**
 * 命中之后跟在表格项后面的那句说明。
 *
 * 用**命中种类**决定文案，不是用相似度：
 * - 包含 → `（包含）`。它现在是绿色、和「完全相同」长得一样，这句是唯一能看出
 *   「为什么算命中」的线索
 * - 模糊 → `（近似 83%）`，这个数字是真编辑距离换算出来的相似度
 * - 完全相同 → 不加说明，一眼就是对的
 */
private fun LineMatch.noteText(): String = when (kind) {
    MatchKind.Contains -> "（包含）"
    MatchKind.Fuzzy -> "（近似 " + "%.0f%%".format(similarity * 100) + "）"
    MatchKind.Exact, MatchKind.None -> ""
}
