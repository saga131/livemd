package com.livemd.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.text.style.ImageSpan

/** 标题标记：大纲跳转用 */
class HeadingSpan(val level: Int, val title: String)

/** 文内查找的高亮（独立类便于精准清除，不误伤代码块底色） */
class FindHighlightSpan : BackgroundColorSpan(0xFFFFEB3B.toInt())

/** 图片占位 Span：同步完成时先占位，NoteImages 异步解码后换成真图 */
class ImageRefSpan(val ref: String, context: Context) : ImageSpan(
    android.graphics.drawable.ColorDrawable(0x33999999).apply {
        bounds = android.graphics.Rect(0, 0, 480, 160)
    }
)

/**
 * 轻量 Markdown → Spannable 渲染器（零第三方依赖）。
 * 支持：标题、有序/无序列表、引用、代码块、行内代码、粗斜体、删除线、高亮、
 * 链接、图片占位、[[wikilink]]、表格（等宽对齐）、分隔线。
 */
object MarkdownRenderer {

    private val INLINE = Regex(
        "`([^`]+)`" +                  // 1 行内代码
            "|\\*\\*([^*\\n]+)\\*\\*" +   // 2-3 粗体 **x** / __x__
            "|__([^_\\n]+)__" +
            "|\\*([^*\\n]+)\\*" +         // 4-5 斜体 *x* / _x_（下划线版要求词边界，防 snake_case 误判）
            "|(?<!\\w)_([^_\\n]+)_(?!\\w)" +
            "|~~([^~\\n]+)~~" +           // 6 删除线
            "|==([^=\\n]+)==" +           // 7 高亮
            "|!\\[([^\\]]*)\\]\\(([^)]+)\\)" + // 8-9 图片（markdown 形式）
            "|\\[\\[([^\\]]+)\\]\\]" +    // 10 wikilink
            "|\\[([^\\]]+)\\]\\(([^)]+)\\)" +  // 11-12 链接
            "|(\\\\\\p{Punct})" +         // 13 转义：\X → 字面 X（CommonMark 反斜杠转义，仅 ASCII 标点）
            "|!\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]" + // 14 图片（Obsidian 嵌入形式 ![[xx.png|300]]）
            "|\\$([^$\\n]+?)\\$"          // 15 行内公式 $...$（首尾紧贴 $ 才算，避免金额误判）
    )

