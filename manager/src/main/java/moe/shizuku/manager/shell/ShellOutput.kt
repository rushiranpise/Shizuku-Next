package moe.shizuku.manager.shell

/**
 * Finding things in what the shell has printed.
 *
 * A `dumpsys` is thousands of lines, and the answer is usually one of them. The search keeps
 * every line and marks the matches rather than filtering down to them: filtering loses the
 * context that says what a line belongs to, which for a dump is most of what makes it readable.
 */
object ShellOutput {

    /**
     * The most text worth putting on the clipboard.
     *
     * Android moves clips through Binder, whose transaction buffer is about 1 MB and is shared
     * with everything else this process is doing. A Java string spends two bytes per character
     * there, so 400,000 characters is roughly 800 KB before the parcel overhead. Above that the
     * platform throws a wrapped `TransactionTooLargeException`, which used to take the app down
     * when the shell copied a long transcript.
     */
    const val MAX_CLIPBOARD_CHARS = 400_000

    /**
     * The transcript as one clipboard string, or null when it is too large for the clipboard.
     *
     * The size is counted before anything is joined so a very large transcript cannot spend the
     * memory on a string that is only going to be refused. The newline between lines is counted
     * with them, which is why the last line is charged one character it does not use.
     */
    fun clipboardText(
        texts: List<String>,
        maxChars: Int = MAX_CLIPBOARD_CHARS
    ): String? {
        var size = 0L
        for (text in texts) {
            size += text.length
            if (size > maxChars) return null
            size++
        }
        return texts.joinToString("\n")
    }

    /**
     * Where [query] occurs in [text], case-insensitively, as ranges.
     *
     * Non-overlapping and left to right, so `aa` in `aaaa` is two matches rather than three.
     * A blank query has no matches, rather than matching everything at every position.
     */
    fun matchRangesOf(text: String, query: String): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val haystack = text.lowercase()
        val needle = query.lowercase()

        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            ranges.add(at until at + needle.length)
            from = at + needle.length
        }
        return ranges
    }

    /** The indices of the lines that contain [query]. */
    fun matchingLines(texts: List<String>, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val needle = query.lowercase()
        return texts.withIndex().filter { it.value.lowercase().contains(needle) }.map { it.index }
    }

    /** How many times [query] occurs across all of [texts]. */
    fun matchCount(texts: List<String>, query: String): Int =
        texts.sumOf { matchRangesOf(it, query).size }
}
