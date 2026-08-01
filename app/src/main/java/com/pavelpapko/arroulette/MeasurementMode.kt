package com.pavelpapko.arroulette

enum class MeasurementMode {
    POINT,
    RULER,
    AREA,
    HEIGHT,
    DISTANCE_TO_OBJECT
}

enum class MeasurementKind(val title: String) {
    DISTANCE("Расстояние"),
    HEIGHT("Высота"),
    AREA("Площадь"),
    OBJECT_DISTANCE("До объекта")
}
