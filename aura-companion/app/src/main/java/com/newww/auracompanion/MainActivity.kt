package com.newww.auracompanion

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.newww.auracompanion.ui.AuraCompanionApp
import com.newww.auracompanion.viewmodel.AuraViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AuraViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importPhotoForActiveCharacter(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(perms.toTypedArray())

        setContent {
            val state by viewModel.state.collectAsState()
            val preset = state.themePreset
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(preset.primaryHex),
                    secondary = Color(preset.secondaryHex),
                    background = Color(preset.bgHex),
                    surface = Color(preset.surfaceHex)
                )
            ) {
                AuraCompanionApp(
                    viewModel = viewModel,
                    onPickPhoto = { photoPickerLauncher.launch("image/*") }
                )
            }
        }
    }
}
