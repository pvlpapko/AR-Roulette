package com.pavelpapko.arroulette

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/** Source used for the current AR hit. */
enum class HitSource {
    DEPTH,
    PLANE,
    FEATURE_POINT
}

data class TargetEstimate(
    val sampleCount: Int,
    val spreadMeters: Float,
    val allowedSpreadMeters: Float,
    val stable: Boolean,
    val source: HitSource?,
    val depthConfidence: Float?,
    val stabilizedPoint: FloatArray?
)

data class ScalarEstimate(
    val value: Float,
    val spread: Float,
    val sampleCount: Int,
    val stable: Boolean
)

data class MotionEstimate(
    val translationSpeedMetersPerSecond: Float,
    val angularSpeedDegreesPerSecond: Float,
    val stable: Boolean,
    val excessive: Boolean
)

/**
 * Detects whether the phone is sufficiently still for placing a measurement point.
 *
 * The gate combines translation and quaternion rotation speed, smooths both with an EMA and
 * requires a continuous steady interval. This prevents a single calm frame from unlocking the
 * point button while the user's hand is still settling.
 */
class MotionStabilityGate(
    private val requiredSteadyNanos: Long = 400_000_000L,
    private val stableTranslationSpeed: Float = 0.10f,
    private val stableAngularSpeedDegrees: Float = 12f,
    private val excessiveTranslationSpeed: Float = 0.35f,
    private val excessiveAngularSpeedDegrees: Float = 45f,
    private val smoothingFactor: Float = 0.24f
) {
    private var previousPosition: FloatArray? = null
    private var previousRotation: FloatArray? = null
    private var previousTimestampNanos: Long = 0L
    private var steadySinceNanos: Long = 0L
    private var smoothedTranslationSpeed = 0f
    private var smoothedAngularSpeed = 0f
    private var lastEstimate = MotionEstimate(0f, 0f, stable = false, excessive = false)

    fun add(
        position: FloatArray,
        rotationQuaternion: FloatArray,
        timestampNanos: Long
    ): MotionEstimate {
        require(position.size >= 3) { "Position must contain x, y and z" }
        require(rotationQuaternion.size >= 4) { "Quaternion must contain x, y, z and w" }

        val previousPositionValue = previousPosition
        val previousRotationValue = previousRotation
        val previousTime = previousTimestampNanos
        previousPosition = position.copyOf(3)
        previousRotation = rotationQuaternion.copyOf(4)
        previousTimestampNanos = timestampNanos

        if (previousPositionValue == null || previousRotationValue == null || previousTime == 0L) {
            steadySinceNanos = 0L
            lastEstimate = MotionEstimate(0f, 0f, stable = false, excessive = false)
            return lastEstimate
        }

        val elapsedNanos = timestampNanos - previousTime
        if (elapsedNanos <= 0L || elapsedNanos > MAX_VALID_SAMPLE_GAP_NANOS) {
            steadySinceNanos = 0L
            smoothedTranslationSpeed = 0f
            smoothedAngularSpeed = 0f
            lastEstimate = MotionEstimate(0f, 0f, stable = false, excessive = false)
            return lastEstimate
        }

        val elapsedSeconds = elapsedNanos / 1_000_000_000f
        val rawTranslationSpeed = MeasurementMath.distanceMeters(
            previousPositionValue,
            position
        ) / elapsedSeconds
        val rawAngularSpeed = quaternionAngleDegrees(
            previousRotationValue,
            rotationQuaternion
        ) / elapsedSeconds

        smoothedTranslationSpeed = ema(smoothedTranslationSpeed, rawTranslationSpeed)
        smoothedAngularSpeed = ema(smoothedAngularSpeed, rawAngularSpeed)

        val currentlySteady =
            smoothedTranslationSpeed <= stableTranslationSpeed &&
                smoothedAngularSpeed <= stableAngularSpeedDegrees
        if (currentlySteady) {
            if (steadySinceNanos == 0L) steadySinceNanos = timestampNanos
        } else {
            steadySinceNanos = 0L
        }

        val stable = steadySinceNanos != 0L &&
            timestampNanos - steadySinceNanos >= requiredSteadyNanos
        val excessive =
            smoothedTranslationSpeed >= excessiveTranslationSpeed ||
                smoothedAngularSpeed >= excessiveAngularSpeedDegrees

        lastEstimate = MotionEstimate(
            translationSpeedMetersPerSecond = smoothedTranslationSpeed,
            angularSpeedDegreesPerSecond = smoothedAngularSpeed,
            stable = stable,
            excessive = excessive
        )
        return lastEstimate
    }

    fun current(): MotionEstimate = lastEstimate

    fun reset() {
        previousPosition = null
        previousRotation = null
        previousTimestampNanos = 0L
        steadySinceNanos = 0L
        smoothedTranslationSpeed = 0f
        smoothedAngularSpeed = 0f
        lastEstimate = MotionEstimate(0f, 0f, stable = false, excessive = false)
    }

    private fun ema(previous: Float, current: Float): Float {
        if (previous == 0f) return current
        return previous + smoothingFactor * (current - previous)
    }

    private fun quaternionAngleDegrees(first: FloatArray, second: FloatArray): Float {
        val firstNorm = sqrt(
            first[0] * first[0] + first[1] * first[1] +
                first[2] * first[2] + first[3] * first[3]
        )
        val secondNorm = sqrt(
            second[0] * second[0] + second[1] * second[1] +
                second[2] * second[2] + second[3] * second[3]
        )
        if (firstNorm <= 0f || secondNorm <= 0f) return 0f
        val dot = abs(
            first[0] * second[0] +
                first[1] * second[1] +
                first[2] * second[2] +
                first[3] * second[3]
        ) / (firstNorm * secondNorm)
        val halfAngle = acos(dot.coerceIn(0f, 1f).toDouble())
        return Math.toDegrees(2.0 * halfAngle).toFloat()
    }

    companion object {
        private const val MAX_VALID_SAMPLE_GAP_NANOS = 500_000_000L
    }
}

