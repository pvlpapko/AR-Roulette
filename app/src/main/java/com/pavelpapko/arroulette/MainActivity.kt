package com.pavelpapko.arroulette

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.Uri
import android.opengl.Matrix
import android.os.Bundle
import android.provider.Settings
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
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import java.text.DateFormat
import java.util.Date
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var arContainer: FrameLayout
    private lateinit var overlayView: MeasurementOverlayView
    private lateinit var resultText: TextView
    private lateinit var liveDistanceText: TextView
    private lateinit var statusText: TextView
    private lateinit var setPointButton: Button
    private lateinit var undoButton: Button
    private lateinit var clearButton: Button
    private lateinit var unitButton: Button
    private lateinit var historyButton: Button

    private var arComposeView: ComposeView? = null
    private var latestFrame: Frame? = null
    private var firstAnchor: Anchor? = null
    private var secondAnchor: Anchor? = null
    private var displayUnit = DisplayUnit.CENTIMETERS
    private var depthSupported = false
    private var lastStatusUpdateMillis = 0L

    private lateinit var measurementStore: MeasurementStore

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initializeArScene()
        } else {
            showCameraPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        measurementStore = MeasurementStore(this)
        bindActions()
        updateControls()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeArScene()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindViews() {
        arContainer = findViewById(R.id.ar_container)
        overlayView = findViewById(R.id.measurement_overlay)
        resultText = findViewById(R.id.result_text)
        liveDistanceText = findViewById(R.id.live_distance_text)
        statusText = findViewById(R.id.status_text)
        setPointButton = findViewById(R.id.set_point_button)
        undoButton = findViewById(R.id.undo_button)
        clearButton = findViewById(R.id.clear_button)
        unitButton = findViewById(R.id.unit_button)
        historyButton = findViewById(R.id.history_button)
    }

    private fun bindActions() {
        setPointButton.setOnClickListener { placePoint() }
        undoButton.setOnClickListener { undoLastPoint() }
        clearButton.setOnClickListener { clearMeasurement() }
        unitButton.setOnClickListener {
            displayUnit = displayUnit.next()
            updateControls()
            latestFrame?.let(::updateFrameUi)
        }
        historyButton.setOnClickListener { showHistory() }
    }

    private fun initializeArScene() {
        if (arComposeView != null || isFinishing || isDestroyed) return

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    planeRenderer = true,
                    sessionConfiguration = { session, config ->
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                        config.depthMode = if (depthSupported) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                    },
                    onSessionCreated = { session ->
                        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                        runOnUiThread { updateStatusText(TrackingState.PAUSED) }
                    },
                    onSessionUpdated = { _, frame ->
                        latestFrame = frame
                        updateFrameUi(frame)
                    },
                    onSessionFailed = { exception ->
                        runOnUiThread {
                            statusText.text = "ARCore недоступен"
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Не удалось запустить AR")
                                .setMessage(
                                    exception.localizedMessage
                                        ?: "Проверьте поддержку ARCore и наличие Google Play Services for AR."
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

    private fun placePoint() {
        val frame = latestFrame
        val sceneView = arComposeView
        if (frame == null || sceneView == null || frame.camera.trackingState != TrackingState.TRACKING) {
            toast("Подождите, пока камера определит пространство")
            return
        }

        val hit = findBestHit(frame, sceneView.width / 2f, sceneView.height / 2f)
        if (hit == null) {
            toast("Точка не определена. Наведитесь на поверхность и медленно переместите телефон")
            return
        }

        runCatching {
            when {
                firstAnchor == null -> firstAnchor = hit.createAnchor()
                secondAnchor == null -> {
                    secondAnchor = hit.createAnchor()
                    val distance = currentDistanceMeters()
                    if (distance != null && distance >= MIN_MEASUREMENT_METERS) {
                        measurementStore.add(MeasurementRecord(System.currentTimeMillis(), distance))
                    } else if (distance != null) {
                        secondAnchor?.detach()
                        secondAnchor = null
                        toast("Точки расположены слишком близко")
                    }
                }
                else -> {
                    detachAnchors()
                    firstAnchor = hit.createAnchor()
                }
            }
        }.onFailure {
            toast("Не удалось закрепить точку: ${it.localizedMessage ?: "ошибка AR"}")
        }

        updateControls()
        updateFrameUi(frame)
    }

    private fun findBestHit(frame: Frame, x: Float, y: Float): HitResult? {
        return frame.hitTest(x, y).firstOrNull { hit ->
            val trackable = hit.trackable
            when (trackable) {
                is DepthPoint -> trackable.trackingState == TrackingState.TRACKING
                is Plane -> trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(hit.hitPose)
                is Point -> trackable.trackingState == TrackingState.TRACKING &&
                    trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }
    }

    private fun updateFrameUi(frame: Frame) {
        val sceneView = arComposeView ?: return
        val camera = frame.camera
        val hit = if (camera.trackingState == TrackingState.TRACKING && sceneView.width > 0 && sceneView.height > 0) {
            findBestHit(frame, sceneView.width / 2f, sceneView.height / 2f)
        } else {
            null
        }

        val firstPoint = firstAnchor
            ?.takeIf { it.trackingState == TrackingState.TRACKING }
            ?.pose
            ?.let { projectToScreen(it, camera, sceneView.width, sceneView.height) }
        val secondPoint = secondAnchor
            ?.takeIf { it.trackingState == TrackingState.TRACKING }
            ?.pose
            ?.let { projectToScreen(it, camera, sceneView.width, sceneView.height) }
        val distance = currentDistanceMeters()
        val label = distance?.let(displayUnit::format)
        val liveDistance = hit?.let { distanceBetweenPoses(camera.pose, it.hitPose) }

        runOnUiThread {
            overlayView.update(firstPoint, secondPoint, label, hit != null)
            resultText.text = label ?: "—"
            liveDistanceText.text = when {
                camera.trackingState != TrackingState.TRACKING -> "Медленно перемещайте телефон для калибровки"
                liveDistance != null -> "До точки под прицелом: ${displayUnit.format(liveDistance)}"
                else -> "Наведите прицел на поверхность"
            }
            val now = System.currentTimeMillis()
            if (now - lastStatusUpdateMillis >= STATUS_UPDATE_INTERVAL_MS) {
                lastStatusUpdateMillis = now
                updateStatusText(camera.trackingState)
            }
        }
    }

    private fun projectToScreen(pose: Pose, camera: Camera, width: Int, height: Int): PointF? {
        if (width <= 0 || height <= 0) return null

        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        val viewProjectionMatrix = FloatArray(16)
        val worldPoint = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f)
        val clipPoint = FloatArray(4)

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.05f, 100f)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMV(clipPoint, 0, viewProjectionMatrix, 0, worldPoint, 0)

        if (clipPoint[3] <= 0f) return null
        val normalizedX = clipPoint[0] / clipPoint[3]
        val normalizedY = clipPoint[1] / clipPoint[3]
        return PointF(
            (normalizedX + 1f) * 0.5f * width,
            (1f - normalizedY) * 0.5f * height
        )
    }

    private fun currentDistanceMeters(): Float? {
        val first = firstAnchor?.pose ?: return null
        val second = secondAnchor?.pose ?: return null
        return MeasurementMath.distanceMeters(first.translation, second.translation)
    }

    private fun distanceBetweenPoses(first: Pose, second: Pose): Float {
        val dx = first.tx() - second.tx()
        val dy = first.ty() - second.ty()
        val dz = first.tz() - second.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun undoLastPoint() {
        when {
            secondAnchor != null -> {
                secondAnchor?.detach()
                secondAnchor = null
            }
            firstAnchor != null -> {
                firstAnchor?.detach()
                firstAnchor = null
            }
        }
        updateControls()
        latestFrame?.let(::updateFrameUi) ?: overlayView.update(null, null, null, false)
    }

    private fun clearMeasurement() {
        detachAnchors()
        updateControls()
        overlayView.update(null, null, null, false)
        resultText.text = "—"
    }

    private fun detachAnchors() {
        runCatching { firstAnchor?.detach() }
        runCatching { secondAnchor?.detach() }
        firstAnchor = null
        secondAnchor = null
    }

    private fun updateControls() {
        setPointButton.text = when {
            firstAnchor == null -> getString(R.string.point_one)
            secondAnchor == null -> getString(R.string.point_two)
            else -> getString(R.string.new_measurement)
        }
        undoButton.isEnabled = firstAnchor != null || secondAnchor != null
        clearButton.isEnabled = firstAnchor != null || secondAnchor != null
        unitButton.text = displayUnit.shortName
        currentDistanceMeters()?.let { resultText.text = displayUnit.format(it) }
    }

    private fun updateStatusText(trackingState: TrackingState) {
        val trackingLabel = when (trackingState) {
            TrackingState.TRACKING -> "слежение стабильно"
            TrackingState.PAUSED -> "идёт поиск поверхностей"
            TrackingState.STOPPED -> "слежение остановлено"
        }
        val depthLabel = if (depthSupported) "Depth API включён" else "Depth API нет, используются плоскости и точки"
        statusText.text = "$trackingLabel • $depthLabel"
    }

    private fun showHistory() {
        val records = measurementStore.load()
        if (records.isEmpty()) {
            toast("История пока пуста")
            return
        }

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val items = records.mapIndexed { index, record ->
            "${index + 1}. ${displayUnit.format(record.meters)}  —  ${dateFormat.format(Date(record.timestamp))}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("История измерений")
            .setItems(items, null)
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Очистить историю") { _, _ ->
                measurementStore.clear()
                toast("История очищена")
            }
            .show()
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        detachAnchors()
        arComposeView?.disposeComposition()
        arComposeView = null
        super.onDestroy()
    }

    companion object {
        private const val MIN_MEASUREMENT_METERS = 0.005f
        private const val STATUS_UPDATE_INTERVAL_MS = 350L
    }
}
