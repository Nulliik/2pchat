# Проверка цепочки исправлений маршрутов

Проверены изменения от `a146bf4` до `8638558` и незакоммиченные исправления в рабочем дереве на 13 сентября 2026 г. Цель — убедиться, что сохранённые маршруты не исчезают, а новые приватные маршруты передаются только по уже аутентифицированному каналу.

## Подтверждённые исправления

* Канонизация fingerprint теперь поддерживает фактический padded Base64-формат публичного ключа без изменения регистра и старый hex-формат. Раньше Base64-идентификатор отбрасывался при сохранении endpoint.
* Marker импорта legacy endpoint больше не пытается повторно импортировать уже обработанные, но удалённые маршруты. Это исключает исключение SQLite при обслуживании пустого кэша.
* Удаление группового события стирает файл только после успешного коммита SQLCipher. При отклонённом коммите вложение остаётся доступно.
* Лимиты размера входных данных проверяются до продвижения ratchet/counter. Максимальный двоичный payload приведён к фактическому пределу transport frame.
* Запуск Yggdrasil из отдельного процесса передаёт живой IP основному процессу через package-scoped broadcast. После успешного announce новый Yggdrasil/Tor endpoint отправляется существующим online-пирам через прежний зашифрованный и аутентифицированный endpoint-update; discovery не повышает доверие.
* `YGGDRASIL_ONLY` разрешён политикой transport, поскольку proxy mode использует локальный SOCKS relay и не требует LAN/WAN fallback.

Изменения не меняют криптографические примитивы, формат пакета, fingerprint-проверку, схему БД или модель доверия.

## Проверка

| Проверка | Результат |
| --- | --- |
| Инструментальные регрессии endpoint import и rollback удаления вложения | PASS |
| Два эмулятора: peer discovery, маршруты, сообщения, natural restart/offline delivery | PASS |
| Два эмулятора: group invite/text/edit/reaction/poll/file/cache/delete/roles/natural restart | PASS |
| Два эмулятора: базовое direct соединение | PASS |
| Два эмулятора: запуск Yggdrasil, передача endpoint без reconnect, двусторонний `YGGDRASIL_ONLY` чат | PASS |
| `go build ./...` | PASS |
| `go test -count=1 ./...` | PASS |
| `go test -race -count=1 ./...` | PASS |
| `go vet ./...` | PASS |
| `gradlew testDebugUnitTest assembleDebug` | PASS; `buildGoCoreBinaries` выполнился |
| `gradlew lintDebug` | PASS |
| Python compatibility: `python -m pytest` | PASS: 228 passed, 8 skipped |

Tor overlay не получил endpoint на втором эмуляторе в течение 180 секунд: `tor_running=false`, onion address пуст. Поэтому обмен Tor endpoint и `TOR_ONLY` чат — **UNVERIFIED**. Это инфраструктурная неполнота прогона, а не успешная проверка или подтверждённый дефект кода.

Дополнительные сканеры (`staticcheck`, `govulncheck`, `semgrep`, `gitleaks`, `trivy`) остаются **UNVERIFIED**, поскольку они не установлены. Ранее воспроизводился нестабильный Windows cleanup в существующем тесте отмены file transfer; текущие последовательный и race-прогоны прошли.

## Артефакты

Обычный debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Логи текущего запуска: `commit-review-final-android.log`, `commit-review-final-lint.log`, `commit-review-peer-e2e.log`, `commit-review-group-e2e.log`, `commit-review-overlay-e2e.log`.