/**
 * Robustly stabilizes the hit point. It uses a median cluster and keeps a locked point through
 * small hand tremors using hysteresis, while large target jumps reset the window immediately.
 */
class TargetStabilizer(
    private val maxSamples: Int = 16,
    private val minimumStableSamples: Int = 7
) {
    private data class Sample(
        val point: FloatArray,
        val source: HitSource,
        val depthConfidence: Float?
    )

    private val samples = ArrayDeque<Sample>()
    private var lockedPoint: FloatArray? = null

    fun add(
        point: FloatArray,
        source: HitSource,
        depthConfidence: Float?,
        distanceFromCameraMeters: Float
    ) {
        require(point.size >= 3) { "Point must contain x, y and z" }

        val reference = lockedPoint ?: samples.takeIf { it.isNotEmpty() }
            ?.let { stored -> MeasurementMath.medianPoint(stored.map { sample -> sample.point }) }
        if (reference != null) {
            val jump = MeasurementMath.distanceMeters(reference, point)
            val jumpLimit = if (lockedPoint != null) {
                max(0.035f, distanceFromCameraMeters * 0.025f)
            } else {
                max(0.075f, distanceFromCameraMeters * 0.05f)
            }
            if (jump > jumpLimit) reset()
        }

        samples.addLast(Sample(point.copyOf(3), source, depthConfidence))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    /** Keep the current lock while the hand is moving; no stale sample is added. */
    fun hold() = Unit

    fun miss() {
        if (samples.isNotEmpty()) samples.removeFirst()
        if (samples.isEmpty()) lockedPoint = null
    }

    fun reset() {
        samples.clear()
        lockedPoint = null
    }

    fun estimate(distanceFromCameraMeters: Float): TargetEstimate {
        if (samples.isEmpty()) {
            return TargetEstimate(
                sampleCount = 0,
                spreadMeters = Float.POSITIVE_INFINITY,
                allowedSpreadMeters = 0f,
                stable = false,
                source = null,
                depthConfidence = null,
                stabilizedPoint = null
            )
        }

        val pointList = samples.map { it.point }
        val center = MeasurementMath.medianPoint(pointList)
        val spread = MeasurementMath.median(
            pointList.map { MeasurementMath.distanceMeters(it, center) }
        )
        val allowedSpread = 0.0045f + distanceFromCameraMeters.coerceAtMost(8f) * 0.0018f
        val depthConfidenceValues = samples.mapNotNull { it.depthConfidence }
        val source = samples
            .groupingBy { it.source }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<HitSource, Int>> { it.value }.thenBy { sourcePriority(it.key) })
            ?.key

        val newlyStable = samples.size >= minimumStableSamples && spread <= allowedSpread
        val existingLock = lockedPoint
        val retainedStable = existingLock != null &&
            samples.size >= minimumStableSamples / 2 &&
            spread <= allowedSpread * 2.0f &&
            MeasurementMath.distanceMeters(existingLock, center) <= allowedSpread * 1.8f
        val stable = newlyStable || retainedStable

        if (newlyStable) {
            lockedPoint = if (existingLock == null) {
                center.copyOf(3)
            } else {
                floatArrayOf(
                    existingLock[0] * 0.75f + center[0] * 0.25f,
                    existingLock[1] * 0.75f + center[1] * 0.25f,
                    existingLock[2] * 0.75f + center[2] * 0.25f
                )
            }
        }

        return TargetEstimate(
            sampleCount = samples.size,
            spreadMeters = spread,
            allowedSpreadMeters = allowedSpread,
            stable = stable,
            source = source,
            depthConfidence = depthConfidenceValues.takeIf { it.isNotEmpty() }?.let(MeasurementMath::median),
            stabilizedPoint = lockedPoint?.copyOf(3) ?: center.copyOf(3)
        )
    }

    private fun sourcePriority(source: HitSource): Int = when (source) {
        HitSource.DEPTH -> 3
        HitSource.PLANE -> 2
        HitSource.FEATURE_POINT -> 1
    }
}

class RobustScalarFilter(
    private val maxSamples: Int = 30,
    private val minimumStableSamples: Int = 10,
    private val absoluteTolerance: Float,
    private val relativeTolerance: Float
) {
    private val samples = ArrayDeque<Float>()

    fun add(value: Float) {
        if (!value.isFinite() || value <= 0f) return
        samples.addLast(value)
        while (samples.size > maxSamples) samples.removeFirst()
    }

    fun reset() {
        samples.clear()
    }

    fun estimate(): ScalarEstimate? {
        if (samples.isEmpty()) return null
        val values = samples.toList()
        val median = MeasurementMath.median(values)
        val deviations = values.map { abs(it - median) }
        val mad = MeasurementMath.median(deviations)
        val outlierLimit = max(absoluteTolerance * 2f, mad * 3.5f)
        val filtered = values.filter { abs(it - median) <= outlierLimit }.ifEmpty { values }
        val robustValue = MeasurementMath.median(filtered)
        val robustSpread = 1.4826f * MeasurementMath.median(filtered.map { abs(it - robustValue) })
        val stableLimit = max(absoluteTolerance, abs(robustValue) * relativeTolerance)

        return ScalarEstimate(
            value = robustValue,
            spread = robustSpread,
            sampleCount = values.size,
            stable = values.size >= minimumStableSamples && robustSpread <= stableLimit
        )
    }
}
