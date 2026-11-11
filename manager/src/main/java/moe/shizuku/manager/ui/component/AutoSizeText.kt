package moe.shizuku.manager.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text that shrinks to fit the width its parent gives it.
 *
 * The alternative is wrapping, which is what turns a one-word label into a column of
 * letters when the font is wide. The font size is found by measuring the text, not by
 * guessing from the font scale, so a custom system font is handled too.
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    minFontSize: TextUnit = 8.sp,
    fillWidth: Boolean = true
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val widthPx = with(density) { maxWidth.toPx() }
        val fittedSize = remember(text, style, maxLines, minFontSize, widthPx) {
            fitFontSize(measurer, text, style, widthPx.toInt(), maxLines, minFontSize)
        }

        Text(
            text = text,
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
            style = style.copy(fontSize = fittedSize),
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            softWrap = maxLines > 1,
            overflow = TextOverflow.Clip
        )
    }
}

private fun fitFontSize(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
    maxLines: Int,
    minFontSize: TextUnit
): TextUnit {
    val baseSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize.value else 16f
    if (widthPx <= 0 || baseSize <= 0f) return baseSize.sp

    val floor = minFontSize.value.coerceAtMost(baseSize)
    var low = floor
    var high = baseSize
    var best = floor

    repeat(12) {
        val size = (low + high) / 2f
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = style.copy(fontSize = size.sp),
            maxLines = if (maxLines == 1) 1 else Int.MAX_VALUE,
            softWrap = maxLines > 1,
            constraints = if (maxLines == 1) {
                Constraints()
            } else {
                Constraints(maxWidth = widthPx)
            }
        )
        val fits = layout.size.width <= widthPx && layout.lineCount <= maxLines
        if (fits) {
            best = size
            low = size
        } else {
            high = size
        }
    }

    return best.sp
}
