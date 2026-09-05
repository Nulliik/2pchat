# Техническое задание и план реализации: Этап 2 (Стабильность Android UI и устранение дефектов)

**Проект:** 2PChat (Android Client)  
**Дата:** 5 сентября 2026 г.  
**Целевая аудитория:** Инженер-разработчик Android / Kotlin Compose  
**Приоритет:** HIGH (Стабильность UI, предотвращение ANR, устранение GC-нагрузки и крашей)  
**Статус:** Готово к выполнению  

---

## 1. Общие цели и контекст этапа

В ходе аудита кодовой базы Android и анализа статических отчётов (Android Lint SARIF, анализ Compose AST) был выявлен комплекс технических проблем, влияющих на надёжность приложения, плавность анимаций (jank) и отзывчивость интерфейса.

### Ключевые задачи этапа:
1. **Zero-NPE гарантия:** Полная ликвидация принудительного разыменования nullable-значений (`!!`) в UI-компонентах.
2. **Ликвидация синхронного дискового ввода-вывода (ANR Prevention):** Перевод синхронных `SharedPreferences.commit()` на асинхронные `apply()` на главном потоке (Main Thread).
3. **Оптимизация Compose Recompositions (Autoboxing Elimination):** Замена универсальных `mutableStateOf<T>` для примитивных типов (`Int`, `Long`, `Float`) на специализированные структуры `mutableIntStateOf`, `mutableLongStateOf`, `mutableFloatStateOf`.
4. **Стандартизация Composable-функций (Modifier Guidelines):** Приведение параметров `modifier` к официальным стандартам Android Jetpack Compose (первый опциональный параметр).
5. **Модернизация многопоточности:** Исключение устаревших `Handler(Looper.getMainLooper())` и блокирующих `Thread.sleep` в корутинных и фоновых контекстах.

---

## 2. Раздел 1: Устранение оператора `!!` (NullPointerException Prevention)

### 2.1 `AnimatedStickerImage.kt`
* **Файл:** [`app/src/main/java/com/example/twopchat/ui/chat/AnimatedStickerImage.kt`](../app/src/main/java/com/example/twopchat/ui/chat/AnimatedStickerImage.kt)
* **Строка:** 169–174
* **Проблема:** В ветке `when` проверяется `staticBitmap != null`, но поле `staticBitmap` является изменяемым свойством или локальным значением, из-за чего smart cast не срабатывает, и разработчик применил `staticBitmap!!.asImageBitmap()`. При гонке состояний или обнулении битмапа это приводит к фатальному `NullPointerException`.
* **Требуемое изменение:**
```kotlin
// БЫЛО (строка 169-174):
staticBitmap != null -> Image(
    bitmap = staticBitmap!!.asImageBitmap(),
    contentDescription = contentDescription,
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Fit,
)

// СТАЛО:
staticBitmap != null -> {
    val bitmap = staticBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
```

---

### 2.2 `NetworkDiagnosticsDialog.kt`
* **Файл:** [`app/src/main/java/com/example/twopchat/ui/main/NetworkDiagnosticsDialog.kt`](../app/src/main/java/com/example/twopchat/ui/main/NetworkDiagnosticsDialog.kt)
* **Строки:** 1175–1187
* **Проблема:** Использование `lastSweepResult!!` в параметре `result` и внутри лямбды `onCopy`. При асинхронном сбросе состояния диагностики нажатие на кнопку копирования вызывает крэш.
* **Требуемое изменение:**
```kotlin
// БЫЛО (строки 1175-1187):
if (lastSweepResult != null) {
    Spacer(modifier = Modifier.height(8.dp))
    ConnectionSweepCard(
        result = lastSweepResult!!,
        appLanguage = appLanguage,
        primaryColor = primaryColor,
        surfaceVariant = surfaceVariant,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        onCopy = {
            clipboardManager.setText(AnnotatedString(lastSweepResult!!.formattedLog))
            Toast.makeText(...)
        }
    )
}

// СТАЛО:
val sweep = lastSweepResult
if (sweep != null) {
    Spacer(modifier = Modifier.height(8.dp))
    ConnectionSweepCard(
        result = sweep,
        appLanguage = appLanguage,
        primaryColor = primaryColor,
        surfaceVariant = surfaceVariant,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        onCopy = {
            clipboardManager.setText(AnnotatedString(sweep.formattedLog))
            Toast.makeText(...)
        }
    )
}
```

---

## 3. Раздел 2: Ликвидация синхронного дискового ввода-вывода (`ApplySharedPref`)

### Почему это критично:
Метод `commit()` синхронно блокирует вызывающий поток до завершения физической записи файла XML на диск. При вызове на `Dispatchers.Main` это гарантированно приводит к пропуску кадров (frame drops, jank) и системным диалогам ANR («Приложение не отвечает»).
Метод `apply()` немедленно обновляет состояние в оперативной памяти (последующие вызовы `get*()` получают актуальные данные) и асинхронно производит I/O на диске в пуле Android OS.

### Список мест для обязательной замены:

