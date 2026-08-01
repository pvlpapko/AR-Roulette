# Changelog

## 1.0.4 — 2026-08-01

- Исправлена ошибка Android Lint `PermissionImpliesUnsupportedChromeOsHardware`.
- Явно объявлена обычная камера как необязательная для фильтрации Google Play/ChromeOS (`android.hardware.camera`, `required=false`).
- AR-камера по-прежнему обязательна через `android.hardware.camera.ar`, поэтому приложение устанавливается только на подходящие ARCore-устройства.
- Версия приложения повышена до 1.0.4 (`versionCode` 5).

## 1.0.3 — 2026-08-01

- Исправлена ошибка Android Lint `NewApi` для Android 10–14: `MutableList.removeLast()` заменён на совместимый `removeAt(lastIndex)`.
- GitHub Actions теперь сохраняет HTML и текстовый отчёты lint как артефакт даже при неуспешной проверке.
- Версия приложения повышена до 1.0.3 (`versionCode` 4).

## 1.0.2 — 2026-08-01

- Исправлен конфликт слияния AndroidManifest между приложением и SceneView: значение `com.google.ar.core=required` теперь явно имеет приоритет через `tools:replace="android:value"`.
- В workflow GitHub Actions Gradle обновлён с 8.13 до 8.14.4.
- Версия приложения повышена до 1.0.2 (`versionCode` 3).

## 1.0.1 — 2026-08-01

- Исправлена сборка с Kotlin 2.3.20: устаревший `android.kotlinOptions.jvmTarget` заменён на `kotlin.compilerOptions`.
- JVM target сохранён на Java 17.
- Kotlin обновлён до 2.4.10 в соответствии с зависимостями SceneView 4.25.0.
- `ARSceneView` переведён на актуальный Compose API и встроен в существующий XML-интерфейс через `ComposeView`.

## 1.0.0 — 2026-08-01

- Первый рабочий проект AR-рулетки.
- Реальные измерения по двум ARCore-якорям.
- Поддержка Depth API с автоматическим fallback.
- Экранная линия, центральный прицел и live-расстояние до поверхности.
- История измерений и переключение единиц.
- GitHub Actions для APK/AAB, тестов, lint и release-подписи.
