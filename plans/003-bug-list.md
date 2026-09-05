# 🐛 2PChat: Список багов и технических проблем

> Актуализация 2026-09-05: Исторический список замечаний. Статусы и номера строк ниже относятся к исходному аудиту, не к новому запуску Android Lint. Не переносить их автоматически на основной Go-клиент.

> Составлен по результатам аудита: Android Lint (4 ошибки, 269 предупреждений) + ручной анализ кода.  
> **Статус**: ✅ = исправлено, 🔴 = критично, 🟡 = средний приоритет, 🟠 = архитектурный долг, 🔵 = низкий приоритет

---

## ✅ Уже исправлено (коммит `0dd2476`)

| # | Файл | Баг |
|---|------|-----|
| 1 | `data_extraction_rules.xml` | `FullBackupContent`: отсутствовал `domain="root"` в `<exclude>` → бэкап мог включать незащищённые данные |
| 2 | `Navigation.kt:92` | `UnusedContentLambdaTargetStateParameter`: параметр `_` в `AnimatedContent` создавал некорректную анимацию |
| 3 | `LongMediaChatPerformanceTest.kt:93` | `UnrememberedMutableState`: `mutableStateListOf()` вне `remember {}` → пересоздание при каждой рекомпозиции |
| 4 | `ChatScreen.kt:3605` | Устаревший `ACTION_MEDIA_SCANNER_SCAN_FILE` → не работал на Android 10+, файлы не появлялись в галерее |
| 5 | `P2PRelayService.kt:105` | `WakeLock.acquire()` без таймаута → потенциальная бесконечная блокировка процессора |
| 6 | `ChatScreen.kt:790` | `unread_count_$peer` увеличивался, когда чат был открыт → висел бейдж непрочитанного |
| 7 | `ChatsTab.kt:562` | Счётчик непрочитанных не сбрасывался при нажатии на чат |

---

## 🔴 Критические баги (исправить в первую очередь)

### BUG-08 · `SharedMedia.kt:108,545,681` — NPE при открытии медиа
**Проблема**: тройной `!!` на `attachmentUri` без предварительной null-проверки.  
Если запись в БД повреждена или файл удалён → **крэш приложения**.  
```kotlin
// Сейчас (опасно):
mediaList.filter { it.attachmentType == "IMAGE" }.map { it.attachmentUri!! }

// Нужно:
mediaList.filter { it.attachmentType == "IMAGE" && !it.attachmentUri.isNullOrBlank() }
         .mapNotNull { it.attachmentUri }
```
**Файл**: `ui/chat/SharedMedia.kt` · Строки: 108, 545, 681

---

### BUG-09 · `ChatScreen.kt:2349` — крэш при быстром закрытии контекстного меню
**Проблема**: `selectedMessageForOptions!!` — если пользователь открывает контекстное меню и тут же свайпает назад — состояние сбрасывается в null → **крэш**.  
```kotlin
// Сейчас (опасно):
val msg = selectedMessageForOptions!!

// Нужно:
val msg = selectedMessageForOptions ?: return
```
**Файл**: `ui/chat/ChatScreen.kt` · Строка: 2349

---

### BUG-10 · `ChatsTab.kt:628` — крэш при быстром тапе и сбросе меню
**Проблема**: `activeMenuPeer!!` — аналогично BUG-09, если лонг-тап-меню сбрасывается конкурентно → **крэш**.  
```kotlin
// Нужно:
val peer = activeMenuPeer ?: return
```
**Файл**: `ui/main/ChatsTab.kt` · Строка: 628

---

### BUG-11 · `AnimatedGifImage.kt:82,112` — крэш при воспроизведении GIF
**Проблема**: `validatedPath!!` — если `validatedPath` стал null после валидации (файл удалён между проверкой и открытием) → **крэш**.  
**Файл**: `ui/chat/AnimatedGifImage.kt` · Строки: 82, 112

---

## 🟡 Средний приоритет

### BUG-12 · `ChatScreen.kt:2922,2959` — крэш при пересылке вложения без имени
**Проблема**: `attachmentName!!` и `attachmentUri!!` при пересылке — если сообщение пришло без вложения, но UI считает его прикреплённым → крэш при попытке пересылки.  
**Файл**: `ui/chat/ChatScreen.kt` · Строки: 2922, 2959

