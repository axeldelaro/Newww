package com.kinetix.coach.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetix.coach.ui.theme.BorderHairline
import com.kinetix.coach.ui.theme.CharcoalSurface
import com.kinetix.coach.ui.theme.CrispWhite
import com.kinetix.coach.ui.theme.ElevatedSurface
import com.kinetix.coach.ui.theme.FormFault
import com.kinetix.coach.ui.theme.FormFaultDim
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.FormGoodDim
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SignalOrangeDim
import com.kinetix.coach.ui.theme.SteelGray
import com.kinetix.coach.vision.ExerciseVideoGuide
import kotlinx.coroutines.delay

private val PLAYBACK_SPEEDS = listOf(0.5f, 1.0f, 1.5f)

/**
 * Native 100% offline Swiss-Industrial video player reading MP4s straight from
 * APK assets (`assets/videos/<fileName>`) via TextureView + MediaPlayer.
 */
@Composable
fun ExerciseVideoPlayer(
    guide: ExerciseVideoGuide,
    modifier: Modifier = Modifier,
    showPhasesAndChecklist: Boolean = true
) {
    val context = LocalContext.current
    var isPlaying by remember(guide.videoFileName) { mutableStateOf(true) }
    var speed by remember(guide.videoFileName) { mutableFloatStateOf(1.0f) }
    var progress by remember(guide.videoFileName) { mutableFloatStateOf(0f) }
    var isPrepared by remember(guide.videoFileName) { mutableStateOf(false) }
    var activeTab by remember(guide.videoFileName) { mutableIntStateOf(0) } // 0=4 Phases, 1=Checklist

    val mediaPlayerHolder = remember(guide.videoFileName) { arrayOfNulls<MediaPlayer>(1) }
    val surfaceHolder = remember(guide.videoFileName) { arrayOfNulls<Surface>(1) }

    fun releasePlayer() {
        runCatching {
            mediaPlayerHolder[0]?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.setSurface(null)
                mp.release()
            }
        }
        mediaPlayerHolder[0] = null
        runCatching { surfaceHolder[0]?.release() }
        surfaceHolder[0] = null
        isPrepared = false
    }

    fun applySpeed(mp: MediaPlayer, targetSpeed: Float) {
        runCatching {
            val wasPlaying = mp.isPlaying
            val params = mp.playbackParams
            params.speed = targetSpeed
            mp.playbackParams = params
            if (!wasPlaying && mp.isPlaying) {
                mp.pause()
            }
        }
    }

    fun prepareWithSurface(surfaceTexture: SurfaceTexture) {
        releasePlayer()
        runCatching {
            val surface = Surface(surfaceTexture)
            surfaceHolder[0] = surface
            val mp = MediaPlayer()
            context.assets.openFd("videos/${guide.videoFileName}").use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { readyMp ->
                isPrepared = true
                applySpeed(readyMp, speed)
                if (isPlaying) {
                    readyMp.start()
                }
            }
            mp.prepareAsync()
            mediaPlayerHolder[0] = mp
        }
    }

    DisposableEffect(guide.videoFileName) {
        onDispose {
            releasePlayer()
        }
    }

    // Smooth playback progress ticker while playing
    LaunchedEffect(guide.videoFileName, isPlaying, isPrepared) {
        while (isPlaying && isPrepared) {
            val mp = mediaPlayerHolder[0]
            if (mp != null) {
                runCatching {
                    val dur = mp.duration
                    if (dur > 0) {
                        progress = (mp.currentPosition.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    }
                }
            }
            delay(50L)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1:1 Crisp Video Studio Viewport
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ObsidianBg,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(ObsidianBg)
                        .clickable {
                            val mp = mediaPlayerHolder[0] ?: return@clickable
                            if (!isPrepared) return@clickable
                            runCatching {
                                if (isPlaying) {
                                    mp.pause()
                                    isPlaying = false
                                } else {
                                    applySpeed(mp, speed)
                                    mp.start()
                                    isPlaying = true
                                }
                            }
                        }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                isOpaque = true
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        prepareWithSurface(st)
                                    }

                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        releasePlayer()
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                                }
                            }
                        },
                        update = { tv ->
                            if (mediaPlayerHolder[0] == null && tv.isAvailable) {
                                tv.surfaceTexture?.let { prepareWithSurface(it) }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (!isPlaying && isPrepared) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(ObsidianBg.copy(alpha = 0.78f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Lecture",
                                tint = SignalOrange,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                // Live Playback Progress Track
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = SignalOrange,
                    trackColor = ElevatedSurface
                )

                // Compact Swiss Industrial Control Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CharcoalSurface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Play / Pause
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = SignalOrange,
                            modifier = Modifier
                                .size(34.dp)
                                .clickable {
                                    val mp = mediaPlayerHolder[0] ?: return@clickable
                                    if (!isPrepared) return@clickable
                                    runCatching {
                                        if (isPlaying) {
                                            mp.pause()
                                            isPlaying = false
                                        } else {
                                            applySpeed(mp, speed)
                                            mp.start()
                                            isPlaying = true
                                        }
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Lecture",
                                    tint = CrispWhite,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        // Restart
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = ElevatedSurface,
                            border = BorderStroke(1.dp, BorderHairline),
                            modifier = Modifier
                                .size(34.dp)
                                .clickable {
                                    val mp = mediaPlayerHolder[0] ?: return@clickable
                                    if (!isPrepared) return@clickable
                                    runCatching {
                                        mp.seekTo(0)
                                        progress = 0f
                                        if (!isPlaying) {
                                            applySpeed(mp, speed)
                                            mp.start()
                                            isPlaying = true
                                        }
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Replay,
                                    contentDescription = "Recommencer",
                                    tint = CrispWhite,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }

                    // Speed Selector Pills (0.5× · 1.0× · 1.5×)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        PLAYBACK_SPEEDS.forEach { sp ->
                            val selected = speed == sp
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (selected) SignalOrangeDim else ElevatedSurface,
                                border = BorderStroke(1.dp, if (selected) SignalOrange else BorderHairline),
                                modifier = Modifier.clickable {
                                    speed = sp
                                    val mp = mediaPlayerHolder[0]
                                    if (mp != null && isPrepared) {
                                        applySpeed(mp, sp)
                                    }
                                }
                            ) {
                                Text(
                                    text = "${sp}×",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) SignalOrange else SteelGray,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tempo, Target Angles & Breathing Strip
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ObsidianBg,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = guide.targetAnglesFr,
                        style = MaterialTheme.typography.labelMedium,
                        color = CrispWhite,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = SignalOrangeDim,
                        border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "TEMPO ${guide.tempo}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.Air,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFF60A5FA),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = guide.breathingFr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelGray
                    )
                }
            }
        }

        // Tabbed 4-Step Execution Timeline + À faire / À éviter Checklist
        if (showPhasesAndChecklist) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoGuideTabPill(
                        label = "4 PHASES D'EXÉCUTION",
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    VideoGuideTabPill(
                        label = "À FAIRE / À ÉVITER",
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (activeTab == 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            guide.phases.forEach { phase ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = SignalOrangeDim,
                                        border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.6f)),
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${phase.number}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = SignalOrange,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = phase.title,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = CrispWhite,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = phase.detail,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = SteelGray
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        guide.checklist.forEach { pair ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ObsidianBg,
                                border = BorderStroke(1.dp, BorderHairline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = FormGoodDim,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = FormGood,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = pair.doCue,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = CrispWhite
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = FormFaultDim,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DoNotDisturbOn,
                                                contentDescription = null,
                                                tint = FormFault,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = pair.avoidCue,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = CrispWhite
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoGuideTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ElevatedSurface else ObsidianBg,
        border = BorderStroke(1.dp, if (selected) SignalOrange else BorderHairline)
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) SignalOrange else SteelGray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Full-bleed dark modal sheet content for previewing any Vision exercise demo right before a set.
 */
@Composable
fun ExerciseDemoSheetContent(
    title: String,
    subtitle: String,
    guide: ExerciseVideoGuide,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DÉMONSTRATION BIOMÉCANIQUE",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalOrange,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = CrispWhite
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = SteelGray
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = SteelGray)
            }
        }

        ExerciseVideoPlayer(
            guide = guide,
            showPhasesAndChecklist = true
        )
    }
}
