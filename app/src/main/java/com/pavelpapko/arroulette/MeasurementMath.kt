package com.pavelpapko.arroulette

import kotlin.math.abs
import kotlin.math.sqrt

object MeasurementMath {
    fun distanceMeters(first: FloatArray, second: FloatArray): Float {
        requirePoint(first)
        requirePoint(second)
        val dx = first[0] - second[0]
        val dy = first[1] - second[1]
        val dz = first[2] - second[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun heightMeters(first: FloatArray, second: FloatArray): Float {
        requirePoint(first)
        requirePoint(second)
        return abs(first[1] - second[1])
    }

    /**
     * Calculates a 3D polygon area by triangulating it from the first vertex.
     * This is robust for the slightly non-planar quadrilaterals commonly produced by AR hit tests.
     */
    fun polygonAreaSquareMeters(points: List<FloatArray>): Float {
        require(points.size >= 3) { "A polygon must contain at least three points" }
        points.forEach(::requirePoint)
        val origin = points.first()
        var area = 0f
        for (index in 1 until points.lastIndex) {
            area += triangleArea(origin, points[index], points[index + 1])
        }
        return area
    }

    fun median(values: List<Float>): Float {
        require(values.isNotEmpty()) { "Cannot calculate a median of an empty list" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    fun medianPoint(points: List<FloatArray>): FloatArray {
        require(points.isNotEmpty()) { "Cannot calculate a median point of an empty list" }
        points.forEach(::requirePoint)
        return floatArrayOf(
            median(points.map { it[0] }),
            median(points.map { it[1] }),
            median(points.map { it[2] })
        )
    }

    private fun triangleArea(first: FloatArray, second: FloatArray, third: FloatArray): Float {
        val abX = second[0] - first[0]
        val abY = second[1] - first[1]
        val abZ = second[2] - first[2]
        val acX = third[0] - first[0]
        val acY = third[1] - first[1]
        val acZ = third[2] - first[2]

        val crossX = abY * acZ - abZ * acY
        val crossY = abZ * acX - abX * acZ
        val crossZ = abX * acY - abY * acX
        return 0.5f * sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
    }

    private fun requirePoint(point: FloatArray) {
        require(point.size >= 3) { "Coordinates must contain x, y and z" }
    }
}