---

### BUG-13 · `NetworkStateCallback.kt:21` — блокировка потока при смене сети
**Проблема**: `Thread.sleep(1000)` в сетевом колбэке → задержка 1 сек при каждом переключении Wi-Fi/мобильной сети → задержка переподключения Yggdrasil.  
```kotlin
// Нужно заменить на:
delay(1000) // в корутине
```
**Файл**: `yggdrasil/NetworkStateCallback.kt` · Строка: 21

---

### BUG-14 · `P2PMessageRelay.kt` — 22+ устаревших `Handler(Looper.getMainLooper()).post {}`
**Проблема**: Устаревший шаблон для Android Compose. При пересоздании Activity `Handler` может доставить сообщение в уже уничтоженный UI-контекст → утечка памяти / NullPointerException.  
**Нужно**: заменить на `withContext(Dispatchers.Main)` в корутинах.  
**Файл**: `P2PMessageRelay.kt` · Строки: 164, 179, 200, 210, 220, 229, 378, 406, 524, 541 и другие

---

### BUG-15 · `IncomingMessageServices.kt` — `SharedPreferences.commit()` на главном потоке
**Проблема**: 8 мест используют `.commit()` вместо `.apply()` — `commit()` блокирует поток до записи на диск → видимые зависания UI при интенсивной переписке.  
**Файлы**: `IncomingMessageServices.kt`, `MainActivity.kt`, `NetworkTrafficStats.kt`, `P2PPreferences.kt`, `TrackerPreferences.kt`, `YggdrasilPeerPreferences.kt`

---

### BUG-16 · `AndroidManifest.xml` — `windowSoftInputMode` на `<activity-alias>`
**Проблема**: 5 `<activity-alias>` содержат `android:windowSoftInputMode="adjustResize"` — этот атрибут **всегда игнорируется** на алиасах. Keyboard может некорректно перекрывать поле ввода при запуске через алиас (тема "Чёрная", "Синяя" и т.д.).  
**Файл**: `AndroidManifest.xml` · Строки: 41, 56, 71, 86, 101

---

## 🟠 Архитектурный технический долг

### BUG-17 · 197 широких `catch (Exception)` — маскировка ошибок
**Проблема**: Почти 200 мест ловят любое исключение, в том числе `OutOfMemoryError`, `CancellationException` (ломает корутины), `SecurityException`. Реальные ошибки скрываются, диагностика крэшей невозможна.  
**Рекомендация**: разделить на `catch (e: IOException)`, `catch (e: SQLiteException)` и т.д.

---

### BUG-18 · 103 прямых обращения к `SharedPreferences` из разных потоков
**Проблема**: Нет централизованного репозитория настроек → возможны race conditions при одновременном чтении/записи из UI-потока и сервиса.  
**Рекомендация**: вынести в `PreferencesRepository` с `Mutex` или `DataStore`.

---

## 🔵 Низкий приоритет (предупреждения Lint)

| # | Категория | Кол-во | Описание |
|---|-----------|--------|----------|
| 19 | `UseKtx` | 147 | Можно упростить код с Android KTX extensions |
| 20 | `IconLauncherShape` / `IconDuplicates` | 40 | Иконки приложения дублируются, не в адаптивном формате |
| 21 | `UseTomlInstead` | 15 | Зависимости Gradle стоит перенести в `libs.versions.toml` |
| 22 | `GradleDependency` | 6 | Доступны более новые версии зависимостей |
| 23 | `ObsoleteSdkInt` | 3 | Проверки SDK версии ниже `minSdk` — никогда не срабатывают |
| 24 | `OldTargetApi` | 1 | `targetSdk` стоит обновить до актуального |

---

## 📋 Сводная таблица приоритетов

| Приоритет | Кол-во | Риск |
|-----------|--------|------|
| ✅ Исправлено | 7 | — |
| 🔴 Критические (крэши) | 4 | NPE, крэш при взаимодействии |
| 🟡 Средний (UX / производительность) | 5 | Задержки UI, memory leak |
| 🟠 Архитектурный долг | 2 | Скрытые ошибки, race conditions |
| 🔵 Предупреждения Lint | 6 категорий | Не влияют на работу |
