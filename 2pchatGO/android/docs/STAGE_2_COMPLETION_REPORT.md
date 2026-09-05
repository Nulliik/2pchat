# Этап 2: выполненные исправления и проверки

Область: `2pchatGO/android`, по `STAGE_2_UI_STABILITY_TECHNICAL_SPEC.md`.
При проверке HEAD `849a7d4` последний коммит содержал только спецификацию;
перечисленные ниже исправления в исходниках отсутствовали.

## Выполнено

- [x] `AnimatedStickerImage.kt`: локальный снимок bitmap вместо `!!`.
- [x] `NetworkDiagnosticsDialog.kt`: один снимок результата для карточки и копирования.
- [x] Все 8 записей настроек из таблицы переведены на `apply()`:
  `Navigation.kt` (1), `P2PPreferences.kt` (2), `SecureStorage.kt` (3),
  debug `GroupE2EControl.kt` (2). Проверяемые результаты синхронной записи,
  включая миграцию, сохранены. Комментарий о блокировке смены идентичности
  уточнён: `apply()` публикует изменения в памяти до возврата из callback.
- [x] Все 15 состояний из таблицы используют специализированные Int/Long/Float
  состояния, включая значения из preferences и вычисляемые начальные значения.
- [x] Во всех 7 сигнатурах `modifier` стал первым необязательным параметром.
- [x] Очистка буфера через coroutine scope приложения и `delay(30_000L)`;
  сохранены проверка актуальности копирования и сравнение содержимого.
  Отложенная задача использует application context.
- [x] В `PacketTunnelProvider.kt` и `YggdrasilProxyService.kt` больше нет
  `Thread.sleep`. Запуск, управление и обновления выполняются последовательно
  в фоне, ожидания отменяемые. Остановка отменяет запуск, обновления и
  отложенное восстановление туннеля. Общий dispatcher сериализует завершение
  старого экземпляра и запуск нового. Блокирующее пакетное I/O оставлено
  в выделенных reader/writer потоках; их закрытие и join выполняются в фоне.

## Проверки

- Зависимости `messenger/requirements.txt` установлены.
- Полный `python -m pytest`: **209 passed, 8 skipped**. Пропущены внешние
  tracker-проверки, требующие отдельного включения.
- `gradlew.bat testDebugUnitTest --rerun-tasks`: **537 всего, 536 прошли,
  1 пропущен, 0 failures/errors**.
- `verifyNoTelemetryDependencies`: успешно, запрещённых зависимостей нет.
- Полный `lintDebug`: выполнен. **ApplySharedPref = 0,
  AutoboxingStateCreation = 0, ModifierParameter = 0**.
- Поиск по основному UI и group UI: `!!` отсутствует.
- `go test ./...`: успешно для всех пакетов с тестами.
- `go test -race ./...` с CGO и временным LLVM-MinGW: bridge, crypto,
  discovery, transport прошли. Windows дважды отказала в доступе к
  временному `session.test.exe`, в том числе при последовательном `-p 1`.
  **Полный race-прогон не подтверждён**; пакет session прошёл обычные тесты.
- `git diff --check`: успешно.

## Остаточные ограничения

Полный Lint содержит **11 ошибок и 367 предупреждений** вне трёх критериев
этапа. Ошибки находятся в неизменённых участках: `TorManager.kt` (7 NewApi),
`IncomingMessageServices.kt` (2 RestrictedApi), `ContactsTab.kt`
(StateFlowValueCalledInComposition), `Navigation.kt`
(UnusedContentLambdaTargetStateParameter). Настройка `abortOnError = false`
уже была в проекте, поэтому успешный Gradle exit code не означает отсутствие
этих ошибок. Они не подавлялись в рамках этого этапа.

Первый полный Lint упал внутри RepeatOnLifecycleDetector/Kotlin FIR;
повторный полный запуск без отключения детекторов завершился и сформировал
отчёт. Проверки на устройстве (быстрые start/stop, отзыв разрешения VPN,
переключение режимов, буфер обмена) в этой сессии не выполнялись.

Отчёты среды: `app/build/reports/lint-results-debug.html`,
`app/build/reports/tests/testDebugUnitTest/index.html`;
логи: `stage2-gradle.log`, `stage2-gradle-final.log`,
`core-go/stage2-go.log`, `core-go/stage2-go-standard.log`,
репозиторный `stage2-pytest.log`.
