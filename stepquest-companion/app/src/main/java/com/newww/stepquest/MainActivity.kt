package com.newww.stepquest

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.newww.stepquest.ui.StepQuestApp
import com.newww.stepquest.viewmodel.StepQuestViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StepQuestViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF6C768),
                    secondary = Color(0xFF6CF0B5),
                    background = Color(0xFF0E0C17),
                    surface = Color(0xFF1A1629)
                )
            ) {
                StepQuestApp(
                    viewModel = viewModel,
                    onRequestStepPermission = { requestRuntimePermissions() }
                )
            }
        }
    }

    private fun requestRuntimePermissions() {
        val toRequest = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (toRequest.isNotEmpty()) permissionsLauncher.launch(toRequest.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }
}
