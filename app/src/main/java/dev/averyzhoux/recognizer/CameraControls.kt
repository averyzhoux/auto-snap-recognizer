package dev.averyzhoux.recognizer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 快门按钮的直径 */
internal val SHUTTER_SIZE = 76.dp

/** 缩略图槽位尺寸，左右各留一个保证快门在视觉上居中 */
internal val THUMBNAIL_SLOT = 56.dp
/**
 * 右下角那个圆形开关，和左侧缩略图对称。
 *
 * 它管什么由调用方决定，所以这里只收「显示什么字 / 算不算选中态 / 点了干什么」：
 * - 普通管线 → 切换**自动 / 手动**
 * - 流式 → 切换**暂停 / 继续**（相机一直在推帧，需要一个停下来的开关）
 *
 * 选中态用小米相机的黄色 + 一圈黄色描边表示。
 */
@Composable
internal fun ModeSwitch(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(THUMBNAIL_SLOT)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(
                width = 1.5.dp,
                color = if (active) XiaomiYellow else Color.White.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (active) XiaomiYellow else Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * 小米相机式快门按钮：白色圆环 + 白色内圆。
 *
 * [externallyDriven] 为 true 时（自动模式 / 流式）按钮不参与点击，
 * 由相机那边驱动，半透明表示不可点；识别中时内圆变成一个小方块/进度指示。
 */
@Composable
internal fun ShutterButton(
    status: OcrStatus,
    enabled: Boolean,
    externallyDriven: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = status is OcrStatus.Recognizing
    val clickable = enabled && !busy && !externallyDriven
    // 外部驱动时按钮只做状态指示，所以调暗
    val contentAlpha = if (externallyDriven) 0.35f else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SHUTTER_SIZE)
            .clip(CircleShape)
            .clickable(enabled = clickable, onClick = onClick)
    ) {
        // 外圈（不额外加 padding，否则会被父容器裁掉）
        Box(
            modifier = Modifier
                .size(SHUTTER_SIZE)
                .border(3.dp, Color.White.copy(alpha = contentAlpha), CircleShape)
        )
        // 内圆
        Box(
            modifier = Modifier
                .size(if (busy) 30.dp else 60.dp)
                .clip(if (busy) RoundedCornerShape(8.dp) else CircleShape)
                .background(
                    if (busy) Color.White.copy(alpha = 0.9f)
                    else Color.White.copy(alpha = contentAlpha)
                )
        )
        if (busy) {
            CircularProgressIndicator(
                color = Color.Black.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 识别模式选择行，位置参照相机 App 的「专业 / 录像 / 人像」那一排。
 *
 * 5 个选项**等分整屏宽度**（`weight(1f)`），文字居中，不横向滚动：
 * 滚动条会让最右那项被切掉、还得手动拖，一眼看不全有哪些模式。
 * 用 weight 而不是按内容宽度排：无论标签多长、系统字号多大，
 * 这一排都恰好铺满、永不溢出，不需要横向拖动。
 *
 * 任意一项被选中时，标签**正上方**会多一个自绘的上拉箭头：再点一次这一项会从底部
 * 展开这条管线的设置（分析间隔等），箭头是给这个隐藏操作的提示。
 * 箭头是画出来的，不是字符 `⌃`——字体里有没有那个字形完全没法保证。
 */
@Composable
internal fun PipelineModeRow(
    current: Pipeline,
    onSelect: (Pipeline) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 只留一点点缝，让相邻两项的点击态（圆角背景）不贴在一起
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // 用 for 而不是 entries.forEach：forEach 的 lambda 收不到 RowScope，
        // 里面的 Modifier.weight(1f) 会解析不到
        for (item in Pipeline.entries) {
            val selected = item == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelect(item) }
                    .padding(vertical = 8.dp)
            ) {
                // ★ 每一项都留出同样高度的箭头槽，只有被选中的那条才填内容。
                //   不这么做的话，只有它顶上多一截，文字会被挤下去、和旁边几项错位。
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(CHEVRON_SLOT)
                ) {
                    if (selected) {
                        UpChevron(color = XiaomiYellow)
                    }
                }
                Text(
                    text = item.label,
                    textAlign = TextAlign.Center,
                    color = if (selected) XiaomiYellow else Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
            }
        }
    }
}

/** 箭头槽的高度：给上拉箭头留的位置，所有模式项都留同样的高 */
private val CHEVRON_SLOT = 4.dp

/**
 * 手电筒开关：取景页左上角、状态文字左边那个闪电。
 *
 * 只有亮/灭两种样子：亮着是小米黄，灭着是半透明白。
 * 刻意和顶部另外两个控件一样**不加胶囊底色**，只让图标浮在取景画面上。
 *
 * ★ 触摸区是 40dp、图案 [TorchIcon] 只有 20dp 且居中，所以图案左右各垫了 10dp，
 *   再加上图案本身在 20dp 里内缩 3.75dp——**光是这个按钮就让两边各占 13.75dp**。
 *   外层想摆平闪电左右的留白时，要按 `13.75 + 两侧各自的间距` 算，
 *   不是按 0 算：两边留白相等的条件是**「屏幕边距」=「闪电到下一个控件的间隙」**。
 */
@Composable
internal fun TorchButton(
    on: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // 图标本身只有 20dp，撑到 40dp 才好点
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onToggle)
    ) {
        TorchIcon(
            color = when {
                !enabled -> Color.White.copy(alpha = 0.25f)
                on -> XiaomiYellow
                else -> Color.White.copy(alpha = 0.75f)
            }
        )
    }
}

