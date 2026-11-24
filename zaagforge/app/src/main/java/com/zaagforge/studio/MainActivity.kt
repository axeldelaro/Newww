package com.zaagforge.studio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.zaagforge.studio.ui.ZaagForgeApp
import com.zaagforge.studio.ui.ZaagForgeViewModel
import com.zaagforge.studio.ui.theme.ZaagForgeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ZaagForgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            ZaagForgeTheme {
                ZaagForgeApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        viewModel.pauseAudio()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.handleIncomingShareLink(sharedText)
            }
        }
    }
}
