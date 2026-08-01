package com.pavelpapko.arroulette

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PointF
import android.net.Uri
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Coordinates3d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.sceneview.RenderQuality
import io.github.sceneview.ar.ARSceneView
import java.nio.ByteOrder
import java.text.DateFormat
import java.util.Date
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var arContainer: FrameLayout
    private lateinit var overlayView: MeasurementOverlayView
    private lateinit var resultText: TextView
    private lateinit var liveDistanceText: TextView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var qualityDot: View
    private lateinit var setPointButton: Button
    private lateinit var undoButton: Button
    private lateinit var clearButton: Button
    private lateinit var unitButton: Button
    private lateinit var gridButton: Button
    private lateinit var historyButton: Button
    private lateinit var photoButton: Button
    private lateinit var menuButton: Button
    private lateinit var modePoint: TextView
    private lateinit var modeRuler: TextView
    private lateinit var modeArea: TextView
    private lateinit var modeHeight: TextView
    private lateinit var modeDistance: TextView

    private var arComposeView: ComposeView? = null
    private var arSession: Session? = null
    private var latestFrame: Frame? = null
    private var lastFrameProcessingNanos = 0L
    private var eisEnabled = false

    // Reused projection buffers avoid hundreds of short-lived arrays per minute.
    private val projectionViewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val projectionWorldPoint = FloatArray(4)
    private val projectionClipPoint = FloatArray(4)
    private val projectionNdcInput = FloatArray(2)
    private val projectionEisOutput = FloatArray(3)

    private lateinit var powerManager: PowerManager
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalHeadroom = Float.NaN
    private var lastThermalHeadroomReadNanos = 0L
    private var thermalListenerRegistered = false
    private var thermalPaused = false
    private val thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
        handleThermalStatusChanged(status)
    }
    private val measurementAnchors = mutableListOf<Anchor>()
    private var displayUnit = DisplayUnit.CENTIMETERS
    private var measurementMode = MeasurementMode.RULER
    private var gridEnabled = false
    private var depthSupported = false
    private var measurementSaved = false
    private var lastStatusUpdateMillis = 0L
    private var lastDepthReadMillis = 0L
    private var lastDepthReading: DepthReading? = null
    private var latestTargetEstimate = emptyTargetEstimate()
    private var latestSelectedHit: SelectedHit? = null
    private var latestResultEstimate: ScalarEstimate? = null
    private var latestLiveEstimate: ScalarEstimate? = null

    private val targetStabilizer = TargetStabilizer()
    private val motionStabilityGate = MotionStabilityGate()
    private var latestMotionEstimate = MotionEstimate(
        translationSpeedMetersPerSecond = 0f,
        angularSpeedDegreesPerSecond = 0f,
        stable = false,
        excessive = false
    )
    private var resultFilter = createResultFilter(MeasurementMode.RULER)
    private val liveDistanceFilter = RobustScalarFilter(
        absoluteTolerance = 0.008f,
        relativeTolerance = 0.008f,
        minimumStableSamples = 8
    )

    private lateinit var measurementStore: MeasurementStore

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initializeArScene() else showCameraPermissionDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreUiState(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        measurementStore = MeasurementStore(this)
        bindActions()
        updateControls()
        initializeThermalMonitoring()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeArScene()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initializeThermalMonitoring() {
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        thermalStatus = powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(mainExecutor, thermalStatusListener)
        thermalListenerRegistered = true
        if (thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
            thermalPaused = true
            showThermalStateImmediately()
        }
    }

    private fun handleThermalStatusChanged(status: Int) {
        thermalStatus = status
        when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> suspendArForHeat()
            thermalPaused && status <= PowerManager.THERMAL_STATUS_LIGHT -> resumeArAfterCooling()
            else -> showThermalStateImmediately()
        }
    }

    private fun suspendArForHeat() {
        if (thermalPaused && arComposeView == null) {
            showThermalStateImmediately()
            return
        }
        thermalPaused = true
        detachAllAnchors()
        targetStabilizer.reset()
        motionStabilityGate.reset()
        liveDistanceFilter.reset()
        latestTargetEstimate = emptyTargetEstimate()
        latestLiveEstimate = null
        latestSelectedHit = null
        latestFrame = null
        arSession = null
        lastFrameProcessingNanos = 0L
        lastDepthReading = null
        lastDepthReadMillis = 0L
        arComposeView?.disposeComposition()
        (arComposeView?.parent as? ViewGroup)?.removeView(arComposeView)
        arComposeView = null
        clearOverlay()
        showThermalStateImmediately()
        toast(getString(R.string.thermal_camera_paused))
    }

    private fun resumeArAfterCooling() {
        if (!thermalPaused) return
        thermalPaused = false
        lastFrameProcessingNanos = 0L
        lastThermalHeadroomReadNanos = 0L
        thermalHeadroom = Float.NaN
        motionStabilityGate.reset()
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initializeArScene()
            toast(getString(R.string.thermal_camera_resumed))
        }
    }

    private fun showThermalStateImmediately() {
        if (!::statusText.isInitialized) return
        val effectiveStatus = effectiveThermalStatus()
        val message = when {
            thermalPaused || thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ->
                getString(R.string.thermal_critical)
            effectiveStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                getString(R.string.thermal_severe)
            effectiveStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
                getString(R.string.thermal_moderate)
            else -> return
        }
        statusText.text = message
        val color = if (effectiveStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            R.color.danger
        } else {
            R.color.warning
        }
        qualityDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, color)
        )
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        val preferences = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
        displayUnit = runCatching {
            DisplayUnit.valueOf(
                savedInstanceState?.getString(STATE_UNIT)
                    ?: preferences.getString(STATE_UNIT, DisplayUnit.CENTIMETERS.name)
                    ?: DisplayUnit.CENTIMETERS.name
            )
        }.getOrDefault(DisplayUnit.CENTIMETERS)
        measurementMode = runCatching {
            MeasurementMode.valueOf(
                savedInstanceState?.getString(STATE_MODE)
                    ?: preferences.getString(STATE_MODE, MeasurementMode.RULER.name)
                    ?: MeasurementMode.RULER.name
            )
        }.getOrDefault(MeasurementMode.RULER)
        gridEnabled = savedInstanceState?.getBoolean(STATE_GRID)
            ?: preferences.getBoolean(STATE_GRID, false)
        resultFilter = createResultFilter(measurementMode)
    }

    private fun bindViews() {
        arContainer = findViewById(R.id.ar_container)
        overlayView = findViewById(R.id.measurement_overlay)
        resultText = findViewById(R.id.result_text)
        liveDistanceText = findViewById(R.id.live_distance_text)
        statusText = findViewById(R.id.status_text)
        hintText = findViewById(R.id.hint_text)
        qualityDot = findViewById(R.id.quality_dot)
        setPointButton = findViewById(R.id.set_point_button)
        undoButton = findViewById(R.id.undo_button)
        clearButton = findViewById(R.id.clear_button)
        unitButton = findViewById(R.id.unit_button)
        gridButton = findViewById(R.id.grid_button)
        historyButton = findViewById(R.id.history_button)
        photoButton = findViewById(R.id.photo_button)
        menuButton = findViewById(R.id.menu_button)
        modePoint = findViewById(R.id.mode_point)
        modeRuler = findViewById(R.id.mode_ruler)
        modeArea = findViewById(R.id.mode_area)
        modeHeight = findViewById(R.id.mode_height)
        modeDistance = findViewById(R.id.mode_distance)
    }

    private fun bindActions() {
        setPointButton.setOnClickListener { placePoint() }
        undoButton.setOnClickListener { undoLastPoint() }
        clearButton.setOnClickListener { clearMeasurement() }
        unitButton.setOnClickListener {
            displayUnit = displayUnit.next()
            persistUiState()
            updateControls()
            latestFrame?.let(::updateFrameUi)
        }
        gridButton.setOnClickListener {
            gridEnabled = !gridEnabled
            persistUiState()
            updateControls()
            latestFrame?.let(::updateFrameUi)
        }
        historyButton.setOnClickListener { showHistory() }
        photoButton.setOnClickListener { saveScreenshot() }
        menuButton.setOnClickListener { showMainMenu() }
        modePoint.setOnClickListener { selectMode(MeasurementMode.POINT) }
        modeRuler.setOnClickListener { selectMode(MeasurementMode.RULER) }
        modeArea.setOnClickListener { selectMode(MeasurementMode.AREA) }
        modeHeight.setOnClickListener { selectMode(MeasurementMode.HEIGHT) }
        modeDistance.setOnClickListener { selectMode(MeasurementMode.DISTANCE_TO_OBJECT) }
    }

    private fun initializeArScene() {
        if (arComposeView != null || thermalPaused || isFinishing || isDestroyed) return

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    planeRenderer = false,
                    renderQuality = RenderQuality.Performance,
                    mainLightNode = null,
                    fillLightNode = null,
                    sessionCameraConfig = { session -> selectPowerEfficientCameraConfig(session) },
                    sessionConfiguration = { session, config ->
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                        config.depthMode = if (depthSupported) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                        eisEnabled = session.isImageStabilizationModeSupported(
                            Config.ImageStabilizationMode.EIS
                        )
                        config.imageStabilizationMode = if (eisEnabled) {
                            Config.ImageStabilizationMode.EIS
                        } else {
                            Config.ImageStabilizationMode.OFF
                        }
                    },
                    onSessionCreated = { session ->
                        arSession = session
                        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    },
                    onSessionPaused = {
                        latestFrame = null
                        motionStabilityGate.reset()
                    },
                    onSessionUpdated = { session, frame ->
                        arSession = session
                        latestFrame = frame
                        if (!thermalPaused) {
                            val nowNanos = SystemClock.elapsedRealtimeNanos()
                            refreshThermalHeadroom(nowNanos)
                            if (
                                lastFrameProcessingNanos == 0L ||
                                nowNanos - lastFrameProcessingNanos >= currentProcessingIntervalNanos()
                            ) {
                                lastFrameProcessingNanos = nowNanos
                                updateFrameUi(frame, nowNanos)
                            }
                        }
                    },
                    onSessionFailed = { exception ->
                        arSession = null
                        runOnUiThread {
                            statusText.text = getString(R.string.ar_unavailable)
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(R.string.ar_start_failed)
                                .setMessage(
                                    exception.localizedMessage
                                        ?: getString(R.string.arcore_check_message)
                                )
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                )
            }
        }

        arComposeView = composeView
        arContainer.addView(composeView, 0)
    }

    private fun selectPowerEfficientCameraConfig(session: Session): CameraConfig = runCatching {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
            .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        val configurations = session.getSupportedCameraConfigs(filter)
        configurations.minWithOrNull(
            compareBy<CameraConfig> {
                abs(
                    it.textureSize.width.toLong() * it.textureSize.height.toLong() -
                        TARGET_CAMERA_TEXTURE_PIXELS
                )
            }.thenBy {
                it.imageSize.width.toLong() * it.imageSize.height.toLong()
            }
        )
    }.getOrNull() ?: session.cameraConfig

    private fun refreshThermalHeadroom(nowNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (
            lastThermalHeadroomReadNanos != 0L &&
            nowNanos - lastThermalHeadroomReadNanos < THERMAL_HEADROOM_READ_INTERVAL_NANOS
        ) return
        lastThermalHeadroomReadNanos = nowNanos
        val headroom = runCatching { powerManager.getThermalHeadroom(0) }.getOrNull()
        thermalHeadroom = headroom?.takeIf { it.isFinite() && it > 0f } ?: Float.NaN
    }

    private fun effectiveThermalStatus(): Int {
        val headroomStatus = when {
            !thermalHeadroom.isFinite() -> PowerManager.THERMAL_STATUS_NONE
            thermalHeadroom >= 0.95f -> PowerManager.THERMAL_STATUS_SEVERE
            thermalHeadroom >= 0.85f -> PowerManager.THERMAL_STATUS_MODERATE
            thermalHeadroom >= 0.75f -> PowerManager.THERMAL_STATUS_LIGHT
            else -> PowerManager.THERMAL_STATUS_NONE
        }
        return max(thermalStatus, headroomStatus)
    }

    private fun currentProcessingIntervalNanos(): Long = when {
        effectiveThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE -> 400_000_000L
        effectiveThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE -> 250_000_000L
        effectiveThermalStatus() >= PowerManager.THERMAL_STATUS_LIGHT -> 143_000_000L
        else -> 100_000_000L
    }

    private fun placePoint() {
        if (thermalPaused) {
            toast(getString(R.string.thermal_camera_paused))
            return
        }
        val frame = latestFrame
        val sceneView = arComposeView
        if (frame == null || sceneView == null || frame.camera.trackingState != TrackingState.TRACKING) {
            toast(getString(R.string.wait_for_tracking))
            return
        }
        if (!latestMotionEstimate.stable) {
            toast(getString(R.string.anti_shake_wait))
            return
        }

        if (measurementMode != MeasurementMode.DISTANCE_TO_OBJECT && isMeasurementComplete()) {
            clearMeasurement()
        }

        val depthReading = readRawDepth(frame, force = true)
        val selectedHit = findBestHit(
            frame = frame,
            width = sceneView.width,
            height = sceneView.height,
            depthReading = depthReading,
            highAccuracy = true
        )
        if (selectedHit == null) {
            toast(getString(R.string.surface_not_found))
            return
        }

        val targetEstimate = targetStabilizer.estimate(selectedHit.distanceMeters)
        if (!targetEstimate.stable) {
            toast(getString(R.string.hold_phone_still))
            return
        }

        if (measurementMode == MeasurementMode.DISTANCE_TO_OBJECT) {
            val liveEstimate = latestLiveEstimate
            if (liveEstimate == null || !liveEstimate.stable) {
                toast(getString(R.string.hold_phone_for_distance))
                return
            }
            measurementStore.add(
                MeasurementRecord(
                    timestamp = System.currentTimeMillis(),
                    value = liveEstimate.value,
                    kind = MeasurementKind.OBJECT_DISTANCE
                )
            )
            toast(getString(R.string.saved_result, displayUnit.formatDistance(liveEstimate.value)))
            return
        }

        runCatching {
            if (measurementMode == MeasurementMode.POINT) detachAllAnchors()
            // Anchor the current validated hit result instead of averaging world-space poses
            // across frames. ARCore can refine its world origin over time; a trackable anchor keeps
            // the physical point stable while the numerical world coordinates are adjusted.
            val newAnchor = selectedHit.hit.createAnchor()
            if (isTooCloseToExisting(newAnchor)) {
                newAnchor.detach()
                toast(getString(R.string.points_too_close))
                return
            }
            measurementAnchors.add(newAnchor)
            measurementSaved = false
            resetResultFilter()
            targetStabilizer.reset()
            motionStabilityGate.reset()
            latestMotionEstimate = emptyMotionEstimate()
            latestTargetEstimate = emptyTargetEstimate()
            liveDistanceFilter.reset()
        }.onFailure {
            toast(getString(R.string.anchor_failed, it.localizedMessage ?: getString(R.string.ar_error)))
        }

        updateControls()
        updateFrameUi(frame)
    }

    private fun isTooCloseToExisting(newAnchor: Anchor): Boolean {
        return measurementAnchors.any { existing ->
            MeasurementMath.distanceMeters(existing.pose.translation, newAnchor.pose.translation) < MIN_POINT_SEPARATION_METERS
        }
    }

    private fun updateFrameUi(frame: Frame) {
        updateFrameUi(frame, SystemClock.elapsedRealtimeNanos())
    }

    private fun updateFrameUi(frame: Frame, processingTimeNanos: Long) {
        val sceneView = arComposeView ?: return
        if (sceneView.width <= 0 || sceneView.height <= 0 || thermalPaused) return

        val camera = frame.camera
        latestMotionEstimate = if (camera.trackingState == TrackingState.TRACKING) {
            motionStabilityGate.add(
                position = camera.pose.translation,
                rotationQuaternion = camera.pose.rotationQuaternion,
                timestampNanos = processingTimeNanos
            )
        } else {
            motionStabilityGate.reset()
            emptyMotionEstimate()
        }

        val selectedHit = if (
            camera.trackingState == TrackingState.TRACKING &&
            !latestMotionEstimate.excessive
        ) {
            val depthReading = readRawDepth(frame)
            findBestHit(frame, sceneView.width, sceneView.height, depthReading)
        } else {
            // Skip AR ray casts during fast movement: they cannot produce a reliable point and
            // only add CPU/GPU pressure while the user is scanning the room.
            null
        }
        latestSelectedHit = selectedHit

        when {
            latestMotionEstimate.excessive -> {
                targetStabilizer.reset()
                liveDistanceFilter.reset()
                latestTargetEstimate = emptyTargetEstimate()
            }
            selectedHit != null -> {
                if (latestMotionEstimate.stable) {
                    targetStabilizer.add(
                        point = selectedHit.hit.hitPose.translation,
                        source = selectedHit.source,
                        depthConfidence = selectedHit.depthConfidence,
                        distanceFromCameraMeters = selectedHit.distanceMeters
                    )
                    liveDistanceFilter.add(selectedHit.distanceMeters)
                } else {
                    targetStabilizer.hold()
                }
                latestTargetEstimate = targetStabilizer.estimate(selectedHit.distanceMeters)
            }
            else -> {
                targetStabilizer.miss()
                latestTargetEstimate = emptyTargetEstimate()
                liveDistanceFilter.reset()
            }
        }
        latestLiveEstimate = liveDistanceFilter.estimate()

        val projectedPoints = measurementAnchors.mapNotNull { anchor ->
            anchor.takeIf { it.trackingState == TrackingState.TRACKING }
                ?.pose
                ?.let { projectToScreen(it, frame, camera, sceneView.width, sceneView.height) }
        }

        val rawResult = currentMeasurementValue()
        if (rawResult != null) {
            resultFilter.add(rawResult)
            latestResultEstimate = resultFilter.estimate()
            saveCompletedMeasurementIfStable(latestResultEstimate)
        } else {
            latestResultEstimate = null
        }

        val displayValue = when (measurementMode) {
            MeasurementMode.DISTANCE_TO_OBJECT -> latestLiveEstimate?.value
            MeasurementMode.POINT -> null
            else -> latestResultEstimate?.value
        }
        val formattedValue = formatCurrentValue(displayValue)
        val reticleState = when {
            selectedHit == null -> MeasurementOverlayView.ReticleState.INVALID
            latestMotionEstimate.stable && latestTargetEstimate.stable ->
                MeasurementOverlayView.ReticleState.STABLE
            else -> MeasurementOverlayView.ReticleState.ACQUIRING
        }
        val closePolygon = measurementMode == MeasurementMode.AREA && isMeasurementComplete()

        runOnUiThread {
            overlayView.update(
                points = projectedPoints,
                closePolygon = closePolygon,
                label = if (isMeasurementComplete()) formattedValue else null,
                reticleState = reticleState,
                showGrid = gridEnabled,
                measurementMode = measurementMode
            )
            resultText.text = when (measurementMode) {
                MeasurementMode.POINT -> if (measurementAnchors.isNotEmpty()) {
                    getString(R.string.point_fixed)
                } else {
                    "—"
                }
                else -> formattedValue ?: "—"
            }
            liveDistanceText.text = buildLiveDistanceText(camera, selectedHit)
            updateDynamicHint()
            updateActionAvailability()

            val now = System.currentTimeMillis()
            if (now - lastStatusUpdateMillis >= STATUS_UPDATE_INTERVAL_MS) {
                lastStatusUpdateMillis = now
                updateQualityStatus(camera, selectedHit)
            }
        }
    }

    private fun findBestHit(
        frame: Frame,
        width: Int,
        height: Int,
        depthReading: DepthReading?,
        highAccuracy: Boolean = false
    ): SelectedHit? {
        if (width <= 0 || height <= 0) return null
        val centerX = width / 2f
        val centerY = height / 2f
        val offset = 8f * resources.displayMetrics.density
        val centerSample = ScreenSample(centerX, centerY, 0f)
        val samplePoints = when {
            effectiveThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE ->
                listOf(centerSample)
            highAccuracy && latestMotionEstimate.stable -> listOf(
                centerSample,
                ScreenSample(centerX - offset, centerY, offset),
                ScreenSample(centerX + offset, centerY, offset),
                ScreenSample(centerX, centerY - offset, offset),
                ScreenSample(centerX, centerY + offset, offset)
            )
            latestMotionEstimate.stable -> listOf(
                centerSample,
                ScreenSample(centerX - offset, centerY, offset),
                ScreenSample(centerX + offset, centerY, offset)
            )
            else -> listOf(centerSample)
        }

        val cameraPose = frame.camera.pose
        val candidates = mutableListOf<HitCandidate>()
        samplePoints.forEach { sample ->
            for (hit in frame.hitTest(sample.x, sample.y)) {
                val source = validHitSource(hit) ?: continue
                val distance = distanceBetweenPoses(cameraPose, hit.hitPose)
                if (distance !in MIN_TARGET_DISTANCE_METERS..MAX_TARGET_DISTANCE_METERS) continue
                candidates.add(HitCandidate(hit, source, distance, sample.offsetPixels))
                break
            }
        }
        if (candidates.isEmpty()) return null

        val center = MeasurementMath.medianPoint(candidates.map { it.hit.hitPose.translation })
        val selected = candidates.minByOrNull { candidate ->
            val clusterDistance = MeasurementMath.distanceMeters(candidate.hit.hitPose.translation, center)
            val sourcePenalty = when (candidate.source) {
                HitSource.DEPTH -> 0f
                HitSource.PLANE -> 0.003f
                HitSource.FEATURE_POINT -> 0.012f
            }
            val screenPenalty = candidate.offsetPixels * 0.00002f
            val depthPenalty = if (depthReading != null && depthReading.confidence >= 0.45f) {
                val relativeDifference = abs(candidate.distanceMeters - depthReading.meters) /
                    max(0.10f, depthReading.meters)
                relativeDifference * 0.015f
            } else {
                0f
            }
            clusterDistance + sourcePenalty + screenPenalty + depthPenalty
        } ?: return null

        return SelectedHit(
            hit = selected.hit,
            source = selected.source,
            distanceMeters = selected.distanceMeters,
            depthConfidence = depthReading?.confidence
        )
    }

    private fun validHitSource(hit: HitResult): HitSource? {
        val trackable = hit.trackable
        return when (trackable) {
            is DepthPoint -> HitSource.DEPTH.takeIf {
                trackable.trackingState == TrackingState.TRACKING
            }
            is Plane -> HitSource.PLANE.takeIf {
                trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(hit.hitPose)
            }
            is Point -> HitSource.FEATURE_POINT.takeIf {
                trackable.trackingState == TrackingState.TRACKING &&
                    trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            else -> null
        }
    }

    private fun readRawDepth(frame: Frame, force: Boolean = false): DepthReading? {
        if (!depthSupported) return null
        val now = System.currentTimeMillis()
        val cached = lastDepthReading?.takeIf {
            now - it.capturedAtMillis <= DEPTH_CACHE_LIFETIME_MS
        }
        if (
            effectiveThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE ||
            !latestMotionEstimate.stable
        ) {
            return cached
        }
        if (!force && now - lastDepthReadMillis < currentDepthReadIntervalMillis()) {
            return cached
        }
        lastDepthReadMillis = now

        val reading = try {
            frame.acquireRawDepthImage16Bits().use { depthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    sampleCenterDepth(depthImage, confidenceImage, frame.timestamp == depthImage.timestamp)
                }
            }
        } catch (_: NotYetAvailableException) {
            null
        } catch (_: IllegalStateException) {
            null
        }

        if (reading != null) lastDepthReading = reading
        return reading ?: lastDepthReading?.takeIf { now - it.capturedAtMillis <= DEPTH_CACHE_LIFETIME_MS }
    }

    private fun currentDepthReadIntervalMillis(): Long = when {
        effectiveThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE -> 1_500L
        effectiveThermalStatus() >= PowerManager.THERMAL_STATUS_LIGHT -> 1_000L
        else -> 750L
    }

    private fun sampleCenterDepth(
        depthImage: android.media.Image,
        confidenceImage: android.media.Image,
        fresh: Boolean
    ): DepthReading? {
        val depthPlane = depthImage.planes.firstOrNull() ?: return null
        val confidencePlane = confidenceImage.planes.firstOrNull() ?: return null
        val depthBuffer = depthPlane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val confidenceBuffer = confidencePlane.buffer.duplicate()
        val depthValues = mutableListOf<Float>()
        val confidenceValues = mutableListOf<Float>()
        val centerX = depthImage.width / 2
        val centerY = depthImage.height / 2

        for (deltaY in -DEPTH_SAMPLE_RADIUS..DEPTH_SAMPLE_RADIUS) {
            for (deltaX in -DEPTH_SAMPLE_RADIUS..DEPTH_SAMPLE_RADIUS) {
                val x = (centerX + deltaX).coerceIn(0, depthImage.width - 1)
                val y = (centerY + deltaY).coerceIn(0, depthImage.height - 1)
                val depthIndex = y * depthPlane.rowStride + x * depthPlane.pixelStride
                val confidenceIndex = y * confidencePlane.rowStride + x * confidencePlane.pixelStride
                if (depthIndex + 1 >= depthBuffer.limit() || confidenceIndex >= confidenceBuffer.limit()) continue

                val millimeters = depthBuffer.getShort(depthIndex).toInt() and 0xFFFF
                val confidence = (confidenceBuffer.get(confidenceIndex).toInt() and 0xFF) / 255f
                if (millimeters > 0 && confidence >= MIN_RAW_DEPTH_CONFIDENCE) {
                    depthValues.add(millimeters / 1000f)
                    confidenceValues.add(confidence)
                }
            }
        }

        if (depthValues.size < MIN_RAW_DEPTH_SAMPLES) return null
        val freshnessFactor = if (fresh) 1f else 0.85f
        return DepthReading(
            meters = MeasurementMath.median(depthValues),
            confidence = (MeasurementMath.median(confidenceValues) * freshnessFactor).coerceIn(0f, 1f),
            capturedAtMillis = System.currentTimeMillis()
        )
    }

    private fun currentMeasurementValue(): Float? {
        val expectedCount = expectedPointCount()
        if (expectedCount == 0 || measurementAnchors.size != expectedCount) return null
        if (measurementAnchors.any { it.trackingState != TrackingState.TRACKING }) return null
        val points = measurementAnchors.map { it.pose.translation }
        return when (measurementMode) {
            MeasurementMode.RULER -> MeasurementMath.distanceMeters(points[0], points[1])
            MeasurementMode.HEIGHT -> MeasurementMath.heightMeters(points[0], points[1])
            MeasurementMode.AREA -> MeasurementMath.polygonAreaSquareMeters(points)
            MeasurementMode.POINT, MeasurementMode.DISTANCE_TO_OBJECT -> null
        }?.takeIf { it >= minimumResultValue() }
    }

    private fun minimumResultValue(): Float = when (measurementMode) {
        MeasurementMode.AREA -> MIN_AREA_SQUARE_METERS
        else -> MIN_MEASUREMENT_METERS
    }

    private fun saveCompletedMeasurementIfStable(estimate: ScalarEstimate?) {
        if (measurementSaved || estimate == null || !estimate.stable || !isMeasurementComplete()) return
        val kind = when (measurementMode) {
            MeasurementMode.RULER -> MeasurementKind.DISTANCE
            MeasurementMode.HEIGHT -> MeasurementKind.HEIGHT
            MeasurementMode.AREA -> MeasurementKind.AREA
            else -> return
        }
        measurementStore.add(
            MeasurementRecord(
                timestamp = System.currentTimeMillis(),
                value = estimate.value,
                kind = kind
            )
        )
        measurementSaved = true
    }

    private fun formatCurrentValue(value: Float?): String? {
        value ?: return null
        return if (measurementMode == MeasurementMode.AREA) {
            displayUnit.formatArea(value)
        } else {
            displayUnit.formatDistance(value)
        }
    }

    private fun buildLiveDistanceText(camera: Camera, selectedHit: SelectedHit?): String {
        if (camera.trackingState != TrackingState.TRACKING) {
            return trackingFailureText(camera.trackingFailureReason)
        }
        if (selectedHit == null) return getString(R.string.aim_at_surface)
        val liveValue = latestLiveEstimate?.value ?: selectedHit.distanceMeters
        val sourceText = when (selectedHit.source) {
            HitSource.DEPTH -> getString(R.string.source_depth)
            HitSource.PLANE -> getString(R.string.source_plane)
            HitSource.FEATURE_POINT -> getString(R.string.source_feature)
        }
        return getString(
            R.string.live_distance_format,
            displayUnit.formatDistance(liveValue),
            sourceText
        )
    }

    private fun updateQualityStatus(camera: Camera, selectedHit: SelectedHit?) {
        val status: String
        val color: Int
        val effectiveThermalStatus = effectiveThermalStatus()
        when {
            thermalPaused || thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                status = getString(R.string.thermal_critical)
                color = ContextCompat.getColor(this, R.color.danger)
            }
            effectiveThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                status = getString(R.string.thermal_severe)
                color = ContextCompat.getColor(this, R.color.danger)
            }
            effectiveThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> {
                status = getString(R.string.thermal_moderate)
                color = ContextCompat.getColor(this, R.color.warning)
            }
            camera.trackingState != TrackingState.TRACKING -> {
                status = trackingFailureText(camera.trackingFailureReason)
                color = ContextCompat.getColor(this, R.color.danger)
            }
            !latestMotionEstimate.stable -> {
                status = getString(R.string.anti_shake_stabilizing)
                color = ContextCompat.getColor(this, R.color.warning)
            }
            selectedHit == null -> {
                status = getString(R.string.searching_surface)
                color = ContextCompat.getColor(this, R.color.warning)
            }
            !latestTargetEstimate.stable -> {
                status = getString(R.string.stabilizing)
                color = ContextCompat.getColor(this, R.color.warning)
            }
            selectedHit.source == HitSource.DEPTH &&
                (latestTargetEstimate.depthConfidence ?: 0f) >= EXCELLENT_DEPTH_CONFIDENCE -> {
                status = getString(R.string.excellent_quality)
                color = ContextCompat.getColor(this, R.color.success)
            }
            else -> {
                status = getString(R.string.good_quality)
                color = ContextCompat.getColor(this, R.color.success)
            }
        }
        statusText.text = status
        qualityDot.backgroundTintList = ColorStateList.valueOf(color)
        statusText.contentDescription = buildString {
            append(status)
            append(if (depthSupported) ". Depth API включён" else ". Depth API недоступен")
            append(if (eisEnabled) ". Стабилизация изображения включена" else ". Стабилизация изображения недоступна")
        }
    }

    private fun trackingFailureText(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.NONE -> getString(R.string.move_phone_slowly)
        TrackingFailureReason.BAD_STATE -> getString(R.string.tracking_bad_state)
        TrackingFailureReason.INSUFFICIENT_LIGHT -> getString(R.string.more_light_needed)
        TrackingFailureReason.EXCESSIVE_MOTION -> getString(R.string.move_phone_slower)
        TrackingFailureReason.INSUFFICIENT_FEATURES -> getString(R.string.aim_at_textured_surface)
        TrackingFailureReason.CAMERA_UNAVAILABLE -> getString(R.string.camera_unavailable)
    }

    private fun projectToScreen(
        pose: Pose,
        frame: Frame,
        camera: Camera,
        width: Int,
        height: Int
    ): PointF? {
        if (width <= 0 || height <= 0) return null
        projectionWorldPoint[0] = pose.tx()
        projectionWorldPoint[1] = pose.ty()
        projectionWorldPoint[2] = pose.tz()
        projectionWorldPoint[3] = 1f

        camera.getViewMatrix(projectionViewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.05f, 100f)
        Matrix.multiplyMM(
            viewProjectionMatrix,
            0,
            projectionMatrix,
            0,
            projectionViewMatrix,
            0
        )
        Matrix.multiplyMV(
            projectionClipPoint,
            0,
            viewProjectionMatrix,
            0,
            projectionWorldPoint,
            0
        )
        if (projectionClipPoint[3] <= 0f) return null

        var normalizedX = projectionClipPoint[0] / projectionClipPoint[3]
        var normalizedY = projectionClipPoint[1] / projectionClipPoint[3]
        if (eisEnabled) {
            projectionNdcInput[0] = normalizedX
            projectionNdcInput[1] = normalizedY
            try {
                frame.transformCoordinates3d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    projectionNdcInput,
                    Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES,
                    projectionEisOutput
                )
                normalizedX = projectionEisOutput[0]
                normalizedY = projectionEisOutput[1]
            } catch (_: IllegalArgumentException) {
                // Fall back to standard NDC if a vendor ARCore build rejects EIS coordinates.
            }
        }

        return PointF(
            (normalizedX + 1f) * 0.5f * width,
            (1f - normalizedY) * 0.5f * height
        )
    }

    private fun distanceBetweenPoses(first: Pose, second: Pose): Float {
        val dx = first.tx() - second.tx()
        val dy = first.ty() - second.ty()
        val dz = first.tz() - second.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun undoLastPoint() {
        targetStabilizer.reset()
        liveDistanceFilter.reset()
        latestTargetEstimate = emptyTargetEstimate()
        latestLiveEstimate = null
        if (measurementAnchors.isNotEmpty()) {
            val anchor = measurementAnchors.removeAt(measurementAnchors.lastIndex)
            runCatching { anchor.detach() }
        }
        measurementSaved = false
        resetResultFilter()
        updateControls()
        latestFrame?.let(::updateFrameUi) ?: clearOverlay()
    }

    private fun clearMeasurement() {
        detachAllAnchors()
        targetStabilizer.reset()
        liveDistanceFilter.reset()
        latestTargetEstimate = emptyTargetEstimate()
        latestLiveEstimate = null
        latestSelectedHit = null
        measurementSaved = false
        resetResultFilter()
        latestResultEstimate = null
        resultText.text = "—"
        clearOverlay()
        updateControls()
    }

    private fun detachAllAnchors() {
        measurementAnchors.forEach { runCatching { it.detach() } }
        measurementAnchors.clear()
    }

    private fun clearOverlay() {
        overlayView.update(
            points = emptyList(),
            closePolygon = false,
            label = null,
            reticleState = if (latestSelectedHit == null) {
                MeasurementOverlayView.ReticleState.INVALID
            } else {
                MeasurementOverlayView.ReticleState.ACQUIRING
            },
            showGrid = gridEnabled,
            measurementMode = measurementMode
        )
    }

    private fun selectMode(mode: MeasurementMode) {
        if (measurementMode == mode) return
        detachAllAnchors()
        targetStabilizer.reset()
        liveDistanceFilter.reset()
        latestTargetEstimate = emptyTargetEstimate()
        latestLiveEstimate = null
        latestSelectedHit = null
        measurementMode = mode
        measurementSaved = false
        resultFilter = createResultFilter(mode)
        latestResultEstimate = null
        resultText.text = "—"
        persistUiState()
        clearOverlay()
        updateControls()
    }

    private fun updateControls() {
        val complete = isMeasurementComplete()
        setPointButton.text = when (measurementMode) {
            MeasurementMode.DISTANCE_TO_OBJECT -> getString(R.string.save)
            MeasurementMode.POINT -> if (measurementAnchors.isEmpty()) {
                getString(R.string.set_point)
            } else {
                getString(R.string.replace_point)
            }
            MeasurementMode.RULER, MeasurementMode.HEIGHT -> when {
                complete -> getString(R.string.new_measurement)
                measurementAnchors.isEmpty() -> getString(R.string.point_one)
                else -> getString(R.string.point_two)
            }
            MeasurementMode.AREA -> when {
                complete -> getString(R.string.new_measurement)
                else -> getString(
                    R.string.area_point_button,
                    (measurementAnchors.size + 1).toString(),
                    AREA_POINT_COUNT.toString()
                )
            }
        }
        unitButton.text = "${displayUnit.shortName}⌄"
        gridButton.text = if (gridEnabled) getString(R.string.grid_on) else getString(R.string.grid_off)
        gridButton.isSelected = gridEnabled
        undoButton.isEnabled = measurementAnchors.isNotEmpty()
        clearButton.isEnabled = measurementAnchors.isNotEmpty()
        modePoint.isSelected = measurementMode == MeasurementMode.POINT
        modeRuler.isSelected = measurementMode == MeasurementMode.RULER
        modeArea.isSelected = measurementMode == MeasurementMode.AREA
        modeHeight.isSelected = measurementMode == MeasurementMode.HEIGHT
        modeDistance.isSelected = measurementMode == MeasurementMode.DISTANCE_TO_OBJECT
        updateDynamicHint()
        updateActionAvailability()
    }

    private fun updateDynamicHint() {
        hintText.text = when (measurementMode) {
            MeasurementMode.DISTANCE_TO_OBJECT -> getString(R.string.hint_distance)
            MeasurementMode.POINT -> if (measurementAnchors.isEmpty()) {
                getString(R.string.hint_point)
            } else {
                getString(R.string.hint_point_fixed)
            }
            MeasurementMode.RULER -> measurementHint(
                first = R.string.hint_ruler_first,
                next = R.string.hint_ruler_second,
                complete = R.string.hint_complete
            )
            MeasurementMode.HEIGHT -> measurementHint(
                first = R.string.hint_height_first,
                next = R.string.hint_height_second,
                complete = R.string.hint_complete
            )
            MeasurementMode.AREA -> when {
                isMeasurementComplete() && latestResultEstimate?.stable == true -> getString(R.string.hint_complete)
                isMeasurementComplete() -> getString(R.string.hint_refining)
                else -> getString(
                    R.string.hint_area,
                    (measurementAnchors.size + 1).toString(),
                    AREA_POINT_COUNT.toString()
                )
            }
        }
    }

    private fun measurementHint(first: Int, next: Int, complete: Int): String = when {
        isMeasurementComplete() && latestResultEstimate?.stable == true -> getString(complete)
        isMeasurementComplete() -> getString(R.string.hint_refining)
        measurementAnchors.isEmpty() -> getString(first)
        else -> getString(next)
    }

    private fun updateActionAvailability() {
        val complete = isMeasurementComplete()
        val enabled = !thermalPaused && latestMotionEstimate.stable && when {
            complete && measurementMode != MeasurementMode.DISTANCE_TO_OBJECT -> true
            measurementMode == MeasurementMode.DISTANCE_TO_OBJECT ->
                latestTargetEstimate.stable && latestLiveEstimate?.stable == true
            else -> latestTargetEstimate.stable
        }
        setPointButton.isEnabled = enabled
        setPointButton.alpha = if (enabled) 1f else 0.62f
    }

    private fun expectedPointCount(): Int = when (measurementMode) {
        MeasurementMode.POINT -> 1
        MeasurementMode.RULER, MeasurementMode.HEIGHT -> 2
        MeasurementMode.AREA -> AREA_POINT_COUNT
        MeasurementMode.DISTANCE_TO_OBJECT -> 0
    }

    private fun isMeasurementComplete(): Boolean {
        val expected = expectedPointCount()
        return expected > 0 && measurementAnchors.size == expected
    }

    private fun resetResultFilter() {
        resultFilter = createResultFilter(measurementMode)
    }

    private fun createResultFilter(mode: MeasurementMode): RobustScalarFilter = when (mode) {
        MeasurementMode.AREA -> RobustScalarFilter(
            absoluteTolerance = 0.0008f,
            relativeTolerance = 0.012f
        )
        else -> RobustScalarFilter(
            absoluteTolerance = 0.004f,
            relativeTolerance = 0.0035f
        )
    }

    private fun showMainMenu() {
        val items = arrayOf(
            getString(R.string.how_to_measure),
            getString(R.string.history),
            getString(R.string.clear_current),
            getString(R.string.about_app)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showInstructions()
                    1 -> showHistory()
                    2 -> clearMeasurement()
                    3 -> showAbout()
                }
            }
            .show()
    }

    private fun showInstructions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.how_to_measure)
            .setMessage(R.string.instructions)
            .setPositiveButton(R.string.understood, null)
            .show()
    }

    private fun showAbout() {
        val depthText = if (depthSupported) getString(R.string.depth_enabled) else getString(R.string.depth_fallback)
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, depthText))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showHistory() {
        val records = measurementStore.load()
        if (records.isEmpty()) {
            toast(getString(R.string.history_empty))
            return
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val items = records.map { record ->
            val value = if (record.kind == MeasurementKind.AREA) {
                displayUnit.formatArea(record.value)
            } else {
                displayUnit.formatDistance(record.value)
            }
            "${record.kind.title}: $value  —  ${dateFormat.format(Date(record.timestamp))}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.measurement_history)
            .setItems(items, null)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.clear_history) { _, _ ->
                measurementStore.clear()
                toast(getString(R.string.history_cleared))
            }
            .show()
    }

    private fun saveScreenshot() {
        photoButton.isEnabled = false
        ScreenshotSaver.capture(this) { result ->
            photoButton.isEnabled = true
            result.onSuccess { fileName ->
                toast(getString(R.string.photo_saved, fileName))
            }.onFailure { error ->
                toast(error.localizedMessage ?: getString(R.string.photo_failed))
            }
        }
    }

    private fun showCameraPermissionDialog() {
        val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_permission_title)
            .setMessage(R.string.camera_permission_message)
            .setCancelable(false)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                if (permanentlyDenied) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .show()
    }

    private fun persistUiState() {
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(STATE_UNIT, displayUnit.name)
            .putString(STATE_MODE, measurementMode.name)
            .putBoolean(STATE_GRID, gridEnabled)
            .apply()
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val composeView = arComposeView
        (composeView?.parent as? ViewGroup)?.removeView(composeView)
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        if (composeView != null) arContainer.addView(composeView, 0)
        updateControls()
        latestFrame?.let(::updateFrameUi) ?: clearOverlay()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_UNIT, displayUnit.name)
        outState.putString(STATE_MODE, measurementMode.name)
        outState.putBoolean(STATE_GRID, gridEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        detachAllAnchors()
        if (thermalListenerRegistered) {
            powerManager.removeThermalStatusListener(thermalStatusListener)
            thermalListenerRegistered = false
        }
        arComposeView?.disposeComposition()
        arComposeView = null
        arSession = null
        latestFrame = null
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class ScreenSample(
        val x: Float,
        val y: Float,
        val offsetPixels: Float
    )

    private data class HitCandidate(
        val hit: HitResult,
        val source: HitSource,
        val distanceMeters: Float,
        val offsetPixels: Float
    )

    private data class SelectedHit(
        val hit: HitResult,
        val source: HitSource,
        val distanceMeters: Float,
        val depthConfidence: Float?
    )

    private data class DepthReading(
        val meters: Float,
        val confidence: Float,
        val capturedAtMillis: Long
    )

    companion object {
        private const val UI_PREFERENCES = "ar_roulette_ui"
        private const val STATE_UNIT = "display_unit"
        private const val STATE_MODE = "measurement_mode"
        private const val STATE_GRID = "grid_enabled"
        private const val AREA_POINT_COUNT = 4
        private const val STATUS_UPDATE_INTERVAL_MS = 500L
        private const val DEPTH_CACHE_LIFETIME_MS = 1_500L
        private const val DEPTH_SAMPLE_RADIUS = 2
        private const val THERMAL_HEADROOM_READ_INTERVAL_NANOS = 1_000_000_000L
        private const val TARGET_CAMERA_TEXTURE_PIXELS = 1280L * 720L
        private const val MIN_RAW_DEPTH_SAMPLES = 5
        private const val MIN_RAW_DEPTH_CONFIDENCE = 0.38f
        private const val EXCELLENT_DEPTH_CONFIDENCE = 0.62f
        private const val MIN_TARGET_DISTANCE_METERS = 0.18f
        private const val MAX_TARGET_DISTANCE_METERS = 8f
        private const val MIN_POINT_SEPARATION_METERS = 0.015f
        private const val MIN_MEASUREMENT_METERS = 0.015f
        private const val MIN_AREA_SQUARE_METERS = 0.0004f

        private fun emptyTargetEstimate() = TargetEstimate(
            sampleCount = 0,
            spreadMeters = Float.POSITIVE_INFINITY,
            allowedSpreadMeters = 0f,
            stable = false,
            source = null,
            depthConfidence = null,
            stabilizedPoint = null
        )

        private fun emptyMotionEstimate() = MotionEstimate(
            translationSpeedMetersPerSecond = 0f,
            angularSpeedDegreesPerSecond = 0f,
            stable = false,
            excessive = false
        )
    }
}
