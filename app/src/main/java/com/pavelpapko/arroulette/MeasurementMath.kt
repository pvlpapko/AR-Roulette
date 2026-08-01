package com.pavelpapko.arroulette

import kotlin.math.sqrt

object MeasurementMath {
    fun distanceMeters(first: FloatArray, second: FloatArray): Float {
        require(first.size >= 3 && second.size >= 3) { "Coordinates must contain x, y and z" }
        val dx = first[0] - second[0]
        val dy = first[1] - second[1]
        val dz = first[2] - second[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
