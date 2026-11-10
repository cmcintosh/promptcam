package com.wembassy.promptcam.teleprompter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Teleprompter overlay UI — renders semi-transparent scrolling text on top of the camera preview.
 * 
 * This Composable observes the TeleprompterState and displays:
 * - A semi-transparent black background (40% opacity) so camera preview is visible behind
 * - White text with configurable font size
 * - Auto-scroll based on the scroll position value (0.0 = top, 1.0 = bottom)
 * 
 * The overlay is designed to be non-interactive — it sits on top of the camera preview
 * but does not capture touch events (the camera preview handles gestures).
 * 
 * @param state The teleprompter state to observe
 * @param modifier Compose modifier for layout customization
 */
@Composable
fun TeleprompterOverlay(
    state: TeleprompterState,
    modifier: Modifier = Modifier
) {
    // Observe state values
    val text by state.text.collectAsState()
    val fontSize by state.fontSize.collectAsState()
    val scrollPosition by state.scrollPosition.collectAsState()
    val visible by state.visible.collectAsState()
    val hasContent by state.hasContent.collectAsState()

    // Scroll state for the text column
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Track the maximum scroll value to convert position (0.0-1.0) to pixels
    var maxScrollRange by remember { mutableStateOf(0) }

    // Update scroll position when the state value changes
    LaunchedEffect(scrollPosition, maxScrollRange) {
        if (maxScrollRange > 0) {
            val targetScroll = (scrollPosition * maxScrollRange).toInt()
            coroutineScope.launch {
                scrollState.animateScrollTo(targetScroll)
            }
        }
    }

    // Update max scroll range when text changes
    LaunchedEffect(text, fontSize) {
        // Wait for layout to settle, then capture max scroll value
        kotlinx.coroutines.delay(100)
        maxScrollRange = scrollState.maxValue
    }

    // Animated visibility for smooth show/hide
    AnimatedVisibility(
        visible = visible && hasContent,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(300)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
        ) {
            // Scrollable text container
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        horizontal = 48.dp,
                        vertical = 100.dp  // Extra top/bottom padding for readability
                    ),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (text.isNotEmpty()) {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = fontSize.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = (fontSize * 1.5f).sp,  // 1.5x line height for readability
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Optional: scroll position indicator (subtle line at reading position)
            // Shows where the desktop client has positioned the scroll
            if (hasContent) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                        .align(Alignment.Top)
                        .offset(y = (100).dp)  // Align with top of text area
                )
            }
        }
    }
}
