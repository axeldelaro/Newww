package com.astropro.xiaomi14u

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astropro.xiaomi14u.camera.AppScreen
import com.astropro.xiaomi14u.ui.screens.AstroLabScreen
import com.astropro.xiaomi14u.ui.screens.CalibrationScreen
import com.astropro.xiaomi14u.ui.screens.CameraScreen
import com.astropro.xiaomi14u.ui.screens.EphemerisScreen
import com.astropro.xiaomi14u.ui.screens.LensProbeScreen
import com.astropro.xiaomi14u.ui.theme.AstroProTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val camOk = results[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        val locOk = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        viewModel.onPermissionResult(camOk, locOk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val camGranted = hasPermission(Manifest.permission.CAMERA)
        val locGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        viewModel.onPermissionResult(camGranted, locGranted)
        if (!camGranted) {
            requestRequiredPermissions()
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val orientation by viewModel.orientationState.collectAsStateWithLifecycle()

            // Optional OLED screen dimming during multi-minute deep-sky stacking sessions
            LaunchedEffect(state.captureProgress.active, state.dimScreenDuringStack) {
                val attrs = window.attributes
                attrs.screenBrightness = if (state.captureProgress.active && state.dimScreenDuringStack) {
                    0.03f
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.attributes = attrs
            }

            AstroProTheme(nightVision = state.nightVision) {
                when (state.screen) {
                    AppScreen.CAMERA -> CameraScreen(
                        state = state,
                        orientation = orientation,
                        onAttachTextureView = { tv -> viewModel.attachPreviewTexture(tv) },
                        onRequestPermission = { requestRequiredPermissions() },
                        onToggleNightVision = { viewModel.toggleNightVision() },
                        onCycleFormat = { viewModel.cycleOutputFormat() },
                        onCycleTimer = { viewModel.cycleTimer() },
                        onSnapNpf = { viewModel.snapShutterToNpf() },
                        onToggleGridLevel = { viewModel.toggleGridLevel() },
                        onToggleHistogram = { viewModel.toggleHistogram() },
                        onToggleTopMenu = { viewModel.toggleTopMenu() },
                        onToggleDimDuringStack = { viewModel.toggleDimScreenDuringStack() },
                        onToggleFrontRear = { viewModel.toggleFrontRearLens() },
                        onSelectLens = { viewModel.selectLens(it) },
                        onSelectMode = { viewModel.selectCaptureMode(it) },
                        onToggleDial = { viewModel.toggleDial(it) },
                        onDismissDial = { viewModel.dismissDial() },
                        onUpdateManual = { viewModel.updateManualSettings(it) },
                        onUpdateModeOptions = { viewModel.updateModeOptions(it) },
                        onToggleFocusLoupe = { x, y -> viewModel.toggleFocusLoupe(x, y) },
                        onShutterPressed = { viewModel.onShutterPressed() },
                        onFinishCaptureEarly = { viewModel.finishCaptureEarly() },
                        onAbortCapture = { viewModel.abortCapture() },
                        onNavigate = { viewModel.navigateTo(it) }
                    )

                    AppScreen.LABO -> AstroLabScreen(
                        sessions = state.sessions,
                        activeSession = state.activeLabSession,
                        previewBitmap = state.labPreviewBitmap,
                        histogram = state.labHistogram,
                        params = state.labParams,
                        showOriginal = state.labShowOriginal,
                        isBusy = state.labBusy,
                        statusBanner = state.statusBanner,
                        onSelectSession = { viewModel.openSessionInLab(it) },
                        onDeleteSession = { viewModel.deleteSession(it) },
                        onUpdateParams = { viewModel.updateLabParams(it) },
                        onToggleOriginal = { viewModel.setLabShowOriginal(it) },
                        onExportTiff16 = { viewModel.exportLabTiff16(it) },
                        onExportFits32 = { viewModel.exportLabFits32(it) },
                        onExportJpegHd = { viewModel.exportLabJpegHd(it) },
                        onBack = { viewModel.navigateTo(AppScreen.CAMERA) }
                    )

                    AppScreen.EPHEMERIS -> EphemerisScreen(
                        location = state.location,
                        twilight = state.twilight,
                        moon = state.moon,
                        targets = state.targets,
                        compassTargetId = state.compassTargetId,
                        orientation = orientation,
                        onSelectLocation = { viewModel.setObserverLocation(it) },
                        onRequestGps = {
                            if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                                viewModel.tryReadLastKnownGpsLocation()
                            } else {
                                requestRequiredPermissions()
                            }
                        },
                        onSelectCompassTarget = { viewModel.selectCompassTarget(it) },
                        onApplyTargetToCamera = { viewModel.applyTargetToCamera(it) },
                        onRefresh = { viewModel.refreshEphemeris() },
                        onBack = { viewModel.navigateTo(AppScreen.CAMERA) }
                    )

                    AppScreen.LENS_PROBE -> LensProbeScreen(
                        rearLenses = state.rearLenses,
                        rawEntries = state.rawProbeEntries,
                        onExportJson = { viewModel.exportHalJsonReport() },
                        onBack = { viewModel.navigateTo(AppScreen.CAMERA) }
                    )

                    AppScreen.CALIBRATION -> CalibrationScreen(
                        masters = state.calibrationMasters,
                        activeDarkId = state.activeDarkId,
                        activeFlatId = state.activeFlatId,
                        activeBiasId = state.activeBiasId,
                        onSelectMaster = { viewModel.selectCalibrationMaster(it) },
                        onDeleteMaster = { viewModel.deleteCalibrationMaster(it) },
                        onLaunchCapture = { viewModel.launchCalibrationMode(it) },
                        onBack = { viewModel.navigateTo(AppScreen.CAMERA) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onActivityPause()
    }

    /**
     * Vibration-free hardware trigger support: Volume Up, Volume Down, Camera shutter key,
     * and Bluetooth remote shutters fire/stop astrophotography capture without touching the screen!
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.uiState.value.screen == AppScreen.CAMERA) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_CAMERA,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    viewModel.onShutterPressed()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }
}
