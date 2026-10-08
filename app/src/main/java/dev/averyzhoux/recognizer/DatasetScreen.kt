package dev.averyzhoux.recognizer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/**
 * 顶部右侧的数据集入口。
 *
 * 必须一直显示「当前用的是哪个表格」——否则识别结果不对时，
 * 你会以为是识别错了，其实是数据集没切。
 */
@Composable
internal fun DatasetChip(
    dataset: DatasetMeta?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val name = dataset?.name ?: "加载中…"
    val count = dataset?.entryCount ?: 0
    // ★ 和左边的状态文字一样，不加胶囊底色，只显示纯文字。
    //   点击区还在（整个 Row 可点），只是看不出来了。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp)
        )
        Text(
            text = "$count ›",
            color = XiaomiYellow,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

/** 数据集入口那一行。 */
@Composable
internal fun DatasetRow(
    meta: DatasetMeta,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (active) XiaomiYellow.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSelect)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meta.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (meta.builtIn) {
                        Text(
                            text = "  内置",
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Text(
                    text = buildString {
                        append("${meta.entryCount} 项 · ${meta.encoding}")
                        if (!meta.builtIn) {
                            append(" · ")
                            append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(meta.importedAt)))
                        }
                        meta.sourceFileName?.let { append(" · $it") }
                    },
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (active) {
                Text(
                    text = "✓ 使用中",
                    color = XiaomiYellow,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // 内置的条目写死在代码里，既不能删也不能编辑
            if (!meta.builtIn) {
                RowAction(text = "编辑", color = XiaomiYellow, onClick = onEdit)
                RowAction(
                    text = "删除",
                    color = Color.White.copy(alpha = 0.55f),
                    onClick = onDelete
                )
            }
        }
    }
}

/** 数据集行右侧的纯文字操作按钮（编辑 / 删除），样式统一。 */
@Composable
internal fun RowAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .padding(start = 10.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** 数据集管理页：列出全部数据集，并提供两个导入入口。 */
@Composable
internal fun DatasetScreen(
    datasets: List<DatasetMeta>,
    activeId: Int,
    onBack: () -> Unit,
    onSelect: (DatasetMeta) -> Unit,
    onEdit: (DatasetMeta) -> Unit,
    onDelete: (DatasetMeta) -> Unit,
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

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
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "数据集（表格）",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                // 占位，让标题居中
                Text(
                    text = "      ",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Text(
                        text = "识别结果会跟「使用中」的这张表格逐项比对。" +
                            "每行一个关键词；一行里有逗号时，第一个是名称、其余当别名。",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(datasets, key = { it.id }) { meta ->
                    DatasetRow(
                        meta = meta,
                        active = meta.id == activeId,
                        onSelect = { onSelect(meta) },
                        onEdit = { onEdit(meta) },
                        onDelete = { onDelete(meta) }
                    )
                }
            }

            // 两个导入入口
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                Button(
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选文件导入")
                }
                OutlinedButton(
                    onClick = onPaste,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("粘贴导入")
                }
            }
        }
    }
}

/**
 * 数据集内容编辑页。
 *
 * 目前只做一件事：给每一项加/去「已识别」标记（左侧那个小方框）。
 * 标记只影响识别结果里的**颜色**，不参与匹配——[OcrMatcher] 一行都不认识这个字段。
 */
@Composable
internal fun DatasetEditScreen(
    name: String,
    entries: List<Entry>,
    onBack: () -> Unit,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val markedCount = entries.count { it.marked }

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
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    text = name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = "已标记 $markedCount",
                    color = MatchBlue,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 16.dp
                ),
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                item {
                    Text(
                        text = "点一下左边的方框，把这一项标记成「已识别」。标记过的项在识别结果里" +
                            "显示为蓝色（优先于精确绿 / 近似黄），方便区分「这条我核对过了」" +
                            "和「这条只是自动命中的」。每次点击立刻存盘。",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                // 列表顺序永远不变，用位置当 key 就够
                itemsIndexed(entries) { index, entry ->
                    EntryMarkRow(entry = entry, onToggle = { onToggle(index) })
                }
            }
        }
    }
}

/** 编辑页的一行：左侧标记框 + 名称 + 别名。 */
@Composable
internal fun EntryMarkRow(entry: Entry, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (entry.marked) MatchBlue.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f)
            )
            // 整行都能点：小方框只有 22dp，手指不好瞄
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        MarkBox(marked = entry.marked)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = entry.name,
                color = if (entry.marked) MatchBlue else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.aliases.isNotEmpty()) {
                Text(
                    text = entry.aliases.joinToString("、"),
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * 编辑页每行左侧的标记框（对应 `[ ]` / `[✓]`）。
 *
 * 未标记是空心框，标记后填蓝并打勾。刻意不用 Material 的 Checkbox：
 * 一排几十个 Checkbox 视觉太重，而且这里要的是「扫一眼看到哪些勾了」，
 * 自绘的小方框更容易扫。
 */
@Composable
internal fun MarkBox(marked: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (marked) MatchBlue.copy(alpha = 0.25f) else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (marked) MatchBlue else Color.White.copy(alpha = 0.35f),
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        if (marked) {
            Text(
                text = "✓",
                color = MatchBlue,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
