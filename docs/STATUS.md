# STATUS — 2026-10-22 (B2 завершён, ветка stage-1f)

## Состояние сборки
- `./gradlew --offline testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (проверено в этой сессии).
- Тесты: **57 unit-тестов, 0 failures, 0 errors** (по JUnit XML): MixingPhysicsTest 8, PinHashTest 6, RecoveryCodeTest 8, SpecTest51 5, SpecTest52 6, SpecTest54 3, SpecTest55 2, UnitsTest 4, VerifyChainTest 6, MigrationV2toV4RobolectricTest 4, LegacyJsonTest 5, RolePermissionsTest 4.
- APK собран локально (app/build/outputs/apk/debug/app-debug.apk). **Релиз/тег НЕ создавались** (раздел 8 — только по команде пользователя). В main не сливалось.

## Этап 1f / подшаги B
- **B0** ✅ проверки истории/remote; bcrypt отсутствует (PBKDF2); recovery-код 5×5 (~118–125 бит), попытки ограничены и пишутся в аудит.
- **B1** ✅ коммит e4a3d20: цепочка хэшей audit_log (prevHash/rowHash, genesis, verifyChain), схема v4 изменена на месте (APK со схемой v3 не устанавливался), миграция V2ToV4, тесты VerifyChainTest(6)+RecoveryCodeTest(8). Запушено (origin/stage-1f=e4a3d20).
- **B2** ✅ ЭТОТ коммит: Robolectric + room-testing; MigrationV2toV4RobolectricTest (4 теста): создание БД по DDL схемы v2, миграция V2ToV4, сверка колонок всех таблиц с экспортированной 4.json, проверка данных-образцов и JSON-бэкапа перед миграцией. Исправлены ошибки компиляции sorted()/forEach (ambiguity) → sortedWith(compareBy(...)) + joinToString.
- Инструментальные тесты на эмуляторе — НЕ ЗАПУСКАЛОСЬ (нет эмулятора в песочнице); заменены Robolectric-покрытием B2.

## Остаток этапа 1f
- UI интеграция логина/автовыход по бездействию — этап C (по решению пользователя).
- Цепочка хэшей: функция+тесты есть; отображение проверки целостности в экране «Аудит» — этап C.

## Прочее
- 1a ✅ d5d44f0 · 1b ✅ (711eb68, c142c05) · 1d ✅ 8c1e8f0 · 1e ✅ (188b68f, f5bcfe3, 71122e8)
- 1c ❌ Navigation Compose не выполнен (монолит MainActivity ~1830 строк) — следующий блок работ (шаг B раздела 10) после B0–B2.
- 1g ⛔ не начат
