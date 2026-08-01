# Changelog

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
