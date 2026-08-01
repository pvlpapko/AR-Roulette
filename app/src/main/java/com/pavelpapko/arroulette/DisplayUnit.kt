package com.pavelpapko.arroulette

import java.util.Locale

enum class DisplayUnit(val shortName: String) {
    MILLIMETERS("мм"),
    CENTIMETERS("см"),
    METERS("м"),
    INCHES("дюйм");

    fun next(): DisplayUnit = entries[(ordinal + 1) % entries.size]

    fun format(meters: Float): String = formatDistance(meters)

    fun formatDistance(meters: Float): String = when (this) {
        MILLIMETERS -> String.format(Locale.getDefault(), "%.0f мм", meters * 1000f)
        CENTIMETERS -> String.format(Locale.getDefault(), "%.1f см", meters * 100f)
        METERS -> String.format(Locale.getDefault(), "%.3f м", meters)
        INCHES -> String.format(Locale.getDefault(), "%.2f дюйм", meters / METERS_PER_INCH)
    }

    fun formatArea(squareMeters: Float): String = when (this) {
        MILLIMETERS -> String.format(Locale.getDefault(), "%.0f мм²", squareMeters * 1_000_000f)
        CENTIMETERS -> String.format(Locale.getDefault(), "%.1f см²", squareMeters * 10_000f)
        METERS -> String.format(Locale.getDefault(), "%.3f м²", squareMeters)
        INCHES -> String.format(
            Locale.getDefault(),
            "%.2f дюйм²",
            squareMeters / (METERS_PER_INCH * METERS_PER_INCH)
        )
    }

    companion object {
        private const val METERS_PER_INCH = 0.0254f
    }
}
