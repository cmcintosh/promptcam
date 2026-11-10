package com.wembassy.promptcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * - Semi-transparent black background (35% opacity) so camera preview is visible behind
 * - White text with configurable font size, centered horizontally
 * - Auto-scrolls based on the scroll position value (0.0 = top, 1.0 = bottom)
 * - Gradient fade at top and bottom edges for smooth visual transition
 * - Uses Canvas and TextMeasurer for rendering
 *
 * The overlay is non-interactive — it sits on top of the camera preview
 * but does not capture touch events.
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
    fontSize: Int = 24,
    modifier: Modifier = Modifier
) {
    if (text.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

    val textStyle = TextStyle(
        color = Color.White,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        lineHeight = (fontSize * 1.5f).sp
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            // Measure the text layout with constrained width
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
            val padding = 80f

            // Calculate scroll offset
            val maxScroll = (textHeight - size.height + 2 * padding).coerceAtLeast(0f)
            val scrollOffset = scrollPosition * maxScroll

            // Vertical start position (negative = scrolled up)
            val startY = padding - scrollOffset

            // Center horizontally
            val textWidth = textLayoutResult.size.width.toFloat()
            val startX = (size.width - textWidth) / 2f

            // Draw the text
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(startX, startY)
            )

            // Draw gradient fade at top
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.6f),
                        Color.Black.copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.15f
                ),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height * 0.15f)
            )

            // Draw gradient fade at bottom
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.2f),
                        Color.Black.copy(alpha = 0.6f)
                    ),
                    startY = size.height * 0.85f,
                    endY = size.height
                ),
                topLeft = Offset(0f, size.height * 0.85f),
                size = Size(size.width, size.height * 0.15f)
            )
        }
    }
}
