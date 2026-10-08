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
