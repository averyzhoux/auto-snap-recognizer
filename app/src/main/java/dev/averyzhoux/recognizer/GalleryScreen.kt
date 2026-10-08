package dev.averyzhoux.recognizer

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/**
 * 内部相册：两列网格，每格显示缩略图 + 识别结果摘要。
 *
 * 数据全在内存里（[GalleryItem] 只存缩略图），不落盘，退出应用即清空。
 */
@Composable
internal fun GalleryScreen(
    items: List<GalleryItem>,
    thumbnailCache: LruCache<String, Bitmap>,
    onClose: () -> Unit,
    onOpen: (GalleryItem) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 系统返回键也用来关相册
    BackHandler(onBack = onClose)

    Surface(color = Color(0xFF101012), modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "‹ 返回",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "相册 ${items.size} 张",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "清空",
                    color = if (items.isEmpty()) Color.White.copy(alpha = 0.3f) else XiaomiYellow,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = items.isNotEmpty(), onClick = onClear)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "还没有照片\n拍一张就会出现在这里",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // 最新的排最前
                    items(items, key = { it.id }) { item ->
                        GalleryCard(
                            item = item,
                            thumbnailCache = thumbnailCache,
                            onClick = { onOpen(item) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 纵览卡片里每格最多显示几行识别结果（分界线上下各算一次）。
 */
internal const val CARD_ROWS = 3

/**
 * 相册里的分界线：把「命中的结果」和「其余识别结果」分开。
 */
@Composable
internal fun HitDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.18f),
        thickness = 1.dp,
        modifier = modifier.padding(vertical = 6.dp)
    )
}

/**
 * 纵览卡片里的一行识别结果。
 *
 * [hit] 为 true 时用小米黄强调，并用 [note] 标出命中的是表格里的哪一项。
 */
@Composable
internal fun CardLine(text: String, color: Color? = null, note: String? = null) {
    val hit = color != null
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = if (hit) "✓ $text" else text,
            color = color ?: Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (note != null) {
            Text(
                text = "→ $note",
                color = (color ?: XiaomiYellow).copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 相册里的一格：缩略图 + 命中情况 + 识别到的前几行文字。点开看详情。 */
@Composable
internal fun GalleryCard(
    item: GalleryItem,
    thumbnailCache: LruCache<String, Bitmap>,
    onClick: () -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column {
            val preview = rememberGalleryBitmap(item, thumbnailCache)
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "第 ${item.id} 张",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            } else {
                ImagePlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${item.id}",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timeMillis))}",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Text(
                    text = "命中 ${item.hitCount} / ${item.lineCount}",
                    color = if (item.hitCount > 0) XiaomiYellow else Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (item.matches.isEmpty()) {
                    Text(
                        text = "没识别到文字",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    // 分界线之上：命中结果的**副本**，方便一眼看到重点
                    val hits = item.matches.filter { it.isHit }
                    if (hits.isNotEmpty()) {
                        hits.take(CARD_ROWS).forEach { match ->
                            CardLine(
                                text = match.rawText,
                                color = matchColor(match),
                                note = match.entry?.name
                            )
                        }
                        HitDivider()
                    }

                    // 分界线之下：照常列出全部识别结果（命中的也在里面）
                    item.lines.take(CARD_ROWS).forEach { line ->
                        CardLine(text = line)
                    }
                }
            }
        }
    }
}

/**
 * 按需解码一张相册预览图。
 *
 * - 先查 [cache]，命中就直接返回（滚动/来回切不会反复解码）
 * - 没命中就丢到 IO 线程解码，期间返回 null（调用方显示占位）
 * - 图片在磁盘上，所以**内存里只会有最近看过的几张**
 */
@Composable
internal fun rememberGalleryBitmap(
    item: GalleryItem,
    cache: LruCache<String, Bitmap>
): Bitmap? {
    val path = item.imageFile.path
    var bitmap by remember(path) { mutableStateOf(cache.get(path)) }

    LaunchedEffect(path) {
        if (bitmap == null && item.imageFile.exists()) {
            val decoded = withContext(Dispatchers.IO) { decodePreview(item.imageFile) }
            if (decoded != null) {
                cache.put(path, decoded)
                bitmap = decoded
            }
        }
    }
    return bitmap
}

/** 解码还没完成时的占位块，避免布局跳动。 */
@Composable
internal fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "载入中…",
            color = Color.White.copy(alpha = 0.3f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 相册里某一张的详情：大图 + **完整的**识别结果。
 *
 * 和拍摄页一样用 [MatchRow] 逐行展示，命中的行高亮打勾并标出命中的表格项，
 * 区别只是这里的图是当时存下来的预览图，且文字区可以整屏滚动。
 */
@Composable
internal fun GalleryDetailScreen(
    item: GalleryItem,
    thumbnailCache: LruCache<String, Bitmap>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    Surface(color = Color(0xFF101012), modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "‹ 相册",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "#${item.id}  " +
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timeMillis)),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp)
            ) {
                // 大图。磁盘上的预览图（480px）按需解码。
                // 用 aspectRatio 让图片框贴合图片本身的比例，
                // 否则框会很宽很矮，ContentScale.Fit 只按高度缩放，图会变得很小。
                val detailBitmap = rememberGalleryBitmap(item, thumbnailCache)
                if (detailBitmap != null) {
                    Image(
                        bitmap = detailBitmap.asImageBitmap(),
                        contentDescription = "第 ${item.id} 张",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                detailBitmap.width.toFloat() / detailBitmap.height.toFloat()
                            )
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    ImagePlaceholder(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(14.dp))
                    )
                }

                // 命中统计
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "命中 ",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${item.hitCount}",
                        color = XiaomiYellow,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = " / ${item.lineCount}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                // 逐行结果，和拍摄页同一个组件。
                // 分界线之上先放命中的，之下再列其余的。
                if (item.matches.isEmpty()) {
                    Text(
                        text = "这一张没有识别到文字",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                } else {
                    // 分界线之上：命中结果的**副本**
                    val hits = item.matches.filter { it.isHit }
                    if (hits.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            hits.forEach { match -> MatchRow(match) }
                        }
                        HitDivider()
                    }

                    // 分界线之下：照常显示完整的逐行结果（命中的也在里面）
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        item.matches.forEach { match -> MatchRow(match) }
                    }
                }
            }
        }
    }
}
