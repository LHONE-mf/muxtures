# S00 — Спасти ценное из старого репозитория (выполняется В СТАРОМ репозитории)

Тип: подготовка. Код старого приложения НЕ меняем. Ветка не нужна, работаем в рабочей папке, коммитов в старый репо не делаем.
Правила `00_RULES.md` действуют, кроме пунктов про `check_stage.sh` (его в старом репо нет): проверка по списку ниже.

## Предусловие
Ты находишься в корне старого репозитория (там лежит `app/build.gradle.kts`). Проверь: `ls app/build.gradle.kts`.

## Шаги
1. Создай папку `salvage/` в корне. Внутри создай перечисленные ниже файлы. Данные бери ИЗ КОДА командами (grep/cat), не по памяти.
2. `salvage/versions.md`: таблица «параметр → значение → файл-источник» для: distributionUrl Gradle, AGP, Kotlin, KSP, Compose compiler extension, Compose BOM, Room, Navigation Compose (если есть), Coroutines, Lifecycle, ZXing, Robolectric, JUnit, androidx.test, compileSdk, minSdk, targetSdk, jvmTarget, версия JDK, которым собирается проект (`java -version`).
3. Копии файлов сборки: `salvage/gradle/settings.gradle.kts.txt`, `root.build.gradle.kts.txt`, `app.build.gradle.kts.txt`, `gradle.properties.txt`, `libs.versions.toml.txt` (если есть).
4. `salvage/gradle-wrapper/`: копии `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.
5. `salvage/materials_seed.csv`: встроенный каталог материалов (≈40 строк). Заголовок строго:
   `name;category;grade;density_gcm3;d50_um;bet_m2g;moisture_ppm;notes`
   Категории только из: ACTIVE_CATHODE, ACTIVE_ANODE, CONDUCTIVE, BINDER, SOLVENT, ADDITIVE, DISPERSANT. UTF-8, разделитель `;`, десятичная точка, пустое поле если значения нет. Если в старом коде категория названа иначе, сопоставь и опиши соответствие в отчёте.
6. `salvage/regimes_seed.csv`: пресеты режимов смешивания. Заголовок: `name;stage_no;stage_type;rpm_disperser;rpm_blade;time_min;comment`.
7. `salvage/viscosity_models.kt.txt`: копия кода оценки вязкости по объёмной доле твёрдого (Мони, Марон–Пирис, Кригер–Догерти) и список параметров с единицами.
8. `salvage/transfer/`: копии `TaskTransfer.kt`, `TaskQr.kt` (как `.kt.txt`) и `task_transfer_sample.json` (реальный пример передаваемого задания; получи запуском старого кода в юнит-тесте, не меняя продуктовый код, либо опиши структуру словами в `transfer/FORMAT.md`).
9. Архивы: `git bundle create ../old-slurry.bundle --all` и `tar czf ../salvage.tgz salvage/`. Выведи `ls -l ../old-slurry.bundle ../salvage.tgz`.

## Запрос пользователю (задай в конце, по шаблону раздела 7 правил)
```
ЗАПРОС 1: Нужны ли записи из старого приложения
Что нужно:    ответ A или B
A) Да, в старом приложении есть реальные замесы, которые нужно перенести (рекомендую, если вы уже вносили настоящие данные).
B) Нет, там только тестовые данные.
Если A: в старом приложении вкладка «Журнал» → «Экспорт» → CSV, сохраните файл как old_journal.csv и положите в salvage/. Если экспорт недоступен, пришлите скриншоты 2–3 записей.
Если нет ответа: считаю B, этап S28 пропускаю.
Блокирует ли: нет
```

## Чек-лист
- [ ] versions.md содержит все 18 параметров, у каждого указан файл-источник
- [ ] 5 файлов в salvage/gradle/ и 4 файла в salvage/gradle-wrapper/ непустые
- [ ] materials_seed.csv: заголовок точный, число строк, первые 5 и последние 5 строк в отчёте
- [ ] regimes_seed.csv: число строк
- [ ] viscosity_models.kt.txt, transfer/* на месте
- [ ] old-slurry.bundle и salvage.tgz созданы (размеры в отчёте)
- [ ] `git status` старого репо не показывает изменений в `app/` (изменения только в `salvage/`)

## Отчёт
Выведи `find salvage -type f | xargs wc -l` и `ls -l ../old-slurry.bundle ../salvage.tgz`. Скажи пользователю: **скачайте `old-slurry.bundle` и `salvage.tgz` на свой компьютер ДО удаления старого репозитория.** Содержимое `salvage/` потом копируется в новый репозиторий в `docs/salvage/` на этапе S01.
