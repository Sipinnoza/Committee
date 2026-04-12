package com.committee.investing.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.committee.investing.ui.theme.TextPrimary

/**
 * 轻量内联 Markdown 渲染器
 * 支持: # 标题, **粗体**, *斜体*, `行内代码`, ```代码块```, - 列表, > 引用, ---分隔线
 * 不依赖第三方库
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
                    // 结束代码块
                    append(codeBlockContent.toString().trimEnd())
                    pop()
                    codeBlockContent = StringBuilder()
                    inCodeBlock = false
                } else {
                    // 开始代码块
                    if (lineIndex > 0) append('\n')
                    pushStyle(SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
 */
private fun AnnotatedString.Builder.appendInlineContent(text: String, linkColor: androidx.compose.ui.graphics.Color) {
    // 简单状态机解析行内格式
    val pattern = Regex("""(\*\*(.+?)\*\*|\*(.+?)\*|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\))""")
    var lastEnd = 0

    for (match in pattern.findAll(text)) {
        // 匹配前的普通文本
        if (match.range.first > lastEnd) {
            append(text.substring(lastEnd, match.range.first))
        }

        val boldGroup = match.groupValues[2]
        val italicGroup = match.groupValues[3]
        val codeGroup = match.groupValues[4]
        val linkText = match.groupValues[5]
        val linkUrl = match.groupValues[6]

        when {
            boldGroup != null -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(boldGroup)
                pop()
            }
            italicGroup != null -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(italicGroup)
                pop()
            }
            codeGroup != null -> {
                pushStyle(SpanStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = androidx.compose.ui.graphics.Color(0x1A000000),
                ))
                append(codeGroup)
                pop()
            }
            linkText != null -> {
                pushStyle(SpanStyle(
                    color = linkColor,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ))
                append(linkText)
                pop()
            }
        }
        lastEnd = match.range.last + 1
    }

    // 剩余文本
    if (lastEnd < text.length) {
        append(text.substring(lastEnd))
    }
}
