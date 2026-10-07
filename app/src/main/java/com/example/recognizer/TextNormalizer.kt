package com.example.recognizer

/**
 * 文本归一化：OCR 出来的文本和数据集里的文本，常常只是“长得像”而不是完全相同。
 * 归一化之后再比对，命中率会高很多。
 *
 * 处理的常见差异：
 * - 全角/半角：`ＡＢＣ１２３` vs `ABC123`，`：` vs `:`
 * - 大小写：`AbC` vs `abc`
 * - 空白与标点：换行、多个空格、全角空格 `\u3000`、`：，。！` 之类
 */
internal object TextNormalizer {

    /**
     * 归一化：全部转小写、全角转半角，然后**只保留字母、数字和汉字**，
     * 其余（空白、各类标点）一律丢弃。
     *
     * 这样 `设备编号：`、`设备编号 `、`设备编号:` 都会变成 `设备编号`。
     */
    fun normalize(input: String): String {
        val builder = StringBuilder(input.length)
        for (raw in input) {
            val ch = toHalfWidth(raw).lowercaseChar()
            // 汉字在 Java 里就满足 isLetter，这一条同时覆盖字母、数字、汉字
            if (ch.isLetterOrDigit()) builder.append(ch)
        }
        return builder.toString()
    }

    /** 全角字符 -> 半角字符，其余原样返回。 */
    private fun toHalfWidth(ch: Char): Char = when {
        ch == '\u3000' -> ' ' // 全角空格
        // 全角 ASCII 可见字符 (FF01..FF5E) 与半角 (21..7E) 相差固定偏移
        ch in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
        else -> ch
    }
}
