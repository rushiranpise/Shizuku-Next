package moe.shizuku.manager.ui.component

/**
 * Minimal HTML tag removal, so strings that contain markup (the legacy View UI
 * rendered them with HtmlCompat, Compose Text does not parse HTML) don't show
 * raw tags. Paragraph/line-break tags become newlines.
 */
fun String.stripHtmlTags(): String =
    replace(Regex("(?i)<\\s*br\\s*/?\\s*>"), "\n")
        .replace(Regex("(?i)<\\s*/?\\s*p\\s*>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .trim()
