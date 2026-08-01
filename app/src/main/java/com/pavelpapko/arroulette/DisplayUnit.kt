package com.pavelpapko.arroulette

import java.util.Locale

@Suppress("MagicNumber")
enum class DisplayUnit(val shortName: String) {
    MILLIMETERS("мм"),
    CENTIMETERS("см"),
    METERS("м"),
    INCHES("дюйм");

    fun next(): DisplayUnit = entries[(ordinal + 1) % entries.size]

    fun format(meters: Float): String = when (this) {
        MILLIMETERS -> String.format(Locale.getDefault(), "%.0f мм", meters * 1000f)
        CENTIMETERS -> String.format(Locale.getDefault(), "%.1f см", meters * 100f)
        METERS -> String.format(Locale.getDefault(), "%.3f м", meters)
        INCHES -> String.format(Locale.getDefault(), "%.2f дюйм", meters / 0.0254f)
    }
}
