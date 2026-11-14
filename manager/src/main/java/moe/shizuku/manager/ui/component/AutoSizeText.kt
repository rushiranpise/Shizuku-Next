package moe.shizuku.manager.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text that shrinks to fit the width its parent gives it.
 *
 * The alternative is wrapping, which is what turns a one-word label into a column of
 * letters when the font is wide. The font size is reduced from the actual text layout
 * feedback, not from a guess, so a custom system font and a large font scale are handled
 * too. Existing line breaks are kept; only an extra line beyond [maxLines] shrinks it.
 *
 * [maxFontScale] caps how much a large system font can grow the label, so a fixed layout
 * keeps the same number of lines instead of leaving each label at a different size.
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
    maxFontScale: Float = 1.2f,
    fillWidth: Boolean = true
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val fontScale = density.fontScale
        val baseSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize.value else 16f
        val cappedBaseSize = if (fontScale > maxFontScale) {
            baseSize * maxFontScale / fontScale
        } else {
            baseSize
        }
        var fittedSize by remember(text, style, maxLines, widthPx, maxFontScale, fontScale) {
            mutableStateOf(cappedBaseSize.coerceAtLeast(minFontSize.value).sp)
        }

        Text(
            text = text,
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
            style = style.copy(fontSize = fittedSize),
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            softWrap = maxLines > 1,
            overflow = TextOverflow.Clip,
            onTextLayout = { layout ->
                if (layout.hasVisualOverflow && fittedSize.value > minFontSize.value) {
                    fittedSize = (fittedSize.value - 1f)
                        .coerceAtLeast(minFontSize.value)
                        .sp
                }
            }
        )
    }
}
