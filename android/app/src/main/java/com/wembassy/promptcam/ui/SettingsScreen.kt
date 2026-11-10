package com.wembassy.promptcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wembassy.promptcam.Settings.CameraLens
import com.wembassy.promptcam.Settings.VideoResolution
import com.wembassy.promptcam.Settings.AppSettings

/**
 * Settings dialog for PromptCam configuration.
 *
 * Shows:
 * - Resolution selector (720p / 1080p) as RadioButtons
 * - Bitrate slider (1-10 Mbps)
 * - Camera selection dropdown (Front / Back)
 * - Port number text fields (video port and control port)
 * - Save and Cancel buttons
 *
 * @param settings Current app settings
 * @param onSave Callback invoked when user saves settings
 * @param onCancel Callback invoked when user cancels
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onCancel: () -> Unit
) {
    // Local state for editing
    var selectedResolution by remember { mutableStateOf(settings.resolution) }
    var bitrateMbps by remember { mutableFloatStateOf(settings.bitrate / 1_000_000f) }
    var selectedCamera by remember { mutableStateOf(settings.cameraLens) }
    var videoPort by remember { mutableStateOf(settings.videoPort.toString()) }
    var controlPort by remember { mutableStateOf(settings.controlPort.toString()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Resolution selector
                Text(
                    text = "Resolution",
                    style = MaterialTheme.typography.titleSmall
                )
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VideoResolution.entries.forEach { resolution ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (resolution == selectedResolution),
                                    onClick = { selectedResolution = resolution },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (resolution == selectedResolution),
                                onClick = null // Click handled by selectable
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = resolution.displayName)
                        }
                    }
                }

                // Bitrate slider
                Text(
                    text = "Bitrate: ${String.format("%.1f", bitrateMbps)} Mbps",
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = bitrateMbps,
                    onValueChange = { bitrateMbps = it },
                    valueRange = 1f..10f,
                    steps = 17, // 0.5 Mbps increments
                    modifier = Modifier.fillMaxWidth()
                )

                // Camera selection
                Text(
                    text = "Camera",
                    style = MaterialTheme.typography.titleSmall
                )
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CameraLens.entries.forEach { lens ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (lens == selectedCamera),
                                    onClick = { selectedCamera = lens },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (lens == selectedCamera),
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = lens.displayName)
                        }
                    }
                }

                // Port number fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = videoPort,
                        onValueChange = { videoPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Video Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = controlPort,
                        onValueChange = { controlPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Control Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newSettings = AppSettings(
                        cameraLens = selectedCamera,
                        resolution = selectedResolution,
                        bitrate = (bitrateMbps * 1_000_000f).toInt(),
                        videoPort = videoPort.toIntOrNull() ?: settings.videoPort,
                        controlPort = controlPort.toIntOrNull() ?: settings.controlPort
                    )
                    onSave(newSettings)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
