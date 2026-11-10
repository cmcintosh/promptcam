package com.wembassy.promptcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Teleprompter overlay composable that renders semi-transparent scrolling text
 * over the camera preview.
 *
 * Features:
 * - No opaque background — camera preview is fully visible behind the text
 * - White text with drop shadow for legibility against any camera background
 * - Text is centered horizontally
 * - Auto-scrolls based on the scroll position value (0.0 = top, 1.0 = bottom)
 * - Gradient fade at top and bottom edges for smooth visual transition
 * - Uses Canvas and TextMeasurer for rendering
 *
 * @param text The teleprompter text to display
 * @param scrollPosition Scroll position (0.0 = top of text, 1.0 = bottom)
 * @param fontSize Font size in sp for the teleprompter text
 * @param modifier Compose modifier for layout customization
 */
@Composable
fun TeleprompterOverlay(
    text: String,
    scrollPosition: Float,
    fontSize: Int = 28,
    modifier: Modifier = Modifier
) {
    if (text.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

    // Text style with drop shadow for legibility
    val textStyle = TextStyle(
        color = Color.White,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        lineHeight = (fontSize * 1.6f).sp,
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.85f),
            offset = androidx.compose.ui.geometry.Offset(2f, 2f),
            blurRadius = 6f
        )
    )

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            // Measure the text layout with constrained width (85% of screen width)
            val maxTextWidth = (size.width * 0.85f).toInt()
            val textLayoutResult = textMeasurer.measure(
                text = text,
                style = textStyle,
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = maxTextWidth,
                    maxHeight = Int.MAX_VALUE
                )
            )

            val textHeight = textLayoutResult.size.height.toFloat()
            val padding = 100f

            // Calculate scroll offset
            val maxScroll = (textHeight - size.height + 2 * padding).coerceAtLeast(0f)
            val scrollOffset = scrollPosition * maxScroll

            // Vertical start position (negative = scrolled up)
            val startY = padding - scrollOffset

            // Center horizontally
            val textWidth = textLayoutResult.size.width.toFloat()
            val startX = (size.width - textWidth) / 2f

            // Draw the text with drop shadow
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(startX, startY)
            )

            // Draw gradient fade at top edge
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.7f),
                        Color.Black.copy(alpha = 0.3f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.12f
                ),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height * 0.12f)
            )

            // Draw gradient fade at bottom edge
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.3f),
                        Color.Black.copy(alpha = 0.7f)
                    ),
                    startY = size.height * 0.88f,
                    endY = size.height
                ),
                topLeft = Offset(0f, size.height * 0.88f),
                size = Size(size.width, size.height * 0.12f)
            )
        }
    }
}
