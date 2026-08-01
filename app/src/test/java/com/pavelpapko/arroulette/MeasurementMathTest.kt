package com.pavelpapko.arroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementMathTest {
    @Test
    fun calculatesThreeDimensionalDistanceInMeters() {
        val first = floatArrayOf(0f, 0f, 0f)
        val second = floatArrayOf(1f, 2f, 2f)
        assertEquals(3f, MeasurementMath.distanceMeters(first, second), 0.0001f)
    }

    @Test
    fun calculatesVerticalHeightIndependentlyOfHorizontalOffset() {
        val first = floatArrayOf(0f, 0.25f, 0f)
        val second = floatArrayOf(0.4f, 2.25f, 0.7f)
        assertEquals(2f, MeasurementMath.heightMeters(first, second), 0.0001f)
    }

    @Test
    fun calculatesRectangleAreaInThreeDimensions() {
        val points = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(2f, 0f, 0f),
            floatArrayOf(2f, 0f, 3f),
            floatArrayOf(0f, 0f, 3f)
        )
        assertEquals(6f, MeasurementMath.polygonAreaSquareMeters(points), 0.0001f)
    }

    @Test
    fun robustFilterRejectsSingleLargeOutlier() {
        val filter = RobustScalarFilter(
            minimumStableSamples = 5,
            absoluteTolerance = 0.01f,
            relativeTolerance = 0.01f
        )
        listOf(1.001f, 1.002f, 0.999f, 1.0f, 5f, 1.001f).forEach(filter::add)
        val estimate = requireNotNull(filter.estimate())
        assertEquals(1.001f, estimate.value, 0.003f)
        assertTrue(estimate.stable)
    }

    @Test
    fun targetStabilizerRequiresSeveralConsistentSamples() {
        val stabilizer = TargetStabilizer(minimumStableSamples = 5)
        repeat(4) { index ->
            stabilizer.add(
                point = floatArrayOf(index * 0.0005f, 0f, -1f),
                source = HitSource.DEPTH,
                depthConfidence = 0.8f,
                distanceFromCameraMeters = 1f
            )
        }
        assertFalse(stabilizer.estimate(1f).stable)
        stabilizer.add(floatArrayOf(0.001f, 0f, -1f), HitSource.DEPTH, 0.8f, 1f)
        assertTrue(stabilizer.estimate(1f).stable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCoordinateArrays() {
        MeasurementMath.distanceMeters(floatArrayOf(0f), floatArrayOf(0f, 0f, 0f))
    }
}
