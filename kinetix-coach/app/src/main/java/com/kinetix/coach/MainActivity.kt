package com.kinetix.coach

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kinetix.coach.ui.coach.CoachScreen
import com.kinetix.coach.ui.coach.CoachViewModel
import com.kinetix.coach.ui.components.HairlineDivider
import com.kinetix.coach.ui.encyclopedia.EncyclopediaScreen
import com.kinetix.coach.ui.encyclopedia.EncyclopediaViewModel
import com.kinetix.coach.ui.theme.CharcoalSurface
import com.kinetix.coach.ui.theme.KinetixTheme
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SteelGray
import com.kinetix.coach.ui.tracker.TrackerScreen
import com.kinetix.coach.ui.tracker.TrackerViewModel
import com.kinetix.coach.ui.vision.VisionScreen
import com.kinetix.coach.ui.vision.VisionViewModel

private enum class KinetixTab(val labelFr: String, val icon: ImageVector) {
    VISION("Vision", Icons.Filled.Videocam),
    COACH("Coach", Icons.AutoMirrored.Filled.Chat),
    ENCYCLOPEDIA("Encyclopédie", Icons.AutoMirrored.Filled.MenuBook),
    TRACKER("Suivi", Icons.Filled.Assessment)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#FF0B0C0E")),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#FF14161A"))
        )
        setContent {
            KinetixTheme {
                KinetixRootShell()
            }
        }
    }
}

@Composable
private fun KinetixRootShell() {
    var activeTab by rememberSaveable { mutableStateOf(KinetixTab.VISION) }

    val visionVm: VisionViewModel = viewModel()
    val coachVm: CoachViewModel = viewModel()
    val encyclopediaVm: EncyclopediaViewModel = viewModel()
    val trackerVm: TrackerViewModel = viewModel()

    val askCoachAndSwitch: (String) -> Unit = { prompt ->
        activeTab = KinetixTab.COACH
        coachVm.sendMessage(prompt)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding(),
        containerColor = ObsidianBg,
        bottomBar = {
            KinetixBottomBar(
                activeTab = activeTab,
                onSelectTab = { activeTab = it }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ObsidianBg)
        ) {
            when (activeTab) {
                KinetixTab.VISION -> VisionScreen(
                    viewModel = visionVm,
                    onAnalyzeWithCoach = askCoachAndSwitch
                )
                KinetixTab.COACH -> CoachScreen(viewModel = coachVm)
                KinetixTab.ENCYCLOPEDIA -> EncyclopediaScreen(
                    viewModel = encyclopediaVm,
                    onAskCoach = askCoachAndSwitch
                )
                KinetixTab.TRACKER -> TrackerScreen(viewModel = trackerVm)
            }
        }
    }
}

@Composable
private fun KinetixBottomBar(
    activeTab: KinetixTab,
    onSelectTab: (KinetixTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CharcoalSurface)
            .navigationBarsPadding()
    ) {
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KinetixTab.entries.forEach { tab ->
                val selected = tab == activeTab
                val tint = if (selected) SignalOrange else SteelGray
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(2.dp)
                            .background(if (selected) SignalOrange else CharcoalSurface)
                    )
                    Spacer(Modifier.height(7.dp))
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.labelFr,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.labelFr,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
