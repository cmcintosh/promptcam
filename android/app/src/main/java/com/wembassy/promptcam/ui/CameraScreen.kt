package com.wembassy.promptcam.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wembassy.promptcam.viewmodels.PromptCamViewModel

/**
 * Main camera screen composable.
 *
 * Shows:
 * - Camera preview using a TextureView (via SurfaceTexture)
 * - Teleprompter overlay on top of the camera preview
 * - Control buttons: camera switch, start/stop streaming, settings
 * - Connection status (number of clients connected)
 *
 * The layout is landscape-optimized with the camera preview filling the screen
 * and controls overlaid on top.
 *
 * @param viewModel The ViewModel that coordinates camera, encoders, and servers
 * @param modifier Compose modifier for layout customization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: PromptCamViewModel,
    modifier: Modifier = Modifier
) {
    val streamingState by viewModel.streamingState.collectAsState()
    val teleprompterText by viewModel.teleprompterText.collectAsState()
    val scrollPosition by viewModel.scrollPosition.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val clientCount by viewModel.clientCount.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    val context = LocalContext.current

    // Observe streaming state to manage camera and servers
    LaunchedEffect(streamingState) {
        when (streamingState) {
            com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING -> {
                // Streaming is managed by the ViewModel
            }
            com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.IDLE -> {
                // Idle state
            }
            com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.ERROR -> {
                // Error state - could show a message
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview using TextureView
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            // Notify ViewModel that the preview surface is ready
                            viewModel.onPreviewSurfaceAvailable(Surface(surface))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            // Handle size changes if needed
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            viewModel.onPreviewSurfaceDestroyed()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            // Called on every frame update - no action needed
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { tv ->
                textureView = tv
            }
        )

        // Teleprompter overlay
        if (teleprompterText.isNotEmpty()) {
            TeleprompterOverlay(
                text = teleprompterText,
                scrollPosition = scrollPosition,
                fontSize = fontSize,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top status bar — connection count and streaming status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Streaming status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusColor = when (streamingState) {
                    com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING -> Color(0xFF4CAF50)
                    com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.ERROR -> Color(0xFFF44336)
                    com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.IDLE -> Color(0xFF9E9E9E)
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, CircleShape)
                )

                Text(
                    text = when (streamingState) {
                        com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING -> "Streaming"
                        com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.ERROR -> "Error"
                        com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.IDLE -> "Idle"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // Connection count
            BadgedBox(
                badge = {
                    if (clientCount > 0) {
                        Badge {
                            Text(text = clientCount.toString())
                        }
                    }
                }
            ) {
                Text(
                    text = "Clients",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Bottom control bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera switch button
            IconButton(
                onClick = { viewModel.switchCamera() },
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Start/Stop streaming button
            Button(
                onClick = {
                    if (streamingState == com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING) {
                        viewModel.stopStreaming()
                    } else {
                        viewModel.startStreaming()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (streamingState == com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING)
                        Color(0xFFF44336) else Color(0xFF4CAF50)
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(
                    imageVector = if (streamingState == com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING)
                        Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (streamingState == com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING)
                        "Stop" else "Start"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (streamingState == com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING)
                        "Stop" else "Start"
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Settings button
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        }
    }

    // Settings dialog
    if (showSettings) {
        SettingsScreen(
            settings = settings,
            onSave = { newSettings ->
                viewModel.updateSettings(newSettings)
                showSettings = false
            },
            onCancel = { showSettings = false }
        )
    }
}
