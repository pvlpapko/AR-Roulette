package com.pavelpapko.arroulette

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

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
    val depthConfidence: Float?
)

data class ScalarEstimate(
    val value: Float,
    val spread: Float,
    val sampleCount: Int,
    val stable: Boolean
)

class TargetStabilizer(
    private val maxSamples: Int = 18,
    private val minimumStableSamples: Int = 8
) {
    private data class Sample(
        val point: FloatArray,
        val source: HitSource,
        val depthConfidence: Float?
    )

    private val samples = ArrayDeque<Sample>()

    fun add(
        point: FloatArray,
        source: HitSource,
        depthConfidence: Float?,
        distanceFromCameraMeters: Float
    ) {
        if (samples.isNotEmpty()) {
            val center = MeasurementMath.medianPoint(samples.map { it.point })
            val jump = MeasurementMath.distanceMeters(center, point)
            val jumpLimit = max(0.10f, distanceFromCameraMeters * 0.06f)
            if (jump > jumpLimit) samples.clear()
        }

        samples.addLast(Sample(point.copyOf(3), source, depthConfidence))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    fun miss() {
        repeat(minOf(2, samples.size)) { samples.removeFirst() }
    }

    fun reset() {
        samples.clear()
    }

    fun estimate(distanceFromCameraMeters: Float): TargetEstimate {
        if (samples.isEmpty()) {
            return TargetEstimate(0, Float.POSITIVE_INFINITY, 0f, false, null, null)
        }

        val pointList = samples.map { it.point }
        val center = MeasurementMath.medianPoint(pointList)
        val spread = MeasurementMath.median(
            pointList.map { MeasurementMath.distanceMeters(it, center) }
        )
        val allowedSpread = 0.006f + distanceFromCameraMeters.coerceAtMost(8f) * 0.0025f
        val depthConfidenceValues = samples.mapNotNull { it.depthConfidence }
        val source = samples
            .groupingBy { it.source }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<HitSource, Int>> { it.value }.thenBy { sourcePriority(it.key) })
            ?.key

        return TargetEstimate(
            sampleCount = samples.size,
            spreadMeters = spread,
            allowedSpreadMeters = allowedSpread,
            stable = samples.size >= minimumStableSamples && spread <= allowedSpread,
            source = source,
            depthConfidence = depthConfidenceValues.takeIf { it.isNotEmpty() }?.let(MeasurementMath::median)
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
