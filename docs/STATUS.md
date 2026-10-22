# STATUS — 2026-10-22 (обновлено в ходе шага A, ветка stage-1f)

## Состояние сборки
- `./gradlew --offline testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (зелёная сборка).
- APK собран локально: app/build/outputs/apk/debug/app-debug.apk. **Релиз/тег НЕ создавались** (по правилу раздела 8 — только по команде пользователя).

## Этап 1f, шаг A — что входит в коммит
- data/Repositories.kt: проверка прав в репозиториях (CREATE_BATCH / EDIT_ACTUALS / SIGN_OFF / MANAGE_MATERIALS / MANAGE_REGIMES), отказ пишется в аудит DENIED.
- ui/SlurryViewModel.kt: addUser/changePin/resetPin/deactivateUser, nullable-результаты репозиториев.
- domain/Roles.kt, calc/PinHash.kt, data/AuthRepository.kt: роли+матрица прав, PBKDF2-PIN, блокировка 5 попыток/60 с (счётчик в таблице users БД).
- data/RoomDb.kt: v3 + миграция V2ToV3 (таблица users, индексы); ИСПРАВЛЕНО: ошибочная аннотация @Migration на классе миграции ломала KSP ([MissingType]) и @Database «переехал» на неё — аннотация возвращена на AppDatabase, миграция переведена в val-объект и регистрируется через Builder.addMigrations(V2ToV3).
- ui/AuthScreens.kt (LoginScreen/UserMenuSheet/AdminScreen/AuditScreen) — экраны шага C: отдельный коммит 8aa2589 (WIP, компилируются, в MainActivity НЕ подключены); интеграция — этап C.
- SectionCard/ChipFlow в MainActivity: private → internal (для повторного использования AuthScreens).

## Остаток этапа 1f (не выполнено)
- Тесты PinHash/RolePermissions/Guard (A2), тесты счётчика блокировки и recovery-кода админа (A3), обязательная причина при update журнала + счётчик сбоев Audit.record + MigrationTest 2→3 instrumented (A4).
- Integration логина в MainActivity и автовыход по бездействию — этап C (по решению пользователя).

## Прочее
- 1a ✅ d5d44f0 · 1b ✅ (711eb68, c142c05) · 1d ✅ 8c1e8f0 · 1e ✅ (188b68f, f5bcfe3, 71122e8)
- 1c ❌ Navigation Compose не выполнен (монолит MainActivity ~1830 строк)
- 1g ⛔ не начат
