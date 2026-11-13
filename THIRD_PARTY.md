# Сторонние компоненты / Third-party notices

## Библиотеки (подключаются Gradle, в репозитории не лежат)

| Компонент | Версия | Лицензия | Ссылка |
|---|---|---|---|
| Android Gradle Plugin | 9.4.1 | Apache-2.0 | https://developer.android.com/build |
| Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar`) | 9.8.2 | Apache-2.0 | https://gradle.org |
| Kotlin, Compose compiler plugin | 2.4.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Jetpack Compose (BOM 2026.09.00: ui, material3, tooling) | 2026.09.00 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX CameraX (core, camera2, lifecycle, video, compose) | 1.6.2 | Apache-2.0 | https://developer.android.com/media/camera/camerax |
| AndroidX Media3 (exoplayer, transformer, effect, ui) | 1.11.1 | Apache-2.0 | https://github.com/androidx/media |

## Заимствованные идеи и алгоритмы (код не копировался)

| Источник | Лицензия | Что взято |
|---|---|---|
| [ntsc-rs](https://github.com/ntsc-rs/ntsc-rs) (valadaptive) | MIT / ISC / Apache-2.0 (кроме `crates/gui`) | Последовательность стадий симуляции VHS/NTSC (YIQ, lowpass яркости, размытие и сдвиг цвета, звон, шум ленты, головки, трекинг, дропауты) и порядок величин параметров. Переписано заново на GLSL (`app/src/main/assets/shaders/vhs_*.frag`), исходный код Rust не переносился. |
| [glitch-core](https://github.com/samplemaple/glitch-core) | MIT | Просматривался как референс глитч-эффектов; совпадений кода нет (проверено по именам функций). |
| [android/camera-samples](https://github.com/android/camera-samples), [androidx/media](https://github.com/androidx/media), [CameraRecorder-android](https://github.com/MasayukiSuda/CameraRecorder-android) (MIT) | Apache-2.0 / MIT | Изучались как примеры работы CameraX, Media3 и записи с GL-эффектом; код не копировался. |

## Ресурсы

- **Иконки интерфейса и значок приложения** (`app/src/main/res/drawable/*.xml`) — векторные, нарисованы для проекта.
- **Логотип автора wmn** (`docs/wmn_logo.png`, `app/src/main/res/drawable-nodpi/wmn_logo.png`) — принадлежит автору
  проекта; используется только в блоке Credits и в README.
- **Шрифты** — отдельных файлов шрифтов нет: сегментные цифры штампа даты рисуются кодом, остальное — системные шрифты Android.
- **Тестовая картинка справки** (`ui/TestImage.kt`) — генерируется кодом.
- **Референсы:** изображения, по которым подбирался вид эффектов, в репозиторий не входят. Сторонних текстур и
  изображений в приложении нет.

## Торговые марки

Названия плёнок и форматов, упомянутые в пресетах и справке (если остались), — торговые марки их владельцев и
используются только для описания характера вида; проект не связан с ними.
