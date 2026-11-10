package com.wembassy.promptcam.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wembassy.promptcam.viewmodels.PromptCamViewModel

/**
 * Main camera screen composable.
 *
 * Shows:
 * - Camera preview using a TextureView (full screen)
 * - Teleprompter overlay on top of the camera preview (with drop shadow)
 * - IP address of the phone (so desktop can connect)
 * - Control buttons: camera switch, start/stop streaming, settings
 * - Connection status (number of clients connected)
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
    val deviceIp by viewModel.deviceIp.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview using TextureView — fills the entire screen
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            viewModel.onPreviewSurfaceAvailable(Surface(surface))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            // Handle size changes
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            viewModel.onPreviewSurfaceDestroyed()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            // Called on every frame
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { tv ->
                // Ensure layout params are correct on recomposition
                if (tv.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                    tv.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        )

        // Teleprompter overlay — on top of camera, with drop shadow
        if (teleprompterText.isNotEmpty()) {
            TeleprompterOverlay(
                text = teleprompterText,
                scrollPosition = scrollPosition,
                fontSize = fontSize,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top status bar — IP address, streaming status, client count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Left: Streaming status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                val statusColor = when (streamingState) {
                    com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING -> Color(0xFF4CAF50)
                    com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.ERROR -> Color(0xFFF44336)
                    com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.IDLE -> Color(0xFF9E9E9E)
                }

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape)
                )

                Text(
                    text = when (streamingState) {
                        com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.STREAMING -> "Streaming"
                        com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.ERROR -> "Error"
                        com.wembassy.promptcam.viewmodels.PromptCamViewModel.StreamingState.IDLE -> "Idle"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 13.sp
                )
            }

            // Center: IP address display (the main thing users need to see)
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Phone IP",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = deviceIp,
                        color = Color(0xFFe94560),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Right: Client count
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Clients: ",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                    Text(
                        text = clientCount.toString(),
                        color = if (clientCount > 0) Color(0xFF4CAF50) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
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
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
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
