package com.astropro.xiaomi14u

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.media.Image
import android.view.TextureView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astropro.xiaomi14u.camera.AppScreen
import com.astropro.xiaomi14u.camera.AstroCameraController
import com.astropro.xiaomi14u.camera.AstroSessionItem
import com.astropro.xiaomi14u.camera.CalibrationKind
import com.astropro.xiaomi14u.camera.CalibrationMasterInfo
import com.astropro.xiaomi14u.camera.CaptureMode
import com.astropro.xiaomi14u.camera.CaptureProgressState
import com.astropro.xiaomi14u.camera.FocusLoupeState
import com.astropro.xiaomi14u.camera.FocusPeakingState
import com.astropro.xiaomi14u.camera.HistogramData
import com.astropro.xiaomi14u.camera.LensInfo
import com.astropro.xiaomi14u.camera.LevelOrientationState
import com.astropro.xiaomi14u.camera.ManualSettings
import com.astropro.xiaomi14u.camera.ModeGuideData
import com.astropro.xiaomi14u.camera.ModeOptions
import com.astropro.xiaomi14u.camera.NpfCalculator
import com.astropro.xiaomi14u.camera.OutputFormatOption
import com.astropro.xiaomi14u.camera.ProDialType
import com.astropro.xiaomi14u.camera.RawProbeEntry
import com.astropro.xiaomi14u.camera.SensorOrientationTracker
import com.astropro.xiaomi14u.camera.StarTrailStyle
import com.astropro.xiaomi14u.camera.TimerOption
import com.astropro.xiaomi14u.camera.XiaomiLensProbe
import com.astropro.xiaomi14u.ephemeris.AstroEphemeris
import com.astropro.xiaomi14u.ephemeris.MoonSummary
import com.astropro.xiaomi14u.ephemeris.ObserverLocation
import com.astropro.xiaomi14u.ephemeris.TargetCategory
import com.astropro.xiaomi14u.ephemeris.TargetVisibility
import com.astropro.xiaomi14u.ephemeris.TwilightSummary
import com.astropro.xiaomi14u.nativeengine.AstroNativeEngine
import com.astropro.xiaomi14u.nativeengine.LabParameters
import com.astropro.xiaomi14u.nativeengine.LabPreset
import com.astropro.xiaomi14u.storage.AstroPreferences
import com.astropro.xiaomi14u.storage.AstroSessionStore
import com.astropro.xiaomi14u.timelapse.TimelapseEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

