# Формат передачи заданий «slurry-task» v1 (снято с кода TaskTransfer.kt, коммит c38d1eb)

Пример реального задания: `task_transfer_sample.json` (структура 1:1 из toJsonCompact;
числа масс соответствуют расчёту эталонного катода NMC622 2 кг / СО 50 % — проверено round-trip тестом в старом проекте).

## Каналы доставки (по убыванию удобства)
1. **QR-код на экране** — исполнитель сканирует камерой. Без интернета/Bluetooth. Надёжно до ~900 байт base64url, крупный QR до ~1500 байт (границы констант QR_RELIABLE_B64 / QR_MAX_B64).
2. **Системный «Поделиться»** (Telegram/WhatsApp/Email/Bluetooth/Quick Share): файл `.slurrytask.json` или текст. На телефоне исполнителя файл открывается тем же приложением (intent-filters манифеста: scheme `slurrycalc`, MIME application/json, расширение .slurrytask.json, ACTION_SEND).
3. **Deep-link**: `slurrycalc://task?d=<base64url>` (и https-вариант `https://slurrycalc.local/task#d=...`).

## Компактный JSON (для QR/ссылки), ASCII + \uXXXX для кириллицы
| Ключ | Тип | Значение |
|---|---|---|
| f | string | всегда `"slurry-task"` (магическое имя формата) |
| v | int | версия формата = 1 |
| t | string | название замеса |
| ts | long | Unix-время создания, мс |
| dc | double | целевой сухой остаток, % |
| wm | double | плановая масса мокрой суспензии, г |
| c | массив | компоненты сухого: `[имя, ρ истинная г/см³, плановая масса г]` |
| s | массив | растворитель: `[имя, ρ г/см³, плановая масса г]` |
| r | string | id встроенного режима смешивания (planetary_std и т. п.) |
| dur | double | общая длительность перемешивания, мин |
| notes | string | примечания к режиму |
| steps | массив этапов | каждый: `[action, tool, dispr, blader, dur, temp, vac, cmt]` — см. ниже |

Этап (steps[i]):
- action — что делаем («Растворение PVDF в NMP», «Ввод Super P», …);
- tool — enum MixToolType: MANUAL/MAGNETIC/PLANETARY/DISC/THREE_ROLL/ULTRASONIC/VACUUM/OTHER;
- dispr — обороты диспергатора (int|null); blader — обороты лопастей (int|null);
- dur — длительность, мин (double|null); temp — T°, °C (double|null);
- vac — 1/0 вакуум; cmt — комментарий по выполняемым действиям.

## Полный формат файла (.slurrytask.json)
Тот же набор полей, но шаги могут быть объектами с ключами action/tool/dispr/blader/dur/temp/vac/cmt
(fromJson принимает оба вида). Парсер: TaskTransfer.fromJson — валидирует поле f, иначе ошибка «чужой файл».

## Если у исполнителя НЕ Android
JSON читается глазами/вводится вручную; либо экспорт CSV/текст (Export.exportCsv) — открывается
в любом редакторе iPhone/планшета. Двусторонняя синхронизация между платформами требует сервера
(Firebase Realtime DB / Supabase / self-hosted API) — в старом приложении не реализовано.
