package com.kinetix.coach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kinetix.coach.ui.theme.CrispWhite
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SteelGray

sealed class CoachTextBlock {
    data class Heading(val text: String) : CoachTextBlock()
    data class Bullet(val text: String) : CoachTextBlock()
    data class Numbered(val index: String, val text: String) : CoachTextBlock()
    data class Paragraph(val text: String) : CoachTextBlock()
}

data class InlineSpanToken(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false
)

/**
 * Pure-Kotlin markdown parser for Coach IA replies:
 * cleanly extracts headings (`### `), bullets (`- ` / `* `), numbered lists (`1. `),
 * and inline `**bold**` / `*italic*` / `` `code` `` without leaking raw markdown tokens.
 */
object CoachMarkdownParser {

    private val HEADING_REGEX = Regex("^#{1,6}\\s+(.+)$")
    private val BULLET_REGEX = Regex("^[-*•]\\s+(.+)$")
    private val NUMBERED_REGEX = Regex("^(\\d+)[.)]\\s+(.+)$")
    private val INLINE_TOKEN_REGEX = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\*(?!\\s)(.+?)(?<!\\s)\\*")

    fun parseBlocks(raw: String): List<CoachTextBlock> {
        val blocks = mutableListOf<CoachTextBlock>()
        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (headingMatch != null) {
                val cleaned = headingMatch.groupValues[1].replace("**", "").trim()
                if (cleaned.isNotEmpty()) blocks.add(CoachTextBlock.Heading(cleaned))
                continue
            }

            val numberedMatch = NUMBERED_REGEX.matchEntire(line)
            if (numberedMatch != null) {
                blocks.add(
                    CoachTextBlock.Numbered(
                        index = "${numberedMatch.groupValues[1]}.",
                        text = numberedMatch.groupValues[2].trim()
                    )
                )
                continue
            }

            val bulletMatch = BULLET_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                blocks.add(CoachTextBlock.Bullet(bulletMatch.groupValues[1].trim()))
                continue
            }

            blocks.add(CoachTextBlock.Paragraph(line))
        }
        return blocks
    }

    fun parseInline(text: String): List<InlineSpanToken> {
        val tokens = mutableListOf<InlineSpanToken>()
        var cursor = 0
        for (m in INLINE_TOKEN_REGEX.findAll(text)) {
            if (m.range.first > cursor) {
                tokens.add(InlineSpanToken(stripStrayMarkers(text.substring(cursor, m.range.first))))
            }
            when {
                m.groups[1] != null -> tokens.add(InlineSpanToken(m.groupValues[1], bold = true))
                m.groups[2] != null -> tokens.add(InlineSpanToken(m.groupValues[2], code = true))
                m.groups[3] != null -> tokens.add(InlineSpanToken(m.groupValues[3], italic = true))
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) {
            tokens.add(InlineSpanToken(stripStrayMarkers(text.substring(cursor))))
        }
        return tokens.filter { it.text.isNotEmpty() }
    }

    private fun stripStrayMarkers(fragment: String): String =
        fragment.replace("**", "")
}

@Composable
fun FormattedCoachText(
    text: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(text) { CoachMarkdownParser.parseBlocks(text) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is CoachTextBlock.Heading -> {
                    Row(
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 3.dp, height = 14.dp)
                                .background(SignalOrange, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.titleMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is CoachTextBlock.Bullet -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SignalOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 2.dp, end = 8.dp)
                        )
                        Text(
                            text = buildInlineAnnotatedString(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrispWhite,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is CoachTextBlock.Numbered -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = block.index,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SignalOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 7.dp)
                        )
                        Text(
                            text = buildInlineAnnotatedString(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrispWhite,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is CoachTextBlock.Paragraph -> {
                    Text(
                        text = buildInlineAnnotatedString(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrispWhite
                    )
                }
            }
        }
    }
}

private fun buildInlineAnnotatedString(raw: String): AnnotatedString = buildAnnotatedString {
    for (token in CoachMarkdownParser.parseInline(raw)) {
        when {
            token.bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = CrispWhite)) {
                append(token.text)
            }
            token.code -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = SignalOrange)) {
                append(token.text)
            }
            token.italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = SteelGray)) {
                append(token.text)
            }
            else -> append(token.text)
        }
    }
}
