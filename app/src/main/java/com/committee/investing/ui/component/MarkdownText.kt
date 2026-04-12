package com.committee.investing.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.committee.investing.ui.theme.TextPrimary

/**
 * 轻量内联 Markdown 渲染器
 * 支持: # 标题, **粗体**, *斜体*, `行内代码`, ```代码块```, - 列表, > 引用, ---分隔线
 *
 * 修复：groupValues 对未命中的组返回 "" 而非 null，
 *        原代码 when 条件 `!= null` 永远为 true，导致粗体逻辑吞噬斜体/代码/链接。
 *        改为 isNotEmpty() 判断。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        lineHeight = 22.sp,
    ),
) {
    SelectionContainer {
        androidx.compose.foundation.text.BasicText(
            text = buildAnnotatedString(text),
            style = baseStyle.merge(TextStyle(color = TextPrimary)),
            modifier = modifier.padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun buildAnnotatedString(raw: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        val lines = raw.split("\n")
        var inCodeBlock = false
        var codeBlockContent = StringBuilder()

        for ((lineIndex, line) in lines.withIndex()) {
            // 代码块开始/结束
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    append(codeBlockContent.toString().trimEnd())
                    pop()
                    codeBlockContent = StringBuilder()
                    inCodeBlock = false
                } else {
                    if (lineIndex > 0) append('\n')
                    pushStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = androidx.compose.ui.graphics.Color(0x1A000000),
                    ))
                    inCodeBlock = true
                }
                if (lineIndex < lines.lastIndex) append('\n')
                continue
            }

            if (inCodeBlock) {
                codeBlockContent.append(line)
                codeBlockContent.append('\n')
                continue
            }

            // 空行
            if (line.isBlank()) {
                append('\n')
                continue
            }

            val trimmed = line.trimStart()

            // 标题 # ## ### 等
            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val title = headingMatch.groupValues[2]
                if (lineIndex > 0) append('\n')
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = when (level) {
                        1 -> 20.sp; 2 -> 18.sp; 3 -> 17.sp; 4 -> 16.sp; else -> 15.sp
                    },
                ))
                appendInlineContent(title, linkColor)
                pop()
                if (lineIndex < lines.lastIndex) append('\n')
                continue
            }

            // 分隔线 ---
            if (trimmed.matches(Regex("^-{3,}\\s*$"))) {
                append("────────────────")
                if (lineIndex < lines.lastIndex) append('\n')
                continue
            }

            // 引用 >
            if (trimmed.startsWith("> ")) {
                if (lineIndex > 0) append('\n')
                pushStyle(SpanStyle(
                    fontStyle = FontStyle.Italic,
                    color = TextPrimary.copy(alpha = 0.7f),
                ))
                append("│ ")
                appendInlineContent(trimmed.removePrefix("> "), linkColor)
                pop()
                if (lineIndex < lines.lastIndex) append('\n')
                continue
            }

            // 列表 - 或 * 或 数字.
            val listMatch = Regex("^(\\s*)[-*]\\s+(.+)$").matchEntire(line)
            val numberedListMatch = Regex("^(\\s*)(\\d+)\\.\\s+(.+)$").matchEntire(line)

            if (lineIndex > 0) append('\n')

            if (listMatch != null) {
                val indent = listMatch.groupValues[1].length
                val content = listMatch.groupValues[2]
                append("  ".repeat(indent / 2))
                append("• ")
                appendInlineContent(content, linkColor)
            } else if (numberedListMatch != null) {
                val num = numberedListMatch.groupValues[2]
                val indent = numberedListMatch.groupValues[1].length
                val content = numberedListMatch.groupValues[3]
                append("  ".repeat(indent / 2))
                append("$num. ")
                appendInlineContent(content, linkColor)
            } else {
                appendInlineContent(trimmed, linkColor)
            }
        }
    }
}

/**
 * 处理行内格式：**粗体**、*斜体*、`代码`、[链接](url)
 *
 * 修复：原代码 when 条件用 `!= null` 判断 groupValues（永远非 null），
 *        改为 isNotEmpty()，确保各分支互斥正确命中。
 */
private fun AnnotatedString.Builder.appendInlineContent(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
) {
    val pattern = Regex("""(\*\*(.+?)\*\*|\*(.+?)\*|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\))""")
    var lastEnd = 0

    for (match in pattern.findAll(text)) {
        if (match.range.first > lastEnd) {
            append(text.substring(lastEnd, match.range.first))
        }

        val boldGroup   = match.groupValues[2]  // **bold**
        val italicGroup = match.groupValues[3]  // *italic*
        val codeGroup   = match.groupValues[4]  // `code`
        val linkText    = match.groupValues[5]  // [text](url)
        val linkUrl     = match.groupValues[6]

        // 修复：使用 isNotEmpty() 而非 != null
        // groupValues 对未命中的捕获组返回 ""，原来的 != null 检查永远为 true，
        // 导致粗体分支优先吞噬所有匹配，斜体/代码/链接从未被渲染。
        when {
            boldGroup.isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(boldGroup)
                pop()
            }
            italicGroup.isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(italicGroup)
                pop()
            }
            codeGroup.isNotEmpty() -> {
                pushStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = androidx.compose.ui.graphics.Color(0x1A000000),
                ))
                append(codeGroup)
                pop()
            }
            linkText.isNotEmpty() -> {
                pushStyle(SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ))
                append(linkText)
                pop()
            }
        }
        lastEnd = match.range.last + 1
    }

    if (lastEnd < text.length) {
        append(text.substring(lastEnd))
    }
}