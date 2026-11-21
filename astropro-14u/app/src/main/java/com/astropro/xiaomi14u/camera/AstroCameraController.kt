package com.astropro.xiaomi14u.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Full Camera2 Astrophotography Controller tailored for the Xiaomi 14 Ultra.
 *
 * Implements a 3-tier bulletproof multi-lens switching strategy:
 *   Tier 1: Direct open of physical cameraId (unlocks true RAW_SENSOR + full manual on HyperOS)
 *   Tier 2: Open parent logical cameraId + OutputConfiguration.setPhysicalCameraId(physicalId)
 *   Tier 3: Automatic graceful fallback to logical cameraId "0" + CONTROL_ZOOM_RATIO
 */
class AstroCameraController(
    private val context: Context,
    private val onPreviewYuvFrame: (Image) -> Unit,
    private val onCameraError: (String) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val bgThread = HandlerThread("AstroCamera2Bg").apply { start() }
    private val bgHandler = Handler(bgThread.looper)
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewAnalysisReader: ImageReader? = null
    private var stillRawReader: ImageReader? = null
    private var stillYuvReader: ImageReader? = null

    private var currentLens: LensInfo? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var activeTier: LensAccessTier = LensAccessTier.DIRECT_PHYSICAL
    private var currentSettings: ManualSettings = ManualSettings()
    private var currentMode: CaptureMode = CaptureMode.ASTRO_PRO

    private var boundTextureView: TextureView? = null
    private val lastPreviewAnalysisMs = AtomicLong(0L)
    private val isPreviewAnalysisBusy = AtomicBoolean(false)
    private val isSwitching = AtomicBoolean(false)

    // Synchronized single-shot capture deferreds
    @Volatile
    private var pendingRawImage: CompletableDeferred<Image>? = null
    @Volatile
    private var pendingYuvImage: CompletableDeferred<Image>? = null
    @Volatile
    private var pendingResult: CompletableDeferred<TotalCaptureResult>? = null

    data class CapturedFrame(
        val rawImage: Image?,
        val yuvImage: Image?,
        val captureResult: TotalCaptureResult?,
        val characteristics: CameraCharacteristics?,
        val lens: LensInfo
    ) : AutoCloseable {
        override fun close() {
            runCatching { rawImage?.close() }
            runCatching { yuvImage?.close() }
        }
    }

    fun attachTextureView(textureView: TextureView, lens: LensInfo, settings: ManualSettings, mode: CaptureMode) {
        boundTextureView = textureView
        currentSettings = settings
        currentMode = mode
        currentLens = lens

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                openLens(currentLens ?: lens, textureView)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                currentLens?.let { configureTransform(textureView, it.previewSize, width, height) }
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        if (textureView.isAvailable) {
            openLens(lens, textureView)
        }
    }

    fun resumeCamera(lens: LensInfo, settings: ManualSettings, mode: CaptureMode) {
        currentSettings = settings
        currentMode = mode
        val tv = boundTextureView
        if (tv != null && tv.isAvailable && cameraDevice == null) {
            openLens(lens, tv)
        } else {
            currentLens = lens
        }
    }

    fun switchLens(lens: LensInfo, settings: ManualSettings, mode: CaptureMode) {
        currentSettings = settings
        currentMode = mode
        val tv = boundTextureView
        if (tv != null && tv.isAvailable) {
            openLens(lens, tv)
        } else {
            currentLens = lens
        }
    }

    fun updateManualSettings(settings: ManualSettings, mode: CaptureMode) {
        currentSettings = settings
        currentMode = mode
        applyPreviewRequest()
    }

    @SuppressLint("MissingPermission")
    private fun openLens(lens: LensInfo, textureView: TextureView) {
        if (!isSwitching.compareAndSet(false, true)) return
        bgHandler.post {
            try {
                closeCameraInternal()
                currentLens = lens
                activeTier = lens.accessTier

                // Attempt Tier 1/2 requested ID; automatically fall back to logical "0" + zoomRatio on failure
                val targetOpenId = lens.openCameraId
                openCameraWithFallback(targetOpenId, lens, textureView, triedFallback = false)
            } catch (t: Throwable) {
                isSwitching.set(false)
                onCameraError("Erreur ouverture capteur: ${t.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraWithFallback(
        openId: String,
        lens: LensInfo,
        textureView: TextureView,
        triedFallback: Boolean
    ) {
        try {
            currentCharacteristics = cameraManager.getCameraCharacteristics(
                lens.physicalCameraId?.takeIf { !triedFallback } ?: openId
            )
            cameraManager.openCamera(openId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, lens, textureView, triedFallback)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                    isSwitching.set(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                    if (!triedFallback && openId != "0") {
                        // Tier 3 graceful fallback: logical camera "0" + CONTROL_ZOOM_RATIO
                        activeTier = LensAccessTier.LOGICAL_ZOOM_RATIO
                        openCameraWithFallback("0", lens, textureView, triedFallback = true)
                    } else {
                        isSwitching.set(false)
                        onCameraError("Caméra ID $openId indisponible (code $error)")
                    }
                }
            }, bgHandler)
        } catch (t: Throwable) {
            if (!triedFallback && openId != "0") {
                activeTier = LensAccessTier.LOGICAL_ZOOM_RATIO
                openCameraWithFallback("0", lens, textureView, triedFallback = true)
            } else {
                isSwitching.set(false)
                onCameraError("Impossible d'ouvrir le capteur $openId: ${t.message}")
            }
        }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        lens: LensInfo,
        textureView: TextureView,
        isZoomFallback: Boolean
    ) {
        try {
            val st = textureView.surfaceTexture ?: run {
                isSwitching.set(false)
                return
            }
            val pSize = lens.previewSize
            st.setDefaultBufferSize(pSize.width, pSize.height)
            textureView.post {
                configureTransform(textureView, pSize, textureView.width, textureView.height)
            }

            previewSurface?.release()
            val prevSurf = Surface(st)
            previewSurface = prevSurf

            // Fast preview YUV stream (640x480) for real-time RGB histogram + 10x Focus Loupe FWHM/HFD
            val analysisW = 640
            val analysisH = 480
            previewAnalysisReader = ImageReader.newInstance(analysisW, analysisH, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val now = System.currentTimeMillis()
                    if (now - lastPreviewAnalysisMs.get() >= 95L && isPreviewAnalysisBusy.compareAndSet(false, true)) {
                        lastPreviewAnalysisMs.set(now)
                        try {
                            onPreviewYuvFrame(img)
                        } catch (_: Throwable) {
                        } finally {
                            img.close()
                            isPreviewAnalysisBusy.set(false)
                        }
                    } else {
                        img.close()
                    }
                }, bgHandler)
            }

            // Still capture readers: RAW_SENSOR (when supported & not zoom-fallback) + YUV_420_888
            val rawDim = lens.rawSize
            val useRawStream = lens.supportsRaw && rawDim != null && !isZoomFallback
            if (useRawStream && rawDim != null) {
                stillRawReader = ImageReader.newInstance(
                    rawDim.width, rawDim.height, ImageFormat.RAW_SENSOR, 2
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        val def = pendingRawImage
                        if (def != null && !def.isCompleted) {
                            def.complete(img)
                        } else {
                            img.close()
                        }
                    }, bgHandler)
                }
            }

            val yuvCapSize = lens.yuvCaptureSize
            stillYuvReader = ImageReader.newInstance(
                yuvCapSize.width, yuvCapSize.height, ImageFormat.YUV_420_888, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val def = pendingYuvImage
                    if (def != null && !def.isCompleted) {
                        def.complete(img)
                    } else {
                        img.close()
                    }
                }, bgHandler)
            }

            val outputs = mutableListOf<OutputConfiguration>()
            fun addOutput(surface: Surface) {
                val cfg = OutputConfiguration(surface)
                if (!isZoomFallback &&
                    activeTier == LensAccessTier.LOGICAL_PHYSICAL_STREAM &&
                    lens.physicalCameraId != null
                ) {
                    runCatching { cfg.setPhysicalCameraId(lens.physicalCameraId) }
                }
                outputs.add(cfg)
            }

            addOutput(prevSurf)
            previewAnalysisReader?.surface?.let { addOutput(it) }
            stillRawReader?.surface?.let { addOutput(it) }
            stillYuvReader?.surface?.let { addOutput(it) }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        isSwitching.set(false)
                        applyPreviewRequest()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (useRawStream) {
                            // First try 3-surface LEVEL_3 guaranteed RAW combo (Preview + Analysis YUV + RAW)
                            // keeping true 16-bit RAW active on Xiaomi 14 Ultra auxiliary sensors!
                            stillYuvReader?.close()
                            stillYuvReader = null
                            createCaptureSessionRawOnly3Stream(camera, lens, textureView, isZoomFallback)
                        } else if (!isZoomFallback && lens.openCameraId != "0") {
                            camera.close()
                            cameraDevice = null
                            activeTier = LensAccessTier.LOGICAL_ZOOM_RATIO
                            openCameraWithFallback("0", lens, textureView, triedFallback = true)
                        } else {
                            isSwitching.set(false)
                            onCameraError("Configuration session échouée sur ${lens.displayName}")
                        }
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (t: Throwable) {
            isSwitching.set(false)
            onCameraError("Erreur session Camera2: ${t.message}")
        }
    }

    private fun createCaptureSessionRawOnly3Stream(
        camera: CameraDevice,
        lens: LensInfo,
        textureView: TextureView,
        isZoomFallback: Boolean
    ) {
        try {
            val prevSurf = previewSurface ?: return
            val outputs = mutableListOf<OutputConfiguration>()
            fun addOutput(surface: Surface) {
                val cfg = OutputConfiguration(surface)
                if (!isZoomFallback &&
                    activeTier == LensAccessTier.LOGICAL_PHYSICAL_STREAM &&
                    lens.physicalCameraId != null
                ) {
                    runCatching { cfg.setPhysicalCameraId(lens.physicalCameraId) }
                }
                outputs.add(cfg)
            }
            addOutput(prevSurf)
            previewAnalysisReader?.surface?.let { addOutput(it) }
            stillRawReader?.surface?.let { addOutput(it) }

            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        isSwitching.set(false)
                        applyPreviewRequest()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        // Fall back cleanly to YUV-only if HAL rejects RAW on this lens
                        stillRawReader?.close()
                        stillRawReader = null
                        val yuvCapSize = lens.yuvCaptureSize
                        stillYuvReader = ImageReader.newInstance(
                            yuvCapSize.width, yuvCapSize.height, ImageFormat.YUV_420_888, 2
                        ).apply {
                            setOnImageAvailableListener({ reader ->
                                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                                val def = pendingYuvImage
                                if (def != null && !def.isCompleted) {
                                    def.complete(img)
                                } else {
                                    img.close()
                                }
                            }, bgHandler)
                        }
                        createCaptureSessionWithoutRaw(camera, lens, textureView, isZoomFallback)
                    }
                }
            )
            camera.createCaptureSession(cfg)
        } catch (_: Throwable) {
            stillRawReader?.close()
            stillRawReader = null
            createCaptureSessionWithoutRaw(camera, lens, textureView, isZoomFallback)
        }
    }

    private fun createCaptureSessionWithoutRaw(
        camera: CameraDevice,
        lens: LensInfo,
        textureView: TextureView,
        isZoomFallback: Boolean
    ) {
        try {
            val prevSurf = previewSurface ?: return
            val outputs = mutableListOf<OutputConfiguration>()
            outputs.add(OutputConfiguration(prevSurf))
            previewAnalysisReader?.surface?.let { outputs.add(OutputConfiguration(it)) }
            stillYuvReader?.surface?.let { outputs.add(OutputConfiguration(it)) }

            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        isSwitching.set(false)
                        applyPreviewRequest()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isSwitching.set(false)
                        onCameraError("Session fallback échouée sur ${lens.displayName}")
                    }
                }
            )
            camera.createCaptureSession(cfg)
        } catch (t: Throwable) {
            isSwitching.set(false)
            onCameraError("Erreur session fallback: ${t.message}")
        }
    }

    private fun applyPreviewRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val prevSurf = previewSurface ?: return
        val lens = currentLens ?: return

        bgHandler.post {
            try {
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                builder.addTarget(prevSurf)
                previewAnalysisReader?.surface?.let { builder.addTarget(it) }

                // Cap live preview shutter at 1/4s so viewfinder stays bright, responsive, and smooth for framing/focusing
                applyManualRequestParameters(builder, lens, currentSettings, isStillCapture = false)
                session.setRepeatingRequest(builder.build(), null, bgHandler)
            } catch (_: Throwable) {
                // Ignore transient session close races
            }
        }
    }

    /**
     * Captures a single full-manual still frame (RAW_SENSOR when available + YUV fallback)
     * with exact shutter duration, ISO, variable aperture, infinity focus, and Kelvin WB.
     */
    suspend fun captureSingleFrame(
        settings: ManualSettings,
        mode: CaptureMode,
        preferRaw: Boolean = true
    ): CapturedFrame? {
        val camera = cameraDevice ?: return null
        val session = captureSession ?: return null
        val lens = currentLens ?: return null

        val hasRaw = stillRawReader != null
        val hasYuv = stillYuvReader != null
        val useRaw = hasRaw && (preferRaw || !hasYuv)
        val useYuv = hasYuv && (!useRaw || mode == CaptureMode.TIMELAPSE)
        if (!useRaw && !useYuv) return null

        val rawDef = if (useRaw) CompletableDeferred<Image>() else null
        val yuvDef = if (useYuv) CompletableDeferred<Image>() else null
        val resDef = CompletableDeferred<TotalCaptureResult>()

        pendingRawImage = rawDef
        pendingYuvImage = yuvDef
        pendingResult = resDef

        val submitted = suspendCancellableCoroutine<Boolean> { cont ->
            bgHandler.post {
                try {
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    if (useRaw) {
                        stillRawReader?.surface?.let { builder.addTarget(it) }
                    }
                    if (useYuv) {
                        stillYuvReader?.surface?.let { builder.addTarget(it) }
                    }
                    applyManualRequestParameters(builder, lens, settings, isStillCapture = true)

                    session.capture(
                        builder.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                s: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                if (!resDef.isCompleted) resDef.complete(result)
                            }

                            override fun onCaptureFailed(
                                s: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                if (!resDef.isCompleted) resDef.cancel()
                            }
                        },
                        bgHandler
                    )
                    if (cont.isActive) cont.resume(true)
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

        if (!submitted) {
            pendingRawImage = null
            pendingYuvImage = null
            pendingResult = null
            return null
        }

        val timeoutMs = ((if (settings.shutterAuto) 3.0 else settings.shutterSeconds) * 1000.0).toLong() + 12_000L
        val rawImg = rawDef?.let { withTimeoutOrNull(timeoutMs) { it.await() } }
        val yuvImg = yuvDef?.let { withTimeoutOrNull(timeoutMs) { it.await() } }
        val capRes = withTimeoutOrNull(timeoutMs) { resDef.await() }

        pendingRawImage = null
        pendingYuvImage = null
        pendingResult = null

        if (rawImg == null && yuvImg == null) return null
        return CapturedFrame(
            rawImage = rawImg,
            yuvImage = yuvImg,
            captureResult = capRes,
            characteristics = currentCharacteristics,
            lens = lens
        )
    }

    /**
     * Writes an authentic 16-bit Adobe DNG file using Android's official DngCreator.
     */
    fun writeDngFile(frame: CapturedFrame, outputFile: File): Boolean {
        val raw = frame.rawImage ?: return false
        val res = frame.captureResult ?: return false
        val chars = frame.characteristics ?: return false
        return try {
            DngCreator(chars, res).use { dng ->
                FileOutputStream(outputFile).use { fos ->
                    dng.writeImage(fos, raw)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyManualRequestParameters(
        builder: CaptureRequest.Builder,
        lens: LensInfo,
        settings: ManualSettings,
        isStillCapture: Boolean
    ) {
        // Tier 3 Zoom Ratio fallback when running through logical camera "0"
        if (activeTier == LensAccessTier.LOGICAL_ZOOM_RATIO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, lens.zoomRatioFallback)
            }
        }

        // Exposure: Manual ISO & Shutter OR Auto with EV Compensation
        val manualExposure = !settings.shutterAuto || !settings.isoAuto
        if (manualExposure) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

            val isoVal = settings.iso.coerceIn(lens.minIso, lens.maxIso)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoVal)

            // Keep live preview responsive (max 0.35s preview exposure) while still captures use full 30s!
            val targetShutterSec = if (isStillCapture) {
                settings.shutterSeconds
            } else {
                settings.shutterSeconds.coerceAtMost(0.35)
            }
            val expNs = NpfCalculator.clampToRange(targetShutterSec, lens.minExposureNs, lens.maxExposureNs)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, max(expNs, 16_666_666L))
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.evCompensation)
        }

        // Variable Aperture on Xiaomi 14 Ultra 1" LYT-900 (f/1.63 .. f/4.0)
        if (lens.availableApertures.isNotEmpty()) {
            val chosen = lens.availableApertures.minByOrNull { abs(it - settings.aperture) } ?: lens.defaultAperture
            runCatching { builder.set(CaptureRequest.LENS_APERTURE, chosen) }
        }

        // Manual Focus (0.0f = infinity) or Continuous AF
        if (settings.focusAuto) {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (isStillCapture) CaptureRequest.CONTROL_AF_MODE_AUTO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            val clampedFocus = settings.focusDistance.coerceIn(0.0f, max(0.0f, lens.minFocusDistance))
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, clampedFocus)
        }

        // Manual Planckian Kelvin White Balance or Auto WB
        if (settings.wbAuto) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggbGains(settings.wbKelvin))
        }

        // Disable destructive spatial noise reduction & artificial sharpening in astrophotography still captures
        // so faint pinpoint stars are NEVER eaten by smartphone denoiser!
        if (isStillCapture) {
            runCatching {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
            }
        }
    }

    /**
     * Exact Tanner Helland Planckian blackbody approximation mapped to Camera2 RGGB sensor gains.
     */
    fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = kelvin.coerceIn(2000, 10000) / 100.0
        val r: Double
        val g: Double
        val b: Double

        if (temp <= 66.0) {
            r = 255.0
            g = (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
            b = if (temp <= 19.0) 0.0 else (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        } else {
            r = (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
            g = (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
            b = 255.0
        }

        // Normalize around green = 1.0f for linear sensor WB gains
        val gSafe = max(20.0, g)
        val rGain = (r / gSafe * 1.45).toFloat().coerceIn(0.6f, 3.2f)
        val bGain = (b / gSafe * 1.45).toFloat().coerceIn(0.6f, 3.2f)
        return RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
    }

    private fun configureTransform(textureView: TextureView, previewSize: Size, viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // Camera sensor is naturally landscape (e.g. 1440x1080) while portrait TextureView is 3:4
        val bufW = previewSize.height.toFloat()
        val bufH = previewSize.width.toFloat()
        val scaleX = viewWidth.toFloat() / bufW
        val scaleY = viewHeight.toFloat() / bufH
        val scale = max(scaleX, scaleY)
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        matrix.setScale(scale / scaleX, scale / scaleY, cx, cy)
        textureView.setTransform(matrix)
    }

    private fun closeCameraInternal() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { previewAnalysisReader?.close() }
        previewAnalysisReader = null
        runCatching { stillRawReader?.close() }
        stillRawReader = null
        runCatching { stillYuvReader?.close() }
        stillYuvReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    fun closeCamera() {
        bgHandler.post { closeCameraInternal() }
    }

    fun release() {
        closeCamera()
        bgThread.quitSafely()
        sessionExecutor.shutdown()
    }
}
