package dev.averyzhoux.recognizer

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.Color
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
 * 模式切换：和左侧缩略图对称的一个圆形按钮，点一下在「自动 / 手动」之间切换。
 *
 * 选中态用小米相机的黄色 + 一圈黄色描边表示，一眼能看出当前是哪种模式。
 * 流式分析下由相机连续推帧，这个开关不适用：[enabled] 传 false，
 * 按钮灰掉、显示「连续」且不可点。
 */
@Composable
internal fun ModeSwitch(
    autoMode: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when {
        !enabled -> "连续"
        autoMode -> "自动"
        else -> "手动"
    }
    // 「连续」是一种被强制的模式，也算选中态，用黄色表示
    val active = !enabled || autoMode

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
            .clickable(enabled = enabled) { onToggle(!autoMode) }
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
 * [externallyDriven] 为 true 时（自动模式 / 流式分析）按钮不参与点击，
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
            Text(
                text = item.label,
                textAlign = TextAlign.Center,
                color = if (selected) XiaomiYellow else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier
                    // 五等分，横向内边距交给 weight 分配，所以这里不再写 padding(horizontal)
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelect(item) }
                    .padding(vertical = 8.dp)
            )
        }
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
 * 格式就是**把 BuildConfig 的两个字段直接拼起来**：`Recognizer<versionCode> <versionName>`，
 * 比如 `Recognizer1 v0.1.0dev2`。
 *
 * 刻意不加也不去掉任何前缀：`versionName` 里带不带 `v` 由 `app/build.gradle.kts`
 * 的 `defaultConfig` 决定（目前本地默认是 `v0.1.0dev2`，带 v），这里原样显示，
 * 免得出现「代码里加一个 v、配置里又有一个 v」拼成 `vv...` 这种事。
 *
 * 值来自 [BuildConfig]，而 BuildConfig 又是 `defaultConfig` 生成的：
 * 本地直接跑用默认值，CI 发布时由 `.github/workflows/release.yml` 从 git tag 注入。
 *
 * 放这一行的意义是**出问题时一眼能确认手机上装的到底是哪个构建**——
 * 覆盖安装过好几个版本之后，光看界面是分不出来的。
 *
 * ★ 调用方把它 **`align(BottomCenter)` 且不避让 `navigationBars`**，所以它会落在
 * 系统导航栏那条带里、比 `☰ ◻ ◁` 还低，并在「导航键下沿 → 屏幕底边」这段里上下居中。
 * 换到**手势导航**时底部中间是那颗胶囊，会撞上——那时应该改成避让 insets。
 *
 * 字号比 [MaterialTheme.typography.labelSmall]（11sp）再小一号：11sp 已经是 Material3
 * 里最小的一档了，所以手动压 `fontSize`，并把 `lineHeight` 一起压下来——
 * 行盒高度直接决定下面那个「上下居中」要垫多少 dp，不同步改会算错。
 *
 * 颜色很淡（35% 白）：它只是兜底信息，不该抢取景画面的注意力。
 */
@Composable
internal fun VersionLabel(modifier: Modifier = Modifier) {
    Text(
        text = "Recognizer${BuildConfig.VERSION_CODE} ${BuildConfig.VERSION_NAME}",
        color = Color.White.copy(alpha = 0.35f),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 14.sp
        ),
        maxLines = 1,
        modifier = modifier
    )
}
