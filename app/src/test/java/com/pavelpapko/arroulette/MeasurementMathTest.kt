package com.pavelpapko.arroulette

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementMathTest {
    @Test
    fun calculatesThreeDimensionalDistanceInMeters() {
        val first = floatArrayOf(0f, 0f, 0f)
        val second = floatArrayOf(1f, 2f, 2f)
        assertEquals(3f, MeasurementMath.distanceMeters(first, second), 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCoordinateArrays() {
        MeasurementMath.distanceMeters(floatArrayOf(0f), floatArrayOf(0f, 0f, 0f))
    }
}