| # | Файл | Строка | Текущий код | Требуемое изменение |
|---|------|:------:|-------------|---------------------|
| 1 | [`Navigation.kt`](../app/src/main/java/com/example/twopchat/Navigation.kt) | 68 | `sharedPrefs.edit().putBoolean("onboarding_completed", true).commit()` | `sharedPrefs.edit().putBoolean("onboarding_completed", true).apply()` |
| 2 | [`P2PPreferences.kt`](../app/src/main/java/com/example/twopchat/config/P2PPreferences.kt) | 743 | `editor.commit()` | `editor.apply()` |
| 3 | [`P2PPreferences.kt`](../app/src/main/java/com/example/twopchat/config/P2PPreferences.kt) | 1082 | `editor.commit()` | `editor.apply()` |
| 4 | [`SecureStorage.kt`](../app/src/main/java/com/example/twopchat/security/SecureStorage.kt) | 235 | `sharedPrefs.edit().putString("db_passphrase_enc", encString).commit()` | `sharedPrefs.edit().putString("db_passphrase_enc", encString).apply()` |
| 5 | [`SecureStorage.kt`](../app/src/main/java/com/example/twopchat/security/SecureStorage.kt) | 338 | `sharedPrefs.edit().putString(PREF_GO_STORAGE_KEY_ENC, encString).commit()` | `sharedPrefs.edit().putString(PREF_GO_STORAGE_KEY_ENC, encString).apply()` |
| 6 | [`SecureStorage.kt`](../app/src/main/java/com/example/twopchat/security/SecureStorage.kt) | 348 | `P2PPreferences.prefs(context).edit().remove(PREF_GO_STORAGE_KEY_ENC).commit()` | `P2PPreferences.prefs(context).edit().remove(PREF_GO_STORAGE_KEY_ENC).apply()` |
| 7 | [`GroupE2EControl.kt`](../app/src/debug/java/com/example/twopchat/debug/GroupE2EControl.kt) | 28 | `prefs.edit().putString("...", "...").commit()` | `.apply()` |
| 8 | [`GroupE2EControl.kt`](../app/src/debug/java/com/example/twopchat/debug/GroupE2EControl.kt) | 35 | `prefs.edit().remove("...").commit()` | `.apply()` |

> **Примечание:** В местах, где результат `commit()` проверяется для контроля ошибок миграции базы данных (например, `P2PPreferences.kt:556-557` `check(editor.commit())`), `commit()` **сохраняется**, так как выполняется на фоновом потоке миграции.

---

## 4. Раздел 3: Устранение боксинга примитивов в Compose (`AutoboxingStateCreation`)

### Почему это критично:
`mutableStateOf(0)` создает `MutableState<java.lang.Integer>`. При каждом изменении значения (анимации, счетчики, ввод текста) создается новый экземпляр класса-обертки на куче (heap allocation), что нагружает Garbage Collector (GC) и вызывает микрофризы интерфейса.
Специализированные типы `mutableIntStateOf`, `mutableFloatStateOf`, `mutableLongStateOf` хранят примитивные типы `int`, `float`, `long` без автобоксинга.

### Таблица исправлений:

| # | Файл | Строка | Текущий код | Требуемый код |
|---|------|:------:|-------------|---------------|
| 1 | [`ChatMessageBubble.kt`](../app/src/main/java/com/example/twopchat/ui/chat/ChatMessageBubble.kt) | 127 | `remember { mutableStateOf(0f) }` | `remember { mutableFloatStateOf(0f) }` |
| 2 | [`ChatScreen.kt`](../app/src/main/java/com/example/twopchat/ui/chat/ChatScreen.kt) | 104 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 3 | [`ChatScreen.kt`](../app/src/main/java/com/example/twopchat/ui/chat/ChatScreen.kt) | 183 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 4 | [`CurrencyRatesScreen.kt`](../app/src/main/java/com/example/twopchat/ui/disguise/CurrencyRatesScreen.kt) | 38 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 5 | [`GroupChatScreen.kt`](../app/src/main/java/com/example/twopchat/group/ui/GroupChatScreen.kt) | 386 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 6 | [`ImageCropper.kt`](../app/src/main/java/com/example/twopchat/ui/onboarding/ImageCropper.kt) | 88 | `remember { mutableStateOf(1f) }` | `remember { mutableFloatStateOf(1f) }` |
| 7 | [`MainActivity.kt`](../app/src/main/java/com/example/twopchat/MainActivity.kt) | 571 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 8 | [`MainActivity.kt`](../app/src/main/java/com/example/twopchat/MainActivity.kt) | 572 | `remember { mutableStateOf(0L) }` | `remember { mutableLongStateOf(0L) }` |
| 9 | [`MainActivity.kt`](../app/src/main/java/com/example/twopchat/MainActivity.kt) | 574 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 10 | [`MainScreen.kt`](../app/src/main/java/com/example/twopchat/ui/main/MainScreen.kt) | 81 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 11 | [`MainScreen.kt`](../app/src/main/java/com/example/twopchat/ui/main/MainScreen.kt) | 108 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 12 | [`OnboardingScreen.kt`](../app/src/main/java/com/example/twopchat/ui/onboarding/OnboardingScreen.kt) | 55 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 13 | [`SettingsTab.kt`](../app/src/main/java/com/example/twopchat/ui/main/SettingsTab.kt) | 79 | `remember { mutableStateOf(0f) }` | `remember { mutableFloatStateOf(0f) }` |
| 14 | [`SettingsTab.kt`](../app/src/main/java/com/example/twopchat/ui/main/SettingsTab.kt) | 125 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |
| 15 | [`SharedMedia.kt`](../app/src/main/java/com/example/twopchat/ui/chat/SharedMedia.kt) | 185 | `remember { mutableStateOf(0) }` | `remember { mutableIntStateOf(0) }` |

