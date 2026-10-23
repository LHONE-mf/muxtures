# Версии библиотек и инструментов старого проекта SlurryCalc (S00)

Снято командами grep/cat с кода репозитория LHONE-mf/muxtures, коммит `c38d1eb` (ветка stage-1f), 2026-10-23.

| Параметр | Значение | Файл-источник |
|---|---|---|
| distributionUrl Gradle | https://services.gradle.org/distributions/gradle-8.7-bin.zip | gradle/wrapper/gradle-wrapper.properties |
| AGP (com.android.application) | 8.5.2 | build.gradle.kts (root) |
| Kotlin (org.jetbrains.kotlin.android) | 1.9.25 | build.gradle.kts (root) |
| KSP (com.google.devtools.ksp) | 1.9.25-1.0.20 | build.gradle.kts (root) |
| Compose compiler extension | 1.5.15 | app/build.gradle.kts (composeOptions) |
| Compose BOM | 2024.10.01 | app/build.gradle.kts (platform(...)) |
| Room | 2.6.1 (runtime + ksp-compiler + testing) | app/build.gradle.kts |
| Navigation Compose | ОТСУТСТВЕТ (этап 1c не завершён; навигация — табы в MainActivity) | app/build.gradle.kts (grep: нет зависимости) |
| Coroutines | Явно не объявлены (через lifecycle-viewmodel-compose / transitively androidx) | app/build.gradle.kts |
| Lifecycle | 2.8.6 (lifecycle-viewmodel-compose) | app/build.gradle.kts |
| ZXing | com.google.zxing:core:3.5.3 (только генерация QR) | app/build.gradle.kts |
| Robolectric | 4.11.1 (+ android-all-instrumented 11-robolectric-6757853-i4) | app/build.gradle.kts |
| JUnit | 4.13.2 (unit); org.json:json:20231013 для unit-тестов | app/build.gradle.kts |
| androidx.test | core:1.5.0; ext:junit:1.2.1; runner:1.6.1 | app/build.gradle.kts |
| androidx.activity | activity-compose:1.9.2 | app/build.gradle.kts |
| androidx.core | core-ktx:1.13.1 | app/build.gradle.kts |
| compileSdk | 34 | app/build.gradle.kts |
| minSdk | 26 | app/build.gradle.kts |
| targetSdk | 34 | app/build.gradle.kts |
| jvmTarget | 17 (kotlinOptions.jvmTarget; source/targetCompatibility VERSION_17) | app/build.gradle.kts |
| JDK сборки | openjdk 17.0.20 (2026-07-21) | вывод `java -version` в среде сборки |
| applicationId / namespace | com.slurrycalc (в НОВОМ проекте будет com.slurrycalc.lab) | app/build.gradle.kts |
| versionName / versionCode | 1.5.0 / 10 | app/build.gradle.kts |
| libs.versions.toml | ОТСУТСТВУЕТ — версии литералами в скриптах | gradle/libs.versions.toml (ls → no such file) |
| gradle.properties ключи | jvmargs=-Xmx768m; parallel=false; kotlin.compiler.execution.strategy=in-process; nonTransitiveRClass=true; daemon=false | gradle.properties |