    /**
     * imageExists：传入图片引用名（文件名），返回本地是否已有该附件（attachments 目录）。
     * 有则渲染为可加载的图片占位，没有则显示占位文字。
     */
    fun render(md: String, context: Context, imageExists: (String) -> Boolean = { false }): android.text.Spannable {
        val accent = androidx.core.content.ContextCompat.getColor(context, R.color.accent)
        val codeFg = androidx.core.content.ContextCompat.getColor(context, R.color.codeBg)
        val sb = SpannableStringBuilder()
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    i++
                    val block = StringBuilder()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        block.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // 跳过结尾 ```
                    appendCodeBlock(sb, block.toString().trimEnd('\n'), codeFg)
                }
                line.startsWith("```") -> i++
                line.trimStart().startsWith("$$") -> { // 块级公式 $$...$$（单行或跨行）
                    val first = line.trim().removePrefix("$$")
                    if (first.endsWith("$$") && first.length >= 2) {
                        appendMathBlock(sb, first.removeSuffix("$$").trim())
                        i++
                    } else {
                        val buf = StringBuilder(first)
                        i++
                        var closed = false
                        while (i < lines.size && !closed) {
                            val l = lines[i]
                            if (l.trimEnd().endsWith("$$")) {
                                buf.append(' ').append(l.trimEnd().removeSuffix("$$"))
                                closed = true
                            } else {
                                buf.append(' ').append(l.trim())
                            }
                            i++
                        }
                        appendMathBlock(sb, buf.toString().trim())
                    }
                }
                Regex("^#{1,6}\\s+.+").matches(line.trimEnd()) -> {
                    val level = line.indexOfFirst { it != '#' }
                    val text = line.substring(level + 1).trim()
                    val start = sb.length
                    appendInline(sb, text, accent, codeFg, context, imageExists)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val size = when (level) { 1 -> 1.9f; 2 -> 1.6f; 3 -> 1.4f; 4 -> 1.2f; else -> 1.1f }
                    sb.setSpan(RelativeSizeSpan(size), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(HeadingSpan(level, text), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("\n\n")
                    i++
                }
                Regex("^(---+|\\*\\*\\*+|___+)\\s*$").matches(line.trim()) -> {
                    sb.append("──────────────\n\n")
                    i++
                }
                line.trimStart().startsWith(">") -> {
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        val text = lines[i].trimStart().removePrefix(">").trim()
                        val start = sb.length
                        appendInline(sb, text, accent, codeFg, context, imageExists)
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(Color.GRAY), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.insert(start, "▏ ")
                        sb.append('\n')
                        i++
                    }
                    sb.append('\n')
                }
                Regex("^\\s*([-*+]\\s+|\\d+[.)]\\s+).+").matches(line) -> {
                    while (i < lines.size && Regex("^\\s*([-*+]\\s+|\\d+[.)]\\s+).+").matches(lines[i])) {
                        val indent = lines[i].length - lines[i].trimStart().length
                        val body = lines[i].trimStart()
                        val marker: String
                        val rest: String
                        val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)").find(body)!!
                        if (m.groupValues[1][0].isDigit()) marker = "${m.groupValues[1].trimEnd('.').trimEnd(')')}. "
                        else marker = "•  "
                        rest = m.groupValues[2]
                        val pad = " ".repeat(indent + 2)
                        sb.append(pad).append(marker)
                        appendInline(sb, rest, accent, codeFg, context, imageExists)
                        sb.append('\n')
                        i++
                    }
                    sb.append('\n')
                }
                line.trimStart().startsWith("|") && line.trim().endsWith("|") -> {
                    val table = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|") && lines[i].trim().endsWith("|")) {
                        val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                        val isDivider = cells.all { Regex("^:?-{2,}:?$").matches(it) }
                        if (!isDivider) table.add(cells)
                        i++
                    }
                    appendTable(sb, table)
                    sb.append('\n')
                }
                line.trim().isEmpty() -> {
                    if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append('\n')
                    i++
                }
                else -> { // 普通段落
                    appendInline(sb, line, accent, codeFg, context, imageExists)
                    sb.append('\n')
                    i++
                }
            }
        }
        return sb
    }

    private fun appendCodeBlock(sb: SpannableStringBuilder, code: String, fg: Int) {
        val start = sb.length
        sb.append(code).append('\n')
        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(BackgroundColorSpan(0x22000000.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(fg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')
    }

    private fun appendTable(sb: SpannableStringBuilder, table: List<List<String>>) {
        if (table.isEmpty()) return
        val cols = table.maxOf { it.size }
        val widths = IntArray(cols)
        for (row in table) for (c in row.indices) widths[c] = maxOf(widths[c], row[c].length)
        for (row in table) {
            val start = sb.length
            for (c in 0 until cols) {
                val cell = row.getOrElse(c) { "" }
                sb.append(cell.padEnd(widths[c] + 2))
            }
            sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
            if (row === table.firstOrNull()) sb.append("─".repeat(widths.sum() + cols * 2)).append('\n')
        }
    }

    private fun appendInline(
        sb: SpannableStringBuilder, text: String, accent: Int, codeFg: Int,
        context: Context, imageExists: (String) -> Boolean
    ) {
        var last = 0
        for (m in INLINE.findAll(text)) {
            sb.append(text, last, m.range.first)
            val start = sb.length
            val g = m.groupValues
            when {
                g[13].isNotEmpty() -> { // \X 转义 → 字面字符
                    sb.append(g[13].substring(1))
                }
                g[1].isNotEmpty() -> { // `code`
                    sb.append(g[1])
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0x18000000), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[2].isNotEmpty() || g[3].isNotEmpty() -> {
                    sb.append(g[2].ifEmpty { g[3] })
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[4].isNotEmpty() || g[5].isNotEmpty() -> {
                    sb.append(g[4].ifEmpty { g[5] })
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[6].isNotEmpty() -> {
                    sb.append(g[6])
                    sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[7].isNotEmpty() -> {
                    sb.append(g[7])
                    sb.setSpan(BackgroundColorSpan(0x30FFEB3B), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[8].isNotEmpty() -> { // ![alt](path) 图片
                    val name = decodeRef(g[9])
                    if (imageExists(name)) {
                        sb.append('\uFFFC')
                        sb.setSpan(ImageRefSpan(name, context), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        sb.append("🖼 ").append(g[8]).append("（图片未同步）")
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                g[10].isNotEmpty() -> { // wikilink
                    sb.append(g[10])
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[11].isNotEmpty() -> { // 链接
                    sb.append(g[11])
                    sb.setSpan(ForegroundColorSpan(accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[14].isNotEmpty() -> { // ![[name.png|300]] Obsidian 图片嵌入
                    val name = decodeRef(g[14])
                    if (imageExists(name)) {
                        sb.append('\uFFFC')
                        sb.setSpan(ImageRefSpan(name, context), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        sb.append("🖼 ").append(name).append("（图片未同步）")
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                g[15].isNotEmpty() -> { // $x^2$ 行内公式：内容首尾不能是空白，否则按普通文本处理
                    val body = g[15]
                    if (body.first().isWhitespace() || body.last().isWhitespace()) {
                        sb.append(text, m.range.first, m.range.last + 1)
                    } else {
                        sb.append('\uFFFC')
                        sb.setSpan(MathSpan(body, block = false), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            last = m.range.last + 1
        }
        sb.append(text, last, text.length)
    }

    /** 块级公式占位（异步替换为渲染位图） */
    private fun appendMathBlock(sb: SpannableStringBuilder, latex: String) {
        if (latex.isEmpty()) return
        sb.append('\uFFFC')
        val start = sb.length - 1
        sb.setSpan(MathSpan(latex, block = true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n\n")
    }

    private fun decodeRef(raw: String): String {
        val name = raw.trim().substringAfterLast('/')
        return try {
            java.net.URLDecoder.decode(name, "UTF-8")
        } catch (e: Exception) {
            name
        }
    }
}