data class AstroAppUiState(
    val screen: AppScreen = AppScreen.CAMERA,
    val nightVision: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val rearLenses: List<LensInfo> = emptyList(),
    val frontLens: LensInfo? = null,
    val activeLens: LensInfo? = null,
    val usingFront: Boolean = false,
    val rawProbeEntries: List<RawProbeEntry> = emptyList(),
    val captureMode: CaptureMode = CaptureMode.ASTRO_PRO,
    val manualSettings: ManualSettings = ManualSettings(),
    val activeDial: ProDialType? = null,
    val modeOptions: ModeOptions = ModeOptions(),
    val timerOption: TimerOption = TimerOption.OFF,
    val outputFormat: OutputFormatOption = OutputFormatOption.DNG_16B,
    val showHistogram: Boolean = true,
    val showGridLevel: Boolean = true,
    val focusPeakingEnabled: Boolean = false,
    val showTopMenu: Boolean = false,
    val showModeGuide: Boolean = false,
    val dimScreenDuringStack: Boolean = false,
    val histogram: HistogramData = HistogramData(),
    val focusLoupe: FocusLoupeState = FocusLoupeState(),
    val focusPeaking: FocusPeakingState = FocusPeakingState(),
    val captureProgress: CaptureProgressState = CaptureProgressState(),
    val sessions: List<AstroSessionItem> = emptyList(),
    val lastThumbBitmap: Bitmap? = null,
    val calibrationMasters: List<CalibrationMasterInfo> = emptyList(),
    val activeDarkId: String? = null,
    val activeFlatId: String? = null,
    val activeBiasId: String? = null,
    // 16-bit Labo state
    val activeLabSession: AstroSessionItem? = null,
    val labParams: LabParameters = LabParameters(),
    val activeLabPreset: LabPreset? = LabPreset.AUTO_STF,
    val labPreviewBitmap: Bitmap? = null,
    val labHistogram: HistogramData = HistogramData(),
    val labShowOriginal: Boolean = false,
    val labBusy: Boolean = false,
    // Ephemeris state
    val location: ObserverLocation = AstroEphemeris.PRESET_LOCATIONS.first(),
    val twilight: TwilightSummary = AstroEphemeris.computeTwilightSummary(System.currentTimeMillis(), AstroEphemeris.PRESET_LOCATIONS.first()),
    val moon: MoonSummary = AstroEphemeris.computeMoonSummary(System.currentTimeMillis(), AstroEphemeris.PRESET_LOCATIONS.first()),
    val targets: List<TargetVisibility> = AstroEphemeris.computeTargetVisibilities(System.currentTimeMillis(), AstroEphemeris.PRESET_LOCATIONS.first()),
    val compassTargetId: String = "sgr_a",
    val statusBanner: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val nativeEngine = AstroNativeEngine()
    private val store = AstroSessionStore(appContext)
    private val preferences = AstroPreferences(appContext)
    val orientationTracker = SensorOrientationTracker(appContext)

    private var probeResult: XiaomiLensProbe.ProbeResult? = null
    private var captureJob: Job? = null
    private var labRenderJob: Job? = null
    private var bannerJob: Job? = null
    private val requestFinishEarly = AtomicBoolean(false)

    // Reusable analysis buffers (avoid GC churn on live preview)
    private val previewHistR = IntArray(64)
    private val previewHistG = IntArray(64)
    private val previewHistB = IntArray(64)
    private val peakingBuf = FloatArray(1600) // up to 800 normalized (x, y) edge points
    private var loupeBitmapBuf: Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)

    private val _uiState: MutableStateFlow<AstroAppUiState> = run {
        val saved = preferences.load()
        val now = System.currentTimeMillis()
        MutableStateFlow(
            AstroAppUiState(
                nightVision = saved.nightVision,
                showGridLevel = saved.showGridLevel,
                showHistogram = saved.showHistogram,
                focusPeakingEnabled = saved.focusPeakingEnabled,
                outputFormat = saved.outputFormat,
                timerOption = saved.timerOption,
                dimScreenDuringStack = saved.dimScreenDuringStack,
                location = saved.location,
                twilight = AstroEphemeris.computeTwilightSummary(now, saved.location),
                moon = AstroEphemeris.computeMoonSummary(now, saved.location),
                targets = AstroEphemeris.computeTargetVisibilities(now, saved.location)
            )
        )
    }
    val uiState: StateFlow<AstroAppUiState> = _uiState.asStateFlow()

    val orientationState: StateFlow<LevelOrientationState> = orientationTracker.state

    val cameraController: AstroCameraController = AstroCameraController(
        context = appContext,
        onPreviewYuvFrame = { img -> processLivePreviewFrame(img) },
        onCameraError = { msg -> showBanner(msg) }
    )

    init {
        probeHardwareAndRestoreState()
    }

    private fun probeHardwareAndRestoreState() {
        viewModelScope.launch(Dispatchers.Default) {
            val probe = XiaomiLensProbe.probeAllCameras(appContext)
            probeResult = probe

            // Default to primary 1x (23mm LYT-900) lens if available, else first rear lens
            val defaultLens = probe.rearLenses.firstOrNull { it.zoomBadge == "1" }
                ?: probe.rearLenses.firstOrNull()

            val sessions = store.listSessions()
            val lastThumb = sessions.firstOrNull()?.thumbPath?.takeIf { it.isNotEmpty() && File(it).exists() }
                ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

            val calMasters = store.listCalibrationMasters()
            val latestDark = calMasters.firstOrNull { it.kind == CalibrationKind.DARK }
            val latestFlat = calMasters.firstOrNull { it.kind == CalibrationKind.FLAT }
            val latestBias = calMasters.firstOrNull { it.kind == CalibrationKind.BIAS }

            latestDark?.let { nativeEngine.loadCalibrationMaster(it.filePath) }
            latestFlat?.let { nativeEngine.loadCalibrationMaster(it.filePath) }
            latestBias?.let { nativeEngine.loadCalibrationMaster(it.filePath) }

            val initialManual = if (defaultLens != null) {
                val npf = NpfCalculator.snapToCameraShutter(
                    defaultLens.npfSeconds(),
                    defaultLens.minExposureSec,
                    defaultLens.maxExposureSec
                )
                ManualSettings(
                    wbAuto = false,
                    wbKelvin = 4200,
                    focusAuto = false,
                    focusDistance = 0.0f,
                    shutterAuto = false,
                    shutterSeconds = npf,
                    isoAuto = false,
                    iso = 1600.coerceIn(defaultLens.minIso, defaultLens.maxIso),
                    aperture = defaultLens.defaultAperture,
                    evCompensation = 0
                )
            } else {
                ManualSettings()
            }

            _uiState.update { st ->
                st.copy(
                    rearLenses = probe.rearLenses,
                    frontLens = probe.frontLens,
                    activeLens = defaultLens,
                    rawProbeEntries = probe.allRawEntries,
                    manualSettings = initialManual,
                    sessions = sessions,
                    lastThumbBitmap = lastThumb,
                    calibrationMasters = calMasters,
                    activeDarkId = latestDark?.id,
                    activeFlatId = latestFlat?.id,
                    activeBiasId = latestBias?.id
                )
            }
            refreshEphemeris()
            withContext(Dispatchers.Main) {
                tryAttachPendingPreview()
            }
        }
    }

    @Volatile
    private var pendingTextureView: TextureView? = null

    fun onPermissionResult(cameraGranted: Boolean, locationGranted: Boolean) {
        _uiState.update { it.copy(cameraPermissionGranted = cameraGranted) }
        if (cameraGranted) {
            tryAttachPendingPreview()
        }
        if (locationGranted) {
            tryReadLastKnownGpsLocation()
        }
    }

    @SuppressLint("MissingPermission")
    fun tryReadLastKnownGpsLocation() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                val loc = providers.firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                if (loc != null) {
                    val obs = ObserverLocation(
                        name = String.format(Locale.US, "GPS (%.2f°, %.2f°)", loc.latitude, loc.longitude),
                        latitudeDeg = loc.latitude,
                        longitudeDeg = loc.longitude
                    )
                    setObserverLocation(obs)
                }
            }
        }
    }

    fun attachPreviewTexture(textureView: TextureView) {
        pendingTextureView = textureView
        tryAttachPendingPreview()
    }

    private fun tryAttachPendingPreview() {
        val tv = pendingTextureView ?: return
        val st = _uiState.value
        if (!st.cameraPermissionGranted) return
        val lens = st.activeLens ?: return
        cameraController.attachTextureView(tv, lens, st.manualSettings, st.captureMode)
    }

    fun onActivityResume() {
        orientationTracker.start()
        val st = _uiState.value
        if (st.cameraPermissionGranted && st.screen == AppScreen.CAMERA) {
            val lens = st.activeLens
            if (lens != null) {
                cameraController.resumeCamera(lens, st.manualSettings, st.captureMode)
            }
        }
    }

    fun onActivityPause() {
        orientationTracker.stop()
        if (!_uiState.value.captureProgress.active) {
            cameraController.closeCamera()
        }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(screen = screen, showTopMenu = false, showModeGuide = false, activeDial = null) }
        if (screen == AppScreen.EPHEMERIS) {
            refreshEphemeris()
        } else if (screen == AppScreen.LABO) {
            ensureLabSessionLoaded()
        }
    }

    fun toggleNightVision() {
        val next = !_uiState.value.nightVision
        preferences.setNightVision(next)
        _uiState.update { it.copy(nightVision = next) }
    }

    fun toggleHistogram() {
        val next = !_uiState.value.showHistogram
        preferences.setShowHistogram(next)
        _uiState.update { it.copy(showHistogram = next) }
    }

    fun toggleGridLevel() {
        val next = !_uiState.value.showGridLevel
        preferences.setShowGridLevel(next)
        _uiState.update { it.copy(showGridLevel = next) }
    }

    fun toggleFocusPeaking() {
        val next = !_uiState.value.focusPeakingEnabled
        preferences.setFocusPeaking(next)
        _uiState.update {
            it.copy(
                focusPeakingEnabled = next,
                focusPeaking = if (next) it.focusPeaking else FocusPeakingState()
            )
        }
    }

    fun toggleTopMenu(show: Boolean? = null) {
        _uiState.update {
            val nextShow = show ?: !it.showTopMenu
            it.copy(showTopMenu = nextShow, showModeGuide = if (nextShow) false else it.showModeGuide)
        }
    }

    fun openModeGuide() {
        _uiState.update { it.copy(showModeGuide = true, showTopMenu = false, activeDial = null) }
    }

    fun dismissModeGuide() {
        _uiState.update { it.copy(showModeGuide = false) }
    }

    fun toggleDimScreenDuringStack() {
        val next = !_uiState.value.dimScreenDuringStack
        preferences.setDimScreenDuringStack(next)
        _uiState.update { it.copy(dimScreenDuringStack = next) }
    }

    fun cycleTimer() {
        val values = TimerOption.entries
        val next = values[(values.indexOf(_uiState.value.timerOption) + 1) % values.size]
        preferences.setTimerOption(next)
        _uiState.update { it.copy(timerOption = next) }
    }

    fun cycleOutputFormat() {
        val next = if (_uiState.value.outputFormat == OutputFormatOption.DNG_16B) {
            OutputFormatOption.RAW_PLUS_JPG
        } else {
            OutputFormatOption.DNG_16B
        }
        preferences.setOutputFormat(next)
        _uiState.update { it.copy(outputFormat = next) }
    }

    fun selectCaptureMode(mode: CaptureMode, showHint: Boolean = true) {
        if (_uiState.value.captureProgress.active) return
        _uiState.update { st ->
            val lens = st.activeLens
            var manual = st.manualSettings
            if (mode == CaptureMode.LUNAR_PLANETARY && lens != null) {
                // Fast planetary shutter default so Moon isn't blown out!
                manual = manual.copy(shutterAuto = false, shutterSeconds = 1.0 / 250.0, isoAuto = false, iso = 200.coerceIn(lens.minIso, lens.maxIso))
            } else if (mode == CaptureMode.DEEP_SKY && lens != null) {
                val npf = NpfCalculator.snapToCameraShutter(lens.npfSeconds(manual.aperture), lens.minExposureSec, lens.maxExposureSec)
                manual = manual.copy(shutterAuto = false, shutterSeconds = npf, focusAuto = false, focusDistance = 0.0f)
            }
            cameraController.updateManualSettings(manual, mode)
            st.copy(captureMode = mode, manualSettings = manual, activeDial = null)
        }
        if (showHint) {
            showBanner(ModeGuideData.guideFor(mode).quickHint)
        }
    }

    fun applyModeGuidePreset(mode: CaptureMode) {
        if (_uiState.value.captureProgress.active) {
            dismissModeGuide()
            return
        }
        val st = _uiState.value
        val guide = ModeGuideData.guideFor(mode)
        val preset = guide.recommendedPreset

        // Pick exact Xiaomi 14 Ultra lens by zoomBadge ("0.5", "1", "3.2", "5"), or closest equivalent focal length
        val targetLens = st.rearLenses.firstOrNull { it.zoomBadge == preset.recommendedZoomBadge }
            ?: st.rearLenses.minByOrNull { abs(it.focalLength35mmEq - preset.recommendedEqMm) }
            ?: st.activeLens

        val updatedOptions = preset.applyToModeOptions(st.modeOptions)

        val updatedManual = if (targetLens != null) {
            val ap = targetLens.defaultAperture
            val shutter = if (preset.useNpfShutter) {
                NpfCalculator.snapToCameraShutter(
                    targetLens.npfSeconds(ap),
                    targetLens.minExposureSec,
                    targetLens.maxExposureSec
                )
            } else {
                preset.shutterSec.coerceIn(targetLens.minExposureSec, targetLens.maxExposureSec)
            }
            st.manualSettings.copy(
                wbAuto = false,
                wbKelvin = preset.wbKelvin,
                focusAuto = !preset.focusInfinity,
                focusDistance = if (preset.focusInfinity) 0.0f else st.manualSettings.focusDistance,
                shutterAuto = false,
                shutterSeconds = shutter,
                isoAuto = false,
                iso = preset.iso.coerceIn(targetLens.minIso, targetLens.maxIso),
                aperture = ap,
                evCompensation = 0
            )
        } else {
            st.manualSettings.copy(
                wbAuto = false,
                wbKelvin = preset.wbKelvin,
                focusAuto = !preset.focusInfinity,
                focusDistance = 0.0f,
                shutterAuto = false,
                shutterSeconds = preset.shutterSec,
                isoAuto = false,
                iso = preset.iso,
                evCompensation = 0
            )
        }

        val lensChanged = targetLens != null && targetLens.id != st.activeLens?.id
        _uiState.update {
            it.copy(
                captureMode = mode,
                activeLens = targetLens ?: it.activeLens,
                usingFront = targetLens?.isFront ?: it.usingFront,
                manualSettings = updatedManual,
                modeOptions = updatedOptions,
                showModeGuide = false,
                showTopMenu = false,
                activeDial = null
            )
        }

        if (targetLens != null) {
            if (lensChanged) {
                cameraController.switchLens(targetLens, updatedManual, mode)
            } else {
                cameraController.updateManualSettings(updatedManual, mode)
            }
        }

        val lensLabel = targetLens?.displayName ?: preset.recommendedLensLabel
        showBanner(
            "${guide.title} prêt · $lensLabel · ISO ${updatedManual.iso} · ${NpfCalculator.formatShutter(updatedManual.shutterSeconds)}"
        )
    }

    fun selectLens(lens: LensInfo) {
        if (_uiState.value.captureProgress.active) return
        val st = _uiState.value
        val ap = lens.availableApertures.minByOrNull { abs(it - st.manualSettings.aperture) } ?: lens.defaultAperture
        val iso = st.manualSettings.iso.coerceIn(lens.minIso, lens.maxIso)
        val clampedShutter = st.manualSettings.shutterSeconds.coerceIn(lens.minExposureSec, lens.maxExposureSec)
        val updatedManual = st.manualSettings.copy(
            aperture = ap,
            iso = iso,
            shutterSeconds = clampedShutter
        )
        _uiState.update {
            it.copy(
                activeLens = lens,
                usingFront = lens.isFront,
                manualSettings = updatedManual,
                activeDial = null
            )
        }
        cameraController.switchLens(lens, updatedManual, st.captureMode)
    }

    fun toggleFrontRearLens() {
        val st = _uiState.value
        if (st.captureProgress.active) return
        if (st.usingFront) {
            val back = st.rearLenses.firstOrNull { it.zoomBadge == "1" } ?: st.rearLenses.firstOrNull()
            if (back != null) selectLens(back)
        } else {
            val front = st.frontLens
            if (front != null) selectLens(front) else showBanner("Caméra frontale indisponible")
        }
    }

    fun toggleDial(dial: ProDialType) {
        _uiState.update { st ->
            st.copy(activeDial = if (st.activeDial == dial) null else dial)
        }
    }

    fun dismissDial() {
        _uiState.update { it.copy(activeDial = null) }
    }

    fun updateManualSettings(transform: (ManualSettings) -> ManualSettings) {
        val st = _uiState.value
        val next = transform(st.manualSettings)
        _uiState.update { it.copy(manualSettings = next) }
        cameraController.updateManualSettings(next, st.captureMode)
    }

    fun snapShutterToNpf(declinationDeg: Double = 0.0) {
        val st = _uiState.value
        val lens = st.activeLens ?: return
        val npf = lens.npfSeconds(st.manualSettings.aperture, declinationDeg)
        val snapped = NpfCalculator.snapToCameraShutter(npf, lens.minExposureSec, lens.maxExposureSec)
        updateManualSettings { it.copy(shutterAuto = false, shutterSeconds = snapped) }
        showBanner(String.format(Locale.US, "NPF %s → %s (limite %.1fs)", lens.displayName, NpfCalculator.formatShutter(snapped), npf))
    }

    fun updateModeOptions(transform: (ModeOptions) -> ModeOptions) {
        _uiState.update { it.copy(modeOptions = transform(it.modeOptions)) }
    }

    fun toggleFocusLoupe(normX: Float? = null, normY: Float? = null) {
        _uiState.update { st ->
            val enable = if (normX != null && normY != null) true else !st.focusLoupe.enabled
            st.copy(
                focusLoupe = st.focusLoupe.copy(
                    enabled = enable,
                    normX = normX?.coerceIn(0.1f, 0.9f) ?: st.focusLoupe.normX,
                    normY = normY?.coerceIn(0.1f, 0.9f) ?: st.focusLoupe.normY,
                    history = if (enable) st.focusLoupe.history else emptyList()
                )
            )
        }
    }

    private fun processLivePreviewFrame(image: Image) {
        val st = _uiState.value
        if (st.screen != AppScreen.CAMERA) return
        if (!st.showHistogram && !st.focusLoupe.enabled && !st.focusPeakingEnabled) return
        if (image.planes.size < 3) return

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var histCopy = st.histogram
        var loupeCopy = st.focusLoupe

        if (st.showHistogram || st.focusLoupe.enabled) {
            val computeLoupe = st.focusLoupe.enabled
            val metrics = nativeEngine.analyzePreviewYuv(
                yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
                uPlane.buffer, uPlane.rowStride, uPlane.pixelStride,
                vPlane.buffer, vPlane.rowStride, vPlane.pixelStride,
                image.width, image.height,
                previewHistR, previewHistG, previewHistB,
                computeLoupe,
                st.focusLoupe.normX, st.focusLoupe.normY,
                if (computeLoupe) loupeBitmapBuf else null
            )

            if (st.showHistogram) {
                histCopy = HistogramData(previewHistR.clone(), previewHistG.clone(), previewHistB.clone())
            }
            if (computeLoupe) {
                val bmpCopy = loupeBitmapBuf.copy(Bitmap.Config.ARGB_8888, false)
                val sharpPoint = if (metrics.starFound && metrics.fwhm > 0.1f) {
                    (10f / metrics.fwhm).coerceIn(0.1f, 15f)
                } else {
                    metrics.tenengrad * 100f
                }
                val nextHist = (st.focusLoupe.history + sharpPoint).takeLast(32)
                loupeCopy = st.focusLoupe.copy(
                    fwhm = metrics.fwhm,
                    hfd = metrics.hfd,
                    snr = metrics.snr,
                    tenengrad = metrics.tenengrad,
                    starFound = metrics.starFound,
                    bitmap = bmpCopy,
                    history = nextHist
                )
            }
        }

        val peakingCopy = if (st.focusPeakingEnabled) {
            val count = nativeEngine.computeFocusPeakingPoints(
                yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
                image.width, image.height,
                0.14f,
                peakingBuf
            )
            FocusPeakingState(
                pointsXY = if (count > 0) peakingBuf.copyOf(count * 2) else FloatArray(0),
                pointCount = count
            )
        } else {
            FocusPeakingState()
        }

        _uiState.update { it.copy(histogram = histCopy, focusLoupe = loupeCopy, focusPeaking = peakingCopy) }
    }

    // =========================================================================
    // Trigger / Stacking / Lucky Imaging / Star Trails / Timelapse / Calibration
    // =========================================================================

    fun onShutterPressed() {
        val st = _uiState.value
        if (st.captureProgress.active) {
            finishCaptureEarly()
            return
        }
        if (st.activeLens == null) {
            showBanner("Aucun capteur actif")
            return
        }
        startCapturePipeline()
    }

    fun finishCaptureEarly() {
        if (_uiState.value.captureProgress.active) {
            requestFinishEarly.set(true)
            _uiState.update {
                it.copy(captureProgress = it.captureProgress.copy(statusText = "Finalisation en cours...", isFinalizing = true))
            }
        }
    }

    fun abortCapture() {
        requestFinishEarly.set(false)
        captureJob?.cancel()
        captureJob = null
        _uiState.update { it.copy(captureProgress = CaptureProgressState()) }
        showBanner("Acquisition annulée")
    }

    private fun startCapturePipeline() {
        val st = _uiState.value
        val lens = st.activeLens ?: return
        val mode = st.captureMode
        val manual = st.manualSettings
        val opts = st.modeOptions
        requestFinishEarly.set(false)

        captureJob?.cancel()
        captureJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                // Vibration-free countdown timer
                val delaySec = st.timerOption.seconds
                if (delaySec > 0) {
                    for (rem in delaySec downTo 1) {
                        _uiState.update {
                            it.copy(
                                captureProgress = CaptureProgressState(
                                    active = true,
                                    mode = mode,
                                    countdownSec = rem,
                                    statusText = "Retardateur ${rem}s..."
                                )
                            )
                        }
                        delay(1000L)
                    }
                }

                when (mode) {
                    CaptureMode.ASTRO_PRO -> runSingleAstroPro(lens, manual, st.outputFormat)
                    CaptureMode.DEEP_SKY -> runDeepSkyStack(lens, manual, opts)
                    CaptureMode.LUNAR_PLANETARY -> runLuckyPlanetary(lens, manual, opts)
                    CaptureMode.STAR_TRAILS -> runStarTrails(lens, manual, opts)
                    CaptureMode.TIMELAPSE -> runTimelapse(lens, manual, opts)
                    CaptureMode.CALIBRATION -> runCalibrationCapture(lens, manual, opts)
                }
            } catch (_: CancellationException) {
                // Handled by abortCapture
            } catch (t: Throwable) {
                showBanner("Erreur capture: ${t.message}")
            } finally {
                _uiState.update { it.copy(captureProgress = CaptureProgressState()) }
            }
        }
    }

    private fun estimateRemainingSec(
        sessionStartMs: Long,
        captured: Int,
        target: Int,
        nominalCycleSec: Double
    ): Double {
        if (target <= 0) return 0.0
        val remaining = (target - captured).coerceAtLeast(0)
        if (remaining == 0) return 0.0
        val avgCycle = if (captured >= 1) {
            val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000.0
            max(0.15, elapsed / captured)
        } else {
            max(0.25, nominalCycleSec)
        }
        return remaining * avgCycle
    }

    private suspend fun runSingleAstroPro(lens: LensInfo, manual: ManualSettings, format: OutputFormatOption) {
        val showLiveExposureRing = !manual.shutterAuto && manual.shutterSeconds >= 1.0
        _uiState.update {
            it.copy(
                captureProgress = CaptureProgressState(
                    active = true,
                    mode = CaptureMode.ASTRO_PRO,
                    framesCaptured = 0,
                    framesTarget = 1,
                    exposureProgress = if (showLiveExposureRing) 0.01f else 0f,
                    remainingExposureSec = if (showLiveExposureRing) manual.shutterSeconds else 0.0,
                    statusText = "Pose ${NpfCalculator.formatShutter(manual.shutterSeconds)} en cours..."
                )
            )
        }
        syncActiveCalibrationMasters(useDark = true, useFlat = true)
        nativeEngine.startStackSession(AstroNativeEngine.STACK_MODE_SINGLE_PRO)

        val frame = if (showLiveExposureRing) {
            coroutineScope {
                val startMs = System.currentTimeMillis()
                val totalSec = manual.shutterSeconds
                val ticker = launch {
                    while (isActive) {
                        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                        val prog = (elapsedSec / totalSec).toFloat().coerceIn(0.01f, 0.99f)
                        val rem = (totalSec - elapsedSec).coerceAtLeast(0.0)
                        _uiState.update { st ->
                            st.copy(
                                captureProgress = st.captureProgress.copy(
                                    elapsedExposureSec = elapsedSec.coerceAtMost(totalSec),
                                    exposureProgress = prog,
                                    remainingExposureSec = rem,
                                    statusText = String.format(
                                        Locale.US,
                                        "Pose %.1fs / %s",
                                        elapsedSec.coerceAtMost(totalSec),
                                        NpfCalculator.formatShutter(totalSec)
                                    )
                                )
                            )
                        }
                        if (elapsedSec >= totalSec) break
                        delay(100L)
                    }
                }
                try {
                    cameraController.captureSingleFrame(manual, CaptureMode.ASTRO_PRO, preferRaw = true)
                } finally {
                    ticker.cancel()
                }
            }
        } else {
            cameraController.captureSingleFrame(manual, CaptureMode.ASTRO_PRO, preferRaw = true)
        }

        if (frame == null) {
            showBanner("Échec de la capture")
            return
        }

        _uiState.update { st ->
            st.copy(
                captureProgress = st.captureProgress.copy(
                    exposureProgress = 1f,
                    remainingExposureSec = 0.0,
                    isFinalizing = true,
                    statusText = "Développement RAW 16-bit..."
                )
            )
        }

        frame.use { cap ->
            // Export authentic 16-bit DNG if RAW_SENSOR was acquired
            var dngMsg = ""
            if (cap.rawImage != null) {
                val tmpDng = store.newExportTempFile("ASTRO14U_${store.nowFileStamp()}.dng")
                if (cameraController.writeDngFile(cap, tmpDng)) {
                    store.copyFileToPicturesMediaStore(tmpDng, tmpDng.name, "image/x-adobe-dng")
                    tmpDng.delete()
                    dngMsg = "DNG 16b + "
                }
            }

            val previewBmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            feedFrameToNative(cap, manual, previewBmp)

            val astroFile = store.newAstro32File("ASTRO_PRO")
            val thumbFile = store.newThumbFile("ASTRO_PRO")
            val dateObs = store.nowIsoUtc()
            val ok = nativeEngine.finishStackSession(
                cameraModel = "Xiaomi 14 Ultra",
                lensName = "${lens.displayName} ${lens.sensorHint}",
                dateObs = dateObs,
                modeName = CaptureMode.ASTRO_PRO.label,
                focalMm = lens.focalLengthMm,
                apertureF = manual.aperture,
                exposureSec = manual.shutterSeconds.toFloat(),
                iso = manual.iso.toFloat(),
                saveAstro32Path = astroFile.absolutePath,
                outPreviewBitmap = previewBmp
            )

            if (ok) {
                store.saveThumbnail(previewBmp, thumbFile)
                if (format == OutputFormatOption.RAW_PLUS_JPG) {
                    store.saveJpegWithExifToGallery(
                        previewBmp,
                        "ASTRO14U_${store.nowFileStamp()}.jpg",
                        lens.focalLengthMm, manual.aperture, manual.shutterSeconds.toFloat(), manual.iso
                    )
                }
                registerNewSession(
                    astroFile = astroFile,
                    thumbFile = thumbFile,
                    thumbBmp = previewBmp,
                    title = "Astro Pro ${lens.displayName}",
                    modeName = CaptureMode.ASTRO_PRO.label,
                    lensName = lens.displayName,
                    exposureSec = manual.shutterSeconds.toFloat(),
                    iso = manual.iso,
                    stackCount = 1,
                    dateObs = dateObs
                )
                showBanner("${dngMsg}Session 16b enregistrée")
            }
        }
    }

    private suspend fun runDeepSkyStack(lens: LensInfo, manual: ManualSettings, opts: ModeOptions) {
        syncActiveCalibrationMasters(useDark = opts.useAutoDark, useFlat = opts.useAutoFlat)
        nativeEngine.startStackSession(
            AstroNativeEngine.STACK_MODE_DEEP_SKY,
            opts.deepSkySigmaKappa,
            3.0f
        )

        val liveBmp = Bitmap.createBitmap(720, 540, Bitmap.Config.ARGB_8888)
        val sessionStartMs = System.currentTimeMillis()
        val nominalCycleSec = manual.shutterSeconds + 0.6
        var captured = 0
        var accepted = 0
        var rejected = 0
        var totalExpSec = 0.0

        for (i in 1..opts.deepSkyFrames) {
            if (!coroutineContext.isActive || requestFinishEarly.get()) break
            val etaSec = estimateRemainingSec(sessionStartMs, captured, opts.deepSkyFrames, nominalCycleSec)
            _uiState.update {
                it.copy(
                    captureProgress = it.captureProgress.copy(
                        active = true,
                        mode = CaptureMode.DEEP_SKY,
                        countdownSec = 0,
                        framesCaptured = captured,
                        framesTarget = opts.deepSkyFrames,
                        framesAccepted = accepted,
                        framesRejected = rejected,
                        elapsedExposureSec = totalExpSec,
                        estimatedRemainingTotalSec = etaSec,
                        statusText = "Pose $i/${opts.deepSkyFrames} (${NpfCalculator.formatShutter(manual.shutterSeconds)})"
                    )
                )
            }

            val cap = cameraController.captureSingleFrame(manual, CaptureMode.DEEP_SKY, preferRaw = true) ?: continue
            val rep = cap.use { feedFrameToNative(it, manual, liveBmp) }
            captured++
            accepted = rep.totalAccepted
            rejected = rep.totalRejected
            if (rep.accepted) totalExpSec += manual.shutterSeconds
            val nextEtaSec = estimateRemainingSec(sessionStartMs, captured, opts.deepSkyFrames, nominalCycleSec)

            _uiState.update {
                it.copy(
                    captureProgress = it.captureProgress.copy(
                        framesCaptured = captured,
                        framesAccepted = accepted,
                        framesRejected = rejected,
                        starsDetected = rep.starsDetected,
                        residualPx = rep.residualPx,
                        elapsedExposureSec = totalExpSec,
                        estimatedRemainingTotalSec = nextEtaSec,
                        liveStackPreview = liveBmp.copy(Bitmap.Config.ARGB_8888, false),
                        statusText = if (rep.accepted) "Aligné (${rep.starsMatched}★)" else "Pose rejetée (flou/bougé)"
                    )
                )
            }
        }

        if (accepted == 0) {
            showBanner("Aucune pose valide empilée")
            return
        }

        val astroFile = store.newAstro32File("DEEPSKY_${accepted}x")
        val thumbFile = store.newThumbFile("DEEPSKY_${accepted}x")
        val dateObs = store.nowIsoUtc()
        val finalThumb = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val ok = nativeEngine.finishStackSession(
            cameraModel = "Xiaomi 14 Ultra",
            lensName = "${lens.displayName} ${lens.sensorHint}",
            dateObs = dateObs,
            modeName = "CIEL PROFOND",
            focalMm = lens.focalLengthMm,
            apertureF = manual.aperture,
            exposureSec = manual.shutterSeconds.toFloat(),
            iso = manual.iso.toFloat(),
            saveAstro32Path = astroFile.absolutePath,
            outPreviewBitmap = finalThumb
        )
        if (ok) {
            store.saveThumbnail(finalThumb, thumbFile)
            store.saveJpegWithExifToGallery(
                finalThumb, "DEEPSKY_${accepted}x_${store.nowFileStamp()}.jpg",
                lens.focalLengthMm, manual.aperture, totalExpSec.toFloat(), manual.iso
            )
            registerNewSession(
                astroFile, thumbFile, finalThumb,
                "Ciel Profond ${accepted}×${NpfCalculator.formatShutter(manual.shutterSeconds)}",
                "CIEL PROFOND", lens.displayName, manual.shutterSeconds.toFloat(), manual.iso, accepted, dateObs
            )
            showBanner("Empilement terminé: $accepted poses (${NpfCalculator.formatIntegrationTime(totalExpSec)})")
        }
    }

    private suspend fun runLuckyPlanetary(lens: LensInfo, manual: ManualSettings, opts: ModeOptions) {
        nativeEngine.clearCalibrationMasters(clearDark = true, clearFlat = true, clearBias = true)
        nativeEngine.startStackSession(
            AstroNativeEngine.STACK_MODE_LUCKY_PLANETARY,
            opts.planetaryFrames.toFloat(),
            opts.planetaryKeepPercent,
            if (opts.planetaryAutoWavelets) 1.15f else 0.0f
        )

        val liveBmp = Bitmap.createBitmap(720, 540, Bitmap.Config.ARGB_8888)
        val sessionStartMs = System.currentTimeMillis()
        val nominalCycleSec = manual.shutterSeconds + 0.35
        var captured = 0
        for (i in 1..opts.planetaryFrames) {
            if (!coroutineContext.isActive || requestFinishEarly.get()) break
            val etaSec = estimateRemainingSec(sessionStartMs, captured, opts.planetaryFrames, nominalCycleSec)
            _uiState.update {
                it.copy(
                    captureProgress = it.captureProgress.copy(
                        active = true,
                        mode = CaptureMode.LUNAR_PLANETARY,
                        countdownSec = 0,
                        framesCaptured = captured,
                        framesTarget = opts.planetaryFrames,
                        estimatedRemainingTotalSec = etaSec,
                        statusText = "Rafale Lucky Imaging $i/${opts.planetaryFrames}..."
                    )
                )
            }
            val cap = cameraController.captureSingleFrame(manual, CaptureMode.LUNAR_PLANETARY, preferRaw = true) ?: continue
            val rep = cap.use { feedFrameToNative(it, manual, liveBmp) }
            captured++
            val nextEtaSec = estimateRemainingSec(sessionStartMs, captured, opts.planetaryFrames, nominalCycleSec)
            _uiState.update {
                it.copy(
                    captureProgress = it.captureProgress.copy(
                        framesCaptured = captured,
                        framesAccepted = rep.totalAccepted,
                        estimatedRemainingTotalSec = nextEtaSec,
                        liveStackPreview = liveBmp.copy(Bitmap.Config.ARGB_8888, false),
                        statusText = String.format(Locale.US, "Top %d%% retenu · Netteté %.1f", (opts.planetaryKeepPercent * 100).toInt(), rep.sharpnessScore * 1000f)
                    )
                )
            }
        }

        if (captured == 0) return

        _uiState.update {
            it.copy(
                captureProgress = it.captureProgress.copy(
                    estimatedRemainingTotalSec = 0.0,
                    statusText = "Alignement planétaire + Ondelettes À Trous...",
                    isFinalizing = true
                )
            )
        }
        val astroFile = store.newAstro32File("PLANETARY_${captured}x")
        val thumbFile = store.newThumbFile("PLANETARY_${captured}x")
        val dateObs = store.nowIsoUtc()
        val finalThumb = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val ok = nativeEngine.finishStackSession(
            cameraModel = "Xiaomi 14 Ultra",
            lensName = "${lens.displayName} ${lens.sensorHint}",
            dateObs = dateObs,
            modeName = "LUNE / PLANÈTES",
            focalMm = lens.focalLengthMm,
            apertureF = manual.aperture,
            exposureSec = manual.shutterSeconds.toFloat(),
            iso = manual.iso.toFloat(),
            saveAstro32Path = astroFile.absolutePath,
            outPreviewBitmap = finalThumb
        )
        if (ok) {
            store.saveThumbnail(finalThumb, thumbFile)
            store.saveJpegWithExifToGallery(
                finalThumb, "PLANETARY_${store.nowFileStamp()}.jpg",
                lens.focalLengthMm, manual.aperture, manual.shutterSeconds.toFloat(), manual.iso
            )
            registerNewSession(
                astroFile, thumbFile, finalThumb,
                "Lucky Imaging Top ${(opts.planetaryKeepPercent * 100).toInt()}%",
                "LUNE / PLANÈTES", lens.displayName, manual.shutterSeconds.toFloat(), manual.iso, captured, dateObs
            )
            showBanner("Lucky Imaging + Ondelettes enregistré")
        }
    }

    private suspend fun runStarTrails(lens: LensInfo, manual: ManualSettings, opts: ModeOptions) {
        val isComet = opts.starTrailStyle == StarTrailStyle.COMET
        nativeEngine.startStackSession(
            if (isComet) AstroNativeEngine.STACK_MODE_STAR_TRAILS_COMET else AstroNativeEngine.STACK_MODE_STAR_TRAILS_STANDARD,
            opts.cometTailLength.decay
        )
        val liveBmp = Bitmap.createBitmap(720, 540, Bitmap.Config.ARGB_8888)
        val sessionStartMs = System.currentTimeMillis()
        val nominalCycleSec = manual.shutterSeconds + 0.5
        var captured = 0
        var totalExpSec = 0.0

        for (i in 1..opts.starTrailMaxFrames) {
            if (!coroutineContext.isActive || requestFinishEarly.get()) break
            val etaSec = estimateRemainingSec(sessionStartMs, captured, opts.starTrailMaxFrames, nominalCycleSec)
            _uiState.update {
                it.copy(
                    captureProgress = it.captureProgress.copy(
                        active = true,
                        mode = CaptureMode.STAR_TRAILS,
                        countdownSec = 0,
                        framesCaptured = captured,
                        framesTarget = opts.starTrailMaxFrames,
                        framesAccepted = captured,
                        elapsedExposureSec = totalExpSec,
                        estimatedRemainingTotalSec = etaSec,
                        statusText = if (isComet) "Filés Comète · Pose $i" else "Filés d'étoiles · Pose $i"
                    )
                )
            }
            val cap = cameraController.captureSingleFrame(manual, CaptureMode.STAR_TRAILS, preferRaw = true) ?: continue
            cap.use { feedFrameToNative(it, manual, liveBmp) }
            captured++
            totalExpSec += manual.shutterSeconds
            val nextEtaSec = estimateRemainingSec(sessionStartMs, captured, opts.starTrailMaxFrames, nominalCycleSec)
            _uiState.update {
                it.copy(
                    captureProgress = it.captureProgress.copy(
                        framesCaptured = captured,
                        framesAccepted = captured,
                        elapsedExposureSec = totalExpSec,
                        estimatedRemainingTotalSec = nextEtaSec,
                        liveStackPreview = liveBmp.copy(Bitmap.Config.ARGB_8888, false)
                    )
                )
            }
        }

        if (captured == 0) return
        val astroFile = store.newAstro32File("STARTRAILS_${captured}x")
        val thumbFile = store.newThumbFile("STARTRAILS_${captured}x")
        val dateObs = store.nowIsoUtc()
        val finalThumb = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        if (nativeEngine.finishStackSession(
                "Xiaomi 14 Ultra", "${lens.displayName} ${lens.sensorHint}",
                dateObs, "STAR TRAILS", lens.focalLengthMm, manual.aperture,
                manual.shutterSeconds.toFloat(), manual.iso.toFloat(),
                astroFile.absolutePath, finalThumb
            )
        ) {
            store.saveThumbnail(finalThumb, thumbFile)
            store.saveJpegWithExifToGallery(
                finalThumb, "STARTRAILS_${store.nowFileStamp()}.jpg",
                lens.focalLengthMm, manual.aperture, totalExpSec.toFloat(), manual.iso
            )
            registerNewSession(
                astroFile, thumbFile, finalThumb,
                "Star Trails (${opts.starTrailStyle.label}) ${captured}×",
                "STAR TRAILS", lens.displayName, manual.shutterSeconds.toFloat(), manual.iso, captured, dateObs
            )
            showBanner("Star Trails sauvegardé ($captured poses)")
        }
    }

    private suspend fun runTimelapse(lens: LensInfo, manual: ManualSettings, opts: ModeOptions) {
        val stamp = store.nowFileStamp()
        val encoder = TimelapseEncoder(appContext, width = 1440, height = 1080, fps = opts.timelapseFps)
        if (!encoder.start("ASTRO_TL_$stamp")) {
            showBanner("Encodeur MP4 H.264 indisponible")
            return
        }

        val frameBmp = Bitmap.createBitmap(1440, 1080, Bitmap.Config.ARGB_8888)
        val sessionStartMs = System.currentTimeMillis()
        val nominalCycleSec = max(opts.timelapseIntervalSec.toDouble(), manual.shutterSeconds + 0.4)
        var captured = 0
        val target = opts.timelapseCount // 0 = unlimited

        try {
            while (coroutineContext.isActive && !requestFinishEarly.get()) {
                if (target > 0 && captured >= target) break
                val startMs = System.currentTimeMillis()
                val etaSec = estimateRemainingSec(sessionStartMs, captured, target, nominalCycleSec)
                _uiState.update {
                    it.copy(
                        captureProgress = CaptureProgressState(
                            active = true,
                            mode = CaptureMode.TIMELAPSE,
                            framesCaptured = captured,
                            framesTarget = target,
                            framesAccepted = captured,
                            elapsedExposureSec = captured * manual.shutterSeconds,
                            estimatedRemainingTotalSec = etaSec,
                            statusText = "Timelapse ${opts.timelapseFps}fps · Vue ${captured + 1}"
                        )
                    )
                }

                nativeEngine.startStackSession(AstroNativeEngine.STACK_MODE_SINGLE_PRO)
                val cap = cameraController.captureSingleFrame(
                    manual, CaptureMode.TIMELAPSE, preferRaw = opts.timelapseArchiveRaw
                )
                if (cap != null) {
                    cap.use { c ->
                        if (opts.timelapseArchiveRaw && c.rawImage != null) {
                            val dng = store.newExportTempFile("TL_${stamp}_${captured}.dng")
                            if (cameraController.writeDngFile(c, dng)) {
                                store.copyFileToPicturesMediaStore(dng, dng.name, "image/x-adobe-dng")
                                dng.delete()
                            }
                        }
                        feedFrameToNative(c, manual, frameBmp)
                        encoder.appendFrame(frameBmp)
                        captured++
                    }
                }

                val elapsed = System.currentTimeMillis() - startMs
                val waitMs = (opts.timelapseIntervalSec * 1000L - elapsed).coerceAtLeast(150L)
                var waited = 0L
                while (waited < waitMs && !requestFinishEarly.get() && coroutineContext.isActive) {
                    delay(150L)
                    waited += 150L
                }
            }
        } finally {
            if (captured > 0) {
                val savedPath = encoder.finishAndSaveToGallery("ASTRO_TIMELAPSE_${stamp}.mp4")
                showBanner(if (savedPath != null) "MP4 enregistré: $savedPath ($captured vues)" else "Erreur export MP4")
            } else {
                encoder.abort()
            }
        }
    }

    private suspend fun runCalibrationCapture(lens: LensInfo, manual: ManualSettings, opts: ModeOptions) {
        val kind = opts.calibrationKind
        val calManual = when (kind) {
            CalibrationKind.BIAS -> manual.copy(shutterAuto = false, shutterSeconds = lens.minExposureSec.coerceAtLeast(1.0 / 4000.0))
            CalibrationKind.FLAT -> manual.copy(shutterAuto = false, shutterSeconds = 1.0 / 60.0)
            CalibrationKind.DARK -> manual
        }

        val rawDim = lens.rawSize?.takeIf { lens.supportsRaw }
        val useRaw = rawDim != null
        val calW = rawDim?.width ?: (lens.yuvCaptureSize.width / 2)
        val calH = rawDim?.height ?: (lens.yuvCaptureSize.height / 2)
        val channels = if (useRaw) 1 else 3

        nativeEngine.startCalibration(
            kind.nativeType, calW, calH, channels, lens.cfaPattern,
            calManual.iso.toFloat(), calManual.shutterSeconds.toFloat(), lens.focalLengthMm
        )

        val sessionStartMs = System.currentTimeMillis()
        val nominalCycleSec = calManual.shutterSeconds + 0.5
        var captured = 0
        for (i in 1..opts.calibrationFrames) {
            if (!coroutineContext.isActive || requestFinishEarly.get()) break
            val etaSec = estimateRemainingSec(sessionStartMs, captured, opts.calibrationFrames, nominalCycleSec)
            _uiState.update {
                it.copy(
                    captureProgress = CaptureProgressState(
                        active = true,
                        mode = CaptureMode.CALIBRATION,
                        framesCaptured = captured,
                        framesTarget = opts.calibrationFrames,
                        framesAccepted = captured,
                        elapsedExposureSec = captured * calManual.shutterSeconds,
                        estimatedRemainingTotalSec = etaSec,
                        statusText = "Master ${kind.label} $i/${opts.calibrationFrames}..."
                    )
                )
            }
            val cap = cameraController.captureSingleFrame(calManual, CaptureMode.CALIBRATION, preferRaw = useRaw) ?: continue
            cap.use { c ->
                if (useRaw && c.rawImage != null) {
                    val plane = c.rawImage.planes[0]
                    captured = nativeEngine.addCalibrationRawFrame(
                        plane.buffer, c.rawImage.width, c.rawImage.height,
                        plane.rowStride, lens.cfaPattern, lens.blackLevel, lens.whiteLevel
                    )
                } else if (c.yuvImage != null && c.yuvImage.planes.size >= 3) {
                    val y = c.yuvImage.planes[0]
                    val u = c.yuvImage.planes[1]
                    val v = c.yuvImage.planes[2]
                    captured = nativeEngine.addCalibrationYuvFrame(
                        y.buffer, y.rowStride, y.pixelStride,
                        u.buffer, u.rowStride, u.pixelStride,
                        v.buffer, v.rowStride, v.pixelStride,
                        c.yuvImage.width, c.yuvImage.height, 2
                    )
                }
            }
        }

        if (captured < 2) {
            showBanner("Calibration annulée (< 2 poses)")
            return
        }

        val outFile = store.newCalibrationFile(kind, lens.displayName, calManual.iso)
        if (nativeEngine.finishCalibration(outFile.absolutePath)) {
            val info = CalibrationMasterInfo(
                id = UUID.randomUUID().toString(),
                kind = kind,
                filePath = outFile.absolutePath,
                lensLabel = lens.displayName,
                focalMm = lens.focalLengthMm,
                iso = calManual.iso,
                exposureSec = calManual.shutterSeconds.toFloat(),
                frameCount = captured,
                isRawBayer = useRaw,
                createdAt = store.nowIsoUtc()
            )
            val updated = store.addCalibrationMaster(info)
            _uiState.update { st ->
                st.copy(
                    calibrationMasters = updated,
                    activeDarkId = if (kind == CalibrationKind.DARK) info.id else st.activeDarkId,
                    activeFlatId = if (kind == CalibrationKind.FLAT) info.id else st.activeFlatId,
                    activeBiasId = if (kind == CalibrationKind.BIAS) info.id else st.activeBiasId
                )
            }
            showBanner("Master ${kind.label} ($captured poses) créé & activé")
        } else {
            showBanner("Échec création Master ${kind.label}")
        }
    }

    private fun feedFrameToNative(
        cap: AstroCameraController.CapturedFrame,
        manual: ManualSettings,
        previewBitmap: Bitmap?
    ) = if (cap.rawImage != null) {
        val raw = cap.rawImage
        val plane = raw.planes[0]
        val gains = cameraController.kelvinToRggbGains(manual.wbKelvin)
        val wbArr = floatArrayOf(gains.red, 1.0f, gains.blue)
        // Auto-bin 2x2 if raw > 2500px wide for lightning-fast zero-OOM 12MP stacking
        val bin2x2 = raw.width > 2500
        nativeEngine.addRawFrame(
            plane.buffer, raw.width, raw.height, plane.rowStride,
            cap.lens.cfaPattern, cap.lens.blackLevel, cap.lens.whiteLevel,
            wbArr, bin2x2, previewBitmap
        )
    } else if (cap.yuvImage != null && cap.yuvImage.planes.size >= 3) {
        val yuv = cap.yuvImage
        val y = yuv.planes[0]
        val u = yuv.planes[1]
        val v = yuv.planes[2]
        val ds = if (yuv.width > 2400) 2 else 1
        nativeEngine.addYuvFrame(
            y.buffer, y.rowStride, y.pixelStride,
            u.buffer, u.rowStride, u.pixelStride,
            v.buffer, v.rowStride, v.pixelStride,
            yuv.width, yuv.height, ds, previewBitmap
        )
    } else {
        com.astropro.xiaomi14u.nativeengine.NativeStackReport()
    }

    private fun syncActiveCalibrationMasters(useDark: Boolean, useFlat: Boolean) {
        val st = _uiState.value
        nativeEngine.clearCalibrationMasters(clearDark = true, clearFlat = true, clearBias = true)
        if (useDark) {
            st.calibrationMasters.firstOrNull { it.id == st.activeDarkId }?.let {
                nativeEngine.loadCalibrationMaster(it.filePath)
            }
        }
        if (useFlat) {
            st.calibrationMasters.firstOrNull { it.id == st.activeFlatId }?.let {
                nativeEngine.loadCalibrationMaster(it.filePath)
            }
        }
        st.calibrationMasters.firstOrNull { it.id == st.activeBiasId }?.let {
            nativeEngine.loadCalibrationMaster(it.filePath)
        }
    }

    private fun registerNewSession(
        astroFile: File,
        thumbFile: File,
        thumbBmp: Bitmap,
        title: String,
        modeName: String,
        lensName: String,
        exposureSec: Float,
        iso: Int,
        stackCount: Int,
        dateObs: String
    ) {
        val item = AstroSessionItem(
            id = UUID.randomUUID().toString(),
            astro32Path = astroFile.absolutePath,
            thumbPath = thumbFile.absolutePath,
            title = title,
            modeName = modeName,
            lensName = lensName,
            exposureSec = exposureSec,
            iso = iso,
            stackCount = stackCount,
            dateObs = dateObs
        )
        val updated = store.addSession(item)
        _uiState.update {
            it.copy(
                sessions = updated,
                lastThumbBitmap = thumbBmp.copy(Bitmap.Config.ARGB_8888, false),
                activeLabSession = item
            )
        }
    }

    // =========================================================================
    // 16-Bit Astro Labo Processing & TIFF / FITS / JPEG Export
    // =========================================================================

    private fun ensureLabSessionLoaded() {
        val st = _uiState.value
        val target = st.activeLabSession ?: st.sessions.firstOrNull()
        if (target != null) {
            openSessionInLab(target)
        }
    }

    fun openSessionInLab(item: AstroSessionItem) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(activeLabSession = item, labBusy = true, labShowOriginal = false) }
            val dims = nativeEngine.loadAstro32(item.astro32Path)
            if (dims != null) {
                val useAuto = item.modeName.contains("PROFOND") || item.modeName.contains("PRO")
                val defaultParams = LabParameters(
                    autoStf = useAuto,
                    bgNeutralize = useAuto
                )
                _uiState.update {
                    it.copy(
                        labParams = defaultParams,
                        activeLabPreset = if (useAuto) LabPreset.AUTO_STF else null,
                        labBusy = false
                    )
                }
                renderLabPreviewNow(defaultParams, showOriginal = false)
            } else {
                _uiState.update { it.copy(labBusy = false) }
                showBanner("Impossible d'ouvrir la session 16-bit")
            }
        }
    }

    fun applyLabPreset(preset: LabPreset) {
        _uiState.update {
            it.copy(
                labParams = preset.params,
                activeLabPreset = if (preset == LabPreset.RESET) null else preset,
                labShowOriginal = false
            )
        }
        renderLabPreviewNow(preset.params, showOriginal = false)
        showBanner("Préréglage : ${preset.label}")
    }

    fun updateLabParams(transform: (LabParameters) -> LabParameters) {
        val next = transform(_uiState.value.labParams)
        _uiState.update { it.copy(labParams = next, activeLabPreset = null, labShowOriginal = false) }
        renderLabPreviewNow(next, showOriginal = false)
    }

    fun setLabShowOriginal(showOriginal: Boolean) {
        _uiState.update { it.copy(labShowOriginal = showOriginal) }
        renderLabPreviewNow(_uiState.value.labParams, showOriginal)
    }

    fun deleteSession(item: AstroSessionItem) {
        val updated = store.deleteSession(item.id)
        val nextActive = if (_uiState.value.activeLabSession?.id == item.id) updated.firstOrNull() else _uiState.value.activeLabSession
        _uiState.update { it.copy(sessions = updated, activeLabSession = nextActive) }
        if (nextActive != null) openSessionInLab(nextActive) else _uiState.update { it.copy(labPreviewBitmap = null) }
    }

    private fun renderLabPreviewNow(params: LabParameters, showOriginal: Boolean) {
        labRenderJob?.cancel()
        labRenderJob = viewModelScope.launch(Dispatchers.Default) {
            val (w, h) = nativeEngine.getActiveImageSize()
            if (w <= 0 || h <= 0) return@launch
            val maxEdge = 960
            val scale = maxEdge.toFloat() / maxOf(w, h).toFloat()
            val pw = (w * scale).toInt().coerceIn(64, maxEdge)
            val ph = (h * scale).toInt().coerceIn(64, maxEdge)
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val hR = IntArray(64)
            val hG = IntArray(64)
            val hB = IntArray(64)
            val ok = nativeEngine.renderLabPreview(params, showOriginal, bmp, hR, hG, hB)
            if (ok) {
                _uiState.update {
                    it.copy(labPreviewBitmap = bmp, labHistogram = HistogramData(hR, hG, hB))
                }
            }
        }
    }

    fun exportLabTiff16(shareAfter: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            val st = _uiState.value
            val stamp = store.nowFileStamp()
            val name = "ASTRO14U_16B_${stamp}.tif"
            val tmp = store.newExportTempFile(name)
            _uiState.update { it.copy(labBusy = true) }
            val ok = nativeEngine.exportTiff16(tmp.absolutePath, st.labParams, applyLab = true)
            _uiState.update { it.copy(labBusy = false) }
            if (ok) {
                val saved = store.copyFileToPicturesMediaStore(tmp, name, "image/tiff")
                showBanner("TIFF 16-bit exporté: ${saved ?: tmp.name}")
                if (shareAfter) store.shareFile(tmp, "image/tiff", "Partager TIFF 16-bit")
            } else {
                showBanner("Échec export TIFF 16-bit")
            }
        }
    }

    fun exportLabFits32(shareAfter: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            val st = _uiState.value
            val stamp = store.nowFileStamp()
            val name = "ASTRO14U_32F_${stamp}.fits"
            val tmp = store.newExportTempFile(name)
            _uiState.update { it.copy(labBusy = true) }
            // Export full linear 32-bit float astronomical FITS (Siril / PixInsight native!)
            val ok = nativeEngine.exportFits(tmp.absolutePath, st.labParams, applyLab = false, asFloat32 = true)
            _uiState.update { it.copy(labBusy = false) }
            if (ok) {
                val saved = store.copyFileToDownloadsMediaStore(tmp, name, "application/fits")
                showBanner("FITS 32-bit exporté: ${saved ?: tmp.name}")
                if (shareAfter) store.shareFile(tmp, "application/octet-stream", "Partager FITS 32-bit")
            } else {
                showBanner("Échec export FITS 32-bit")
            }
        }
    }

    fun exportLabJpegHd(shareAfter: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            val st = _uiState.value
            val (w, h) = nativeEngine.getActiveImageSize()
            if (w <= 0 || h <= 0) return@launch
            _uiState.update { it.copy(labBusy = true) }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = nativeEngine.renderFullExportBitmap(st.labParams, bmp)
            _uiState.update { it.copy(labBusy = false) }
            if (ok) {
                val sess = st.activeLabSession
                val name = "ASTRO14U_HD_${store.nowFileStamp()}.jpg"
                val saved = store.saveJpegWithExifToGallery(
                    bmp, name,
                    st.activeLens?.focalLengthMm ?: 23f,
                    st.manualSettings.aperture,
                    sess?.exposureSec ?: 8f,
                    sess?.iso ?: 1600
                )
                showBanner("JPEG HD exporté: ${saved ?: name}")
                if (shareAfter) {
                    val tmp = store.newExportTempFile(name)
                    if (tmp.exists()) store.shareFile(tmp, "image/jpeg", "Partager JPEG HD")
                }
            } else {
                showBanner("Échec export JPEG HD")
            }
        }
    }

    // =========================================================================
    // Calibration Master Management & HAL JSON Export
    // =========================================================================

    fun selectCalibrationMaster(info: CalibrationMasterInfo) {
        viewModelScope.launch(Dispatchers.Default) {
            nativeEngine.loadCalibrationMaster(info.filePath)
            _uiState.update { st ->
                st.copy(
                    activeDarkId = if (info.kind == CalibrationKind.DARK) info.id else st.activeDarkId,
                    activeFlatId = if (info.kind == CalibrationKind.FLAT) info.id else st.activeFlatId,
                    activeBiasId = if (info.kind == CalibrationKind.BIAS) info.id else st.activeBiasId
                )
            }
            showBanner("Master ${info.kind.label} activé (${info.lensLabel})")
        }
    }

    fun deleteCalibrationMaster(info: CalibrationMasterInfo) {
        val remaining = store.deleteCalibrationMaster(info.id)
        _uiState.update { st ->
            st.copy(
                calibrationMasters = remaining,
                activeDarkId = if (st.activeDarkId == info.id) null else st.activeDarkId,
                activeFlatId = if (st.activeFlatId == info.id) null else st.activeFlatId,
                activeBiasId = if (st.activeBiasId == info.id) null else st.activeBiasId
            )
        }
    }

    fun launchCalibrationMode(kind: CalibrationKind) {
        updateModeOptions { it.copy(calibrationKind = kind) }
        selectCaptureMode(CaptureMode.CALIBRATION)
        navigateTo(AppScreen.CAMERA)
    }

    fun exportHalJsonReport() {
        val probe = probeResult ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val json = XiaomiLensProbe.exportHalReportJson(probe)
            val name = "XIAOMI14U_HAL_${store.nowFileStamp()}.json"
            val tmp = store.newExportTempFile(name)
            tmp.writeText(json)
            val saved = store.copyFileToDownloadsMediaStore(tmp, name, "application/json")
            showBanner("JSON HAL exporté: ${saved ?: name}")
            withContext(Dispatchers.Main) {
                store.shareFile(tmp, "application/json", "Exporter Diagnostic HAL JSON")
            }
        }
    }

    // =========================================================================
    // Ephemeris & 1-Tap Apply Target to Camera
    // =========================================================================

    fun setObserverLocation(location: ObserverLocation) {
        preferences.setObserverLocation(location)
        _uiState.update { it.copy(location = location) }
        refreshEphemeris()
    }

    fun selectCompassTarget(targetId: String) {
        _uiState.update { it.copy(compassTargetId = targetId) }
    }

    fun refreshEphemeris() {
        val loc = _uiState.value.location
        val now = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                twilight = AstroEphemeris.computeTwilightSummary(now, loc),
                moon = AstroEphemeris.computeMoonSummary(now, loc),
                targets = AstroEphemeris.computeTargetVisibilities(now, loc)
            )
        }
    }

    fun applyTargetToCamera(vis: TargetVisibility) {
        val st = _uiState.value
        val targetLens = st.rearLenses.minByOrNull { abs(it.focalLength35mmEq - vis.target.recommendedEqMm) }
            ?: st.activeLens
        val targetMode = when (vis.target.category) {
            TargetCategory.SOLAR_SYSTEM -> CaptureMode.LUNAR_PLANETARY
            TargetCategory.DEEP_SKY -> CaptureMode.DEEP_SKY
            else -> CaptureMode.ASTRO_PRO
        }
        if (targetLens != null) {
            selectLens(targetLens)
        }
        selectCaptureMode(targetMode, showHint = false)
        if (targetMode != CaptureMode.LUNAR_PLANETARY && targetLens != null) {
            val npf = targetLens.npfSeconds(targetLens.defaultAperture, vis.target.decDeg)
            val snapped = NpfCalculator.snapToCameraShutter(npf, targetLens.minExposureSec, targetLens.maxExposureSec)
            updateManualSettings {
                it.copy(
                    focusAuto = false,
                    focusDistance = 0.0f,
                    shutterAuto = false,
                    shutterSeconds = snapped
                )
            }
        }
        navigateTo(AppScreen.CAMERA)
        showBanner("Cible ${vis.target.nameFr} appliquée (${targetLens?.displayName ?: ""} · ${targetMode.shortLabel})")
    }

    fun showBanner(message: String) {
        bannerJob?.cancel()
        _uiState.update { it.copy(statusBanner = message) }
        bannerJob = viewModelScope.launch {
            delay(3200L)
            _uiState.update { it.copy(statusBanner = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        captureJob?.cancel()
        orientationTracker.stop()
        cameraController.release()
        nativeEngine.close()
    }
}