/**
 * 闪电图案，自绘。
 *
 * 用画的而不是打 `⚡`：那个码位在多数系统上会走**彩色 emoji 字体**，
 * 渲染出来是一张黄红渐变的小贴纸，跟旁边纯文字的观感完全不搭；
 * 而且字体里有没有它也没法保证（`⌃` 那次就吃过这个亏）。
 */
@Composable
private fun TorchIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val s = size.minDimension / 24f
        val path = Path().apply {
            // 24×24 视口里的闪电轮廓，全部直线段
            moveTo(13f * s, 2f * s)
            lineTo(4.5f * s, 13.5f * s)
            lineTo(10.5f * s, 13.5f * s)
            lineTo(10f * s, 22f * s)
            lineTo(19.5f * s, 10.5f * s)
            lineTo(13f * s, 10.5f * s)
            close()
        }
        drawPath(path = path, color = color)
    }
}

/**
 * 上拉箭头（一个「⌃」形状的雪佛龙），自绘。
 *
 * 用画的而不是打 `⌃` / `▲` 这类字符：字体里有没有那个字形没法保证，
 * 缺字形的时候会显示成豆腐块，比没有还难看。
 *
 * 尺寸调过两轮（12×6 → 8×4 → 5.5×3）。这么小的时候**线宽不能按比例跟着缩**：
 * 0.7dp 在高密度屏上只剩 2px，抗锯齿之后会糊成一团灰，所以停在 0.8dp。
 */
@Composable
private fun UpChevron(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 5.5.dp, height = 3.dp)) {
        val stroke = 0.8.dp.toPx()
        // 左右两笔在顶点交汇，端点磨圆，看起来才像相机里的那种箭头
        drawLine(
            color = color,
            start = Offset(0f, size.height),
            end = Offset(size.width / 2f, 0f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/** 左下角最近一帧缩略图，点一下进内部相册。 */
@Composable
internal fun Thumbnail(
    bitmap: Bitmap,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "打开内部相册",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(THUMBNAIL_SLOT)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    )
}
@Composable
internal fun PermissionRationale(
    askAgain: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (askAgain) "需要相机权限才能预览" else "相机权限已被拒绝，请到系统设置里手动开启",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp)
        ) {
            if (askAgain) {
                Button(onClick = onRequest) { Text("授予权限") }
            } else {
                Button(onClick = onOpenSettings) { Text("打开设置") }
            }
        }
    }
}

/**
 * 压在**屏幕最底边**的版本号，小字 + 半透明。
 *
 * 格式：`Recognizer<versionCode> <versionName>`，例如 `Recognizer300 v0.3.0-Hephaestus`。
 * 两个都显示是因为它们回答的是**两个不同的问题**：
 * - `versionCode`（300）是系统判断新旧用的那个整数，也是装机时真正决定能否覆盖安装的值
 * - `versionName`（v0.3.0-Hephaestus）是人认得出的「哪一版」
 *
 * 两个值都来自 [BuildConfig]，而 BuildConfig 由 `app/build.gradle.kts` 的 `defaultConfig`
 * 生成。**那是唯一的来源**：CI 不从 tag 推、也不注入（见 `tasks/27`）。
 * 这里不加也不去掉任何前缀，`versionName` 写成什么样就显示成什么样。
 *
 * 放这一行的意义是**出问题时一眼能确认手机上装的到底是哪个构建**——
 * 覆盖安装过好几个版本之后，光看界面是分不出来的。
 *
 * ★ 调用方把它 **`align(BottomCenter)` 且不避让 `navigationBars`**，所以它会落在
 * 系统导航栏那条带里、比 `☰ ◻ ◁` 还低，并在「导航键下沿 → 屏幕底边」这段里上下居中。
 * 换到**手势导航**时底部中间是那颗胶囊，会撞上——那时应该改成避让 insets。
 *
 * 字号比 [MaterialTheme.typography.labelSmall]（11sp）小两号：11sp 已经是 Material3
 * 里最小的一档了，所以手动压 `fontSize`，并把 `lineHeight` 一起压下来——
 * 行盒高度直接决定下面那个「上下居中」要垫多少 dp，**不同步改会算错**。
 * （下面调用方垫的 2.5dp 就是按 13sp 行盒算的：中心 ≈ 2.5 + 13/2 - 1.2 ≈ 7.8dp，
 * 目标是「导航键下沿 → 屏幕底」的中点 8.15dp。）
 *
 * 颜色很淡（35% 白）：它只是兜底信息，不该抢取景画面的注意力。
 */
@Composable
internal fun VersionLabel(modifier: Modifier = Modifier) {
    Text(
        text = "Recognizer${BuildConfig.VERSION_CODE} ${BuildConfig.VERSION_NAME}",
        color = Color.White.copy(alpha = 0.35f),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 13.sp
        ),
        maxLines = 1,
        modifier = modifier
    )
}
