package dev.averyzhoux.recognizer

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 一次待确认的导入。
 *
 * 解析先做一遍用于**预览**（让用户当场看出格式对不对），确认后才落盘。
 * 保留原始字节/文本，落盘时由 [DatasetStore] 再解析一遍——同一个解析器，
 * 所以预览和最终存下来的内容一致。
 */
internal class PendingImport(
    val bytes: ByteArray?,
    val text: String?,
    val sourceFileName: String?,
    val preview: ParseResult,
    val encoding: String,
    val suggestedName: String
)
/** 粘贴文本导入。 */
@Composable
internal fun PasteDialog(
    onDismiss: () -> Unit,
    onConfirm: (text: String, name: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴导入") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("每行一个关键词") },
                    placeholder = { Text("Serial Number\nModel\nManufacturer, Mfg") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("数据集名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text, name) },
                enabled = text.isNotBlank()
            ) {
                Text("预览")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 导入预览确认。
 *
 * 这一步不能省：格式不对（分隔符、编码、列数）要在这里就能看出来，
 * 否则导入一堆垃圾数据还以为是自己拍错了。
 */
@Composable
internal fun ImportPreviewDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var name by remember { mutableStateOf(pending.suggestedName) }
    val preview = pending.preview

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = buildString {
                        append("解析出 ")
                        append(preview.entries.size)
                        append(" 项")
                        append("（共 ")
                        append(preview.totalLines)
                        append(" 行，编码 ")
                        append(pending.encoding)
                        append("）")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (preview.skippedLines.isNotEmpty()) {
                    Text(
                        text = "跳过了 ${preview.skippedLines.size} 行（空行 / 注释 / 重复项）",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB26A00),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (preview.entries.isEmpty()) {
                    Text(
                        text = "没有解析出任何条目，请检查文件内容",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Text(
                        text = "前几项预览：",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    preview.entries.take(8).forEach { entry ->
                        Text(
                            text = buildString {
                                append("· ")
                                append(entry.name)
                                if (entry.aliases.isNotEmpty()) {
                                    append("   （别名：")
                                    append(entry.aliases.joinToString(" / "))
                                    append("）")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (preview.entries.size > 8) {
                        Text(
                            text = "…… 还有 ${preview.entries.size - 8} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("数据集名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = preview.entries.isNotEmpty()
            ) {
                Text("导入并使用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 从 content Uri 里取显示用的文件名。 */
internal fun queryDisplayName(context: Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
