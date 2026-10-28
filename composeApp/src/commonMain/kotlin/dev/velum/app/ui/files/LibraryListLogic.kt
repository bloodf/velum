package dev.velum.app.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.velum.core.db.Recording

enum class LibrarySort {
    Newest,
    Oldest,
    TitleAsc,
}

/** Pure sort / highlight helpers for the library list (unit-tested). */
object LibraryListLogic {

    fun compareRecordings(a: Recording, b: Recording, sort: LibrarySort): Int =
        when (sort) {
            LibrarySort.Newest -> b.started_at.compareTo(a.started_at).thenById(a, b)
            LibrarySort.Oldest -> a.started_at.compareTo(b.started_at).thenById(a, b)
            LibrarySort.TitleAsc -> a.title.compareTo(b.title, ignoreCase = true).thenById(a, b)
        }

    fun sorted(recordings: List<Recording>, sort: LibrarySort): List<Recording> =
        recordings.sortedWith { a, b -> compareRecordings(a, b, sort) }

    /**
     * Case-insensitive ranges of [query] inside [title].
     * Empty / blank query → no ranges. Overlapping hits are merged.
     */
    fun highlightRanges(title: String, query: String): List<IntRange> {
        val needle = query.trim()
        if (needle.isEmpty() || title.isEmpty()) return emptyList()
        val lowerTitle = title.lowercase()
        val lowerNeedle = needle.lowercase()
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start <= lowerTitle.length - lowerNeedle.length) {
            val idx = lowerTitle.indexOf(lowerNeedle, start)
            if (idx < 0) break
            ranges += idx until (idx + lowerNeedle.length)
            start = idx + lowerNeedle.length
        }
        return mergeRanges(ranges)
    }

    fun highlightedTitle(
        title: String,
        query: String,
        highlightBackground: Color,
        highlightForeground: Color,
    ): AnnotatedString {
        val ranges = highlightRanges(title, query)
        if (ranges.isEmpty()) return AnnotatedString(title)
        return buildAnnotatedString {
            var cursor = 0
            for (range in ranges) {
                if (cursor < range.first) {
                    append(title.substring(cursor, range.first))
                }
                withStyle(
                    SpanStyle(
                        background = highlightBackground,
                        color = highlightForeground,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append(title.substring(range.first, range.last + 1))
                }
                cursor = range.last + 1
            }
            if (cursor < title.length) {
                append(title.substring(cursor))
            }
        }
    }

    fun recordingMarkdown(
        title: String,
        transcriptionLabel: String,
        summaryLabel: String,
        transcript: String?,
        summaryMarkdown: String?,
    ): String = buildString {
        append("# ").append(title).append('\n').append('\n')
        append("## ").append(transcriptionLabel).append('\n').append('\n')
        append(transcript.orEmpty().ifBlank { "—" }).append('\n').append('\n')
        append("## ").append(summaryLabel).append('\n').append('\n')
        append(summaryMarkdown.orEmpty().ifBlank { "—" }).append('\n')
    }

    private fun Int.thenById(a: Recording, b: Recording): Int =
        if (this != 0) this else a.id.compareTo(b.id)

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            cur = if (next.first <= cur.last + 1) {
                cur.first..maxOf(cur.last, next.last)
            } else {
                out += cur
                next
            }
        }
        out += cur
        return out
    }
}