*Не забудьте добавить соответствующий импорт:*
```kotlin
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
```

---

## 5. Раздел 4: Стандартизация параметров `Modifier` (`ModifierParameter`)

### Правило Compose:
В соответствии с [официальными соглашениями Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#elements-accept-and-respect-a-modifier-parameter):
Каждый Composable-компонент, принимающий `modifier`, должен объявлять его **первым опциональным параметром** в списке параметров (с дефолтным значением `= Modifier`). Это позволяет вызывающей стороне предсказуемо передавать модификаторы без использования именованных аргументов.

### Места для исправления порядка параметров:

1. **[`ChatMessageBubble.kt:1744`](../app/src/main/java/com/example/twopchat/ui/chat/ChatMessageBubble.kt#L1744)**
   * Переместить `modifier: Modifier = Modifier` в начало списка параметров со значениями по умолчанию.
2. **[`ConversationComponents.kt:170`](../app/src/main/java/com/example/twopchat/ui/chat/ConversationComponents.kt#L170)**
   * Поставить `modifier: Modifier = Modifier` перед другими опциональными параметрами.
3. **[`ConversationComponents.kt:218`](../app/src/main/java/com/example/twopchat/ui/chat/ConversationComponents.kt#L218)**
   * Переставить `modifier: Modifier = Modifier` на первую опциональную позицию.
4. **[`GroupChatScreen.kt:2275`](../app/src/main/java/com/example/twopchat/group/ui/GroupChatScreen.kt#L2275)**
   * Поставить `modifier: Modifier = Modifier` перед остальными аргументами с дефолтными значениями.
5. **[`MainComponents.kt:79`](../app/src/main/java/com/example/twopchat/ui/main/MainComponents.kt#L79)**
   * Упорядочить параметры функции: обязательные аргументы $\rightarrow$ `modifier: Modifier = Modifier` $\rightarrow$ остальные опциональные.
6. **[`MainScreen.kt:78`](../app/src/main/java/com/example/twopchat/ui/main/MainScreen.kt#L78)**
   * Сделать `modifier: Modifier = Modifier` первым опциональным параметром.
7. **[`QualityTooltip.kt:37`](../app/src/main/java/com/example/twopchat/ui/chat/QualityTooltip.kt#L37)**
   * Переместить `modifier: Modifier = Modifier` на первую позицию опциональных аргументов.

---

## 6. Раздел 5: Безопасность многопоточности и исключение `Thread.sleep`

1. **[`ClipboardUtils.kt:23`](../app/src/main/java/com/example/twopchat/ClipboardUtils.kt#L23):**
   * Заменить `Handler(Looper.getMainLooper()).postDelayed({...}, 30000)` на запуск очистки через CoroutineScope приложения с `delay(30_000L)` или системный `ClipboardManager.clearPrimaryClip()` (на Android 13+).
2. **Yggdrasil-модули ([`YggdrasilProxyService.kt`](../app/src/main/java/com/example/twopchat/yggdrasil/YggdrasilProxyService.kt), [`PacketTunnelProvider.kt`](../app/src/main/java/com/example/twopchat/yggdrasil/PacketTunnelProvider.kt)):**
   * Заменить циклические `Thread.sleep(...)` внутри корутинных scope на неблокирующий `delay(...)` с проверкой `isActive` для мгновенной реакции на отмену сервиса при закрытии приложения.

---

## 7. Чек-лист верификации для исполнителя

После внесения изменений исполнитель обязан последовательно выполнить в терминале:

```bash
# 1. Проверка отсутствия регрессий в Android unit-тестах:
./gradlew testDebugUnitTest --rerun-tasks

# 2. Проверка отсутствия запрещенных зависимостей телеметрии:
./gradlew verifyNoTelemetryDependencies

# 3. Полный прогон статического анализатора Android Lint:
./gradlew lintDebug

# 4. Прогон Go-тестов с детектором гонок:
cd core-go && go test -race ./... && cd ..

# 5. Репозиторный сьют тестов (согласно AGENTS.md):
pytest messenger/tests
```

### Критерии успешной приёмки:
- `ApplySharedPref`: 0 предупреждений (все вызовы на Main Thread переведены на `.apply()`).
- `AutoboxingStateCreation`: 0 предупреждений.
- `ModifierParameter`: 0 предупреждений.
- Отсутствие `!!` в кодовой базе UI.
- Все 535 unit-тестов Android и 209 тестов Python зелёные.
