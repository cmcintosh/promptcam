package com.wembassy.promptcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wembassy.promptcam.ui.CameraScreen
import com.wembassy.promptcam.ui.theme.PromptCamTheme
import com.wembassy.promptcam.viewmodels.PromptCamViewModel

/**
 * Main entry point for PromptCam.
 *
 * Sets up the Compose UI, requests camera and audio permissions using
 * ActivityResultContracts, and hosts the CameraScreen composable.
 *
 * The activity is locked to landscape orientation (set in AndroidManifest.xml).
 */
class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private var permissionsGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted.value = results.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if permissions are already granted
        permissionsGranted.value = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        setContent {
            PromptCamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val permissionsGrantedState by permissionsGranted

                    if (permissionsGrantedState) {
                        val viewModel: PromptCamViewModel = viewModel()
                        CameraScreen(viewModel = viewModel)
                    } else {
                        PermissionRequestScreen(
                            onRequestPermissions = {
                                permissionLauncher.launch(requiredPermissions)
                            }
                        )
                    }
                }
            }
        }

        // Request permissions if not granted
        if (!permissionsGranted.value) {
            permissionLauncher.launch(requiredPermissions)
        }
    }
}

/**
 * Simple screen shown when camera/audio permissions have not been granted.
 */
@Composable
private fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit
) {
    LaunchedEffect(Unit) {
        onRequestPermissions()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "PromptCam needs camera and microphone permissions to function.\nPlease grant permissions to continue.",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}
