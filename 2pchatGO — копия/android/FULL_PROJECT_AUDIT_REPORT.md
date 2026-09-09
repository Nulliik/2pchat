# 📊 Полный аудит проекта 2PChat (Go Core + Android)

> Статус документа уточнён 2026-09-05. Исторический исходный аудит. Перечисленные проблемы и итоговый вердикт относятся к исходному срезу, а не автоматически к текущей ветке; см. последующие отчёты remediation и ADR.

## 1. Executive Summary (Резюме)
- **Общий статус стабильности:** ⚠️ **NEEDS FIXES (ТРЕБУЮТСЯ ИСПРАВЛЕНИЯ)**
- **Количество критических ошибок (🔴 Critical):** 4
- **Количество важных архитектурных проблем (🟠 High):** 3
- **Количество проблем адаптации (🟡 Medium):** 2
- **Вердикт:** **Needs Fixes** (Не готов к продакшену до устранения критических ошибок утечки JNI-потоков, подмены идентичности и рассинхронизации состояний).

Проведён глубокий комплексный аудит исходного кода ядра **Go Core (`core-go/`)**, CGO/JNI моста (**`cmd/lib2pcore`**, `main.go`, `jni_callbacks.c`) и слоя интеграции Android Kotlin (**`NativeBridgeImpl.kt`**, `P2PMessageRelay.kt`, `NativeBridge.kt`). 
Анализ выполнялся строго на основе стандартов безопасности `RULES.md` (v4.0) и руководства по рефакторингу.

---

## 2. 🔴 Критические ошибки (Critical Issues)

### Issue 1: Утечка привязки потоков JNI (AttachCurrentThread без DetachCurrentThread)
- **File:** [`jni_callbacks.c`](core-go/cmd/lib2pcore/jni_callbacks.c)
- **Root Cause:** В функции `getJNIEnv()` при обратном вызове из Go-горутины в Java исполняется `(*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL)`. Однако после завершения обработки JNI-вызова функция `DetachCurrentThread` никогда не вызывается.
- **Impact:** Системная утечка ссылок на нативные потоки в виртуальной машине Android ART JVM. При завершении или переиспользовании OS-потоков runtime Go происходят падения приложения с ошибками `Fatal signal 11 (SIGSEGV)` или `thread attached to JVM exited without detaching` при активном обмене сообщениями и передаче файлов.
- **Fix:** Реализовать корректный менеджмент потоков JNI: при привязке `JNI_EDETACHED` сохранять флаг привязки в Thread-Local Storage (TLS) или оборачивать вызовы callbacks вызовом `(*g_jvm)->DetachCurrentThread(g_jvm)` перед завершением функции.

---

### Issue 2: Компрометация идентичности пира через слепой фоллбэк (1-on-1 Fallback Mapping Hijack)
- **File:** [`NativeBridgeImpl.kt`](app/src/main/java/com/example/twopchat/bridge/NativeBridgeImpl.kt)
- **Root Cause:** В методе `resolvePeerName(fingerprint)` содержится эвристика: если от неизвестного отпечатка `fingerprint` приходит входящее соединение, а в приложении открыт ровно 1 активный чат (например, `dogGO`), Kotlin автоматически привязывает этот неизвестный отпечаток к никнейму единственного пира и перезаписывает `peer_fingerprint_<peerName>` в `SharedPreferences`.
- **Impact:** 🔴 **Критическая уязвимость безопасности и приватности (Нарушение Rule §14).** Любой сторонний узел сети или сканер портов, подключившись к порту пользователя, может подменить отпечаток ключа легитимного контакта в базе данных приложения. Пользователь начнёт отправлять зашифрованные сообщения на ключ атакующего.
- **Fix:** Полностью удалить эвристический фоллбэк `activeChats.size == 1`. Идентичность должна подтверждаться **исключительно** путем успешного криптографического рукопожатия X3DH и обмена подписанными кадрами `identity_info`.

---

### Issue 3: Ошибка компиляции CGO при отсутствии системных заголовков NDK
- **File:** [`jni_callbacks.h`](core-go/cmd/lib2pcore/jni_callbacks.h) & [`jni_callbacks.c`](core-go/cmd/lib2pcore/jni_callbacks.c)
- **Root Cause:** В `jni_callbacks.h` объявлены фоллбэк-типы `typedef struct JNIEnv_ JNIEnv;` и `typedef struct JavaVM_ JavaVM;` на случай отсутствия `<jni.h>`. Однако в `jni_callbacks.c` разыменование `(*vm)->GetEnv` и `(*env)->FindClass` производится по синтаксису си-структур указателей на таблицы функций. В среде сборки без NDK это вызывает ошибку компиляции: `error: member reference type 'JavaVM' is not a pointer`.
- **Impact:** Невозможность выполнения `go test ./...` для пакета `cmd/lib2pcore` в окружении разработчика и CI/CD без полноценного Android NDK Toolchain.
- **Fix:** Добавить полные определения структур таблицы функций JNI в фоллбэк-заголовок или изолировать JNI-реализацию с помощью build tags `//go:build android`.

---

### Issue 4: Состояние "Призрачной сессии" (Ghost Session Bug) и разрассогласование источника истины
- **File:** [`NativeBridgeImpl.kt`](app/src/main/java/com/example/twopchat/bridge/NativeBridgeImpl.kt) & [`NativeBridge.kt`](app/src/main/java/com/example/twopchat/NativeBridge.kt)
- **Root Cause:** Kotlin поддерживает собственное кэшированное состояние `onlinePeers` (`ConcurrentHashMap<String, Boolean>`), обновляемое асинхронно через `bridgeScope.launch`. При обрыве TCP-сокета ядро Go немедленно закрывает сессию, но событие `onPeerDisconnected` отправляется в Kotlin асинхронно. Если UI или Relay опрашивают статус онлайн до выполнения корутины, они видят `online = true`, но вызовы `sendMessage` в Go Core завершаются ошибкой.
- **Impact:** Сообщения зависают в очереди `pendingMessages` или молча теряются, пока пользователь вручную не перезапустит приложение. Нарушен принцип "Single Source of Truth".
- **Fix:** Сделать Go Core **единственным источником истины**: прямые вызовы `NativeBridge.isPeerOnline(peerFP)` должны запрашивать живой статус `Session.IsOnline()` в Go Core без использования промежуточного кэша booleans в Kotlin.

---

## 3. 🟠 Архитектурные проблемы и Структура кода

### 1. Неочищенный секретный материал DH в памяти кучи (Heap Memory Exposure)
- **File:** [`ratchet.go`](core-go/pkg/crypto/ratchet.go) & [`keys.go`](core-go/pkg/crypto/keys.go)
- **Root Cause:** Функция `crypto.DH()` возвращает новый срез `shared`, выделенный в куче. В `InitializeSessionFromPreKey` промежуточные вычисления `dh1, dh2, dh3, dh4` объединяются в `material`, однако исходные срезы `dh1..dh4` не затираются функцией `Zeroize()`.
- **Impact:** Непосредственный секретный материал Diffie-Hellman остается в оперативной памяти кучи Go до срабатывания Garbage Collector. Нарушение Rule §8 (Sensitive Memory Zeroization).
- **Fix:** Добавить немедленный вызов `defer crypto.Zeroize(dh1)` и аналогично для `dh2, dh3, dh4` сразу после их использования.

### 2. Риск взаимной блокировки (Deadlock) при синхронных вызовах JNI
- **File:** [`manager.go`](core-go/pkg/bridge/manager.go) & [`main.go`](core-go/cmd/lib2pcore/main.go)
- **Root Cause:** При обработке некоторых сетевых событий вызов `m.callbacks.OnPeerConnected` происходит из Go-горутин. В случае если вызов JNI в Kotlin синхронно обратится назад к Go Core через CGO (например, `goSendMessage` или `goIsPeerOnline`), происходит перекрестное захватывание мьютексов Go и блокировок JVM.
- **Impact:** Зависание нативного потока Go и UI-потока Android.
- **Fix:** Строго соблюдать Rule §6.2: скопировать необходимый стек состояния, освободить мьютексы Go `m.mu.Unlock()` и только после этого вызывать callback JNI.

### 3. Избыточные JNI-вызовы и накладные расходы при передаче файлов
- **File:** [`jni_callbacks.c`](core-go/cmd/lib2pcore/jni_callbacks.c) & [`file_transfer.go`](core-go/pkg/transport/file_transfer.go)
- **Root Cause:** При стриминге файла блоками по 64 КБ `OnFileProgress` вызывается на каждый чанк. На каждый вызов создаются новые JNI-строки `NewStringUTF(peerFP)` и `NewStringUTF(messageID)`, а в Kotlin запускаются корутины `bridgeScope.launch`.
- **Impact:** Высокая нагрузка на CPU и сборщик мусора JVM при передаче больших файлов (сотни JNI-аллокаций в секунду).
- **Fix:** Добавить троттлинг (дроппинг промежуточных вызовов прогресса чаще, чем 1 раз в 100 мс или на 1% изменения прогресса).

---

## 4. 🟡 Проблемы адаптации (Kotlin <-> Go)

### 1. Блокирующие операции I/O в SharedPreferences на потоке вызова
- **File:** [`NativeBridgeImpl.kt`](app/src/main/java/com/example/twopchat/bridge/NativeBridgeImpl.kt)
- **Root Cause:** Загрузка маппингов пиров из `P2PPreferences` выполняется синхронно при инициализации bridge и во время обработки входящих сообщений.
- **Impact:** Микролаги и подтормаживание UI при первом получении сообщений от пира.
- **Fix:** Вынести операции чтения/записи `SharedPreferences` на `Dispatchers.IO`.

### 2. Отсутствие Rate Limiting для входящих X3DH-рукопожатий
- **File:** [`session/manager.go`](core-go/pkg/session/manager.go)
- **Root Cause:** Функция `handleIncomingConnection` порождает новую горутину на каждое TCP-подключение и сразу начинает выполнение тяжелых криптографических вычислений X3DH (`curve25519.X25519`).
- **Impact:** Уязвимость к атакам типа Denial of Service (DoS) путем исчерпания CPU флудом TCP-подключений.
- **Fix:** Внедрить лимитер частоты подключений (Rate Limiter) в `AsyncListener` по IP-адресу/подсети.

---

## 5. 🛡️ Аудит безопасности и криптографии

| Проверяемый механизм | Статус | Комментарий |
| :--- | :---: | :--- |
| **Zeroize (Очистка памяти)** | 🟠 **Частично** | Ключи сессий `SessionState` очищаются при закрытии, но промежуточные результаты `DH()` в `ratchet.go` остаются в куче. |
| **Nonce Uniqueness (Уникальность нонсов)** | ✅ **ПРОЙДЕНО** | Нонсы для `SecretBox` формируются через `crypto/rand.Read` (24 байта). Для фрагментов файлов применяется схема `16 байт rand + 8 байт chunkIdx`. |
| **Double Ratchet (X3DH)** | ✅ **ПРОЙДЕНО** | Инициализация и ротация ключей ведут себя корректно. Совместимость с протоколом V4 подтверждена. |
| **Trust Model (Модель доверия)** | 🔴 **НАРУШЕНО** | Выявлена автоматическая привязка неизвестного отпечатка к единственному чату в `NativeBridgeImpl.kt`. |
| **Protocol Framing (V4 / Handshake V3)** | ✅ **ПРОЙДЕНО** | Версии пакетов `PacketVersion = 4` и `HandshakeVersion = 3` строго согласованы между Go и спецификацией. |

---

## 6. 🧪 План исправлений (Roadmap)

### Этап 1: Критические исправления (Приоритет 🔴 CRITICAL)
1. **Исправить управление JNI-потоками в `jni_callbacks.c`**:
   - Внедрить вызов `DetachCurrentThread` при завершении обратных вызовов для потоков, привязанных динамически.
2. **Безопасность модели доверия в `NativeBridgeImpl.kt`**:
   - Удалить код 1-on-1 fallback автопривязки в `resolvePeerName()`. Отпечаток пира обновляется **только** при получении валидного кадра `identity_info`.
3. **Единый источник истины (Source of Truth)**:
   - Перевести проверку статуса пира `isPeerOnline` на прямой вызов ядра Go Core `NativeBridge.isPeerOnline(peerFP)`.

### Этап 2: Защита оперативной памяти и оптимизация (Приоритет 🟠 HIGH)
4. **Zeroize промежуточных DH-ключей в `pkg/crypto/ratchet.go`**:
   - Добавить явное обнуление срезов `dh1, dh2, dh3, dh4` и `material` через `crypto.Zeroize()`.
5. **Троттлинг событий прогресса передачи файлов**:
   - Ограничить частоту вызовов `OnFileProgress` до 10 вызовов в секунду на передачу.

### Этап 3: Стабильность сборки и производительность (Приоритет 🟡 MEDIUM)
6. **Исправить CGO-заголовки `jni_callbacks.h`**:
   - Обеспечить сборку `go test ./...` без зависимости от внешней среды NDK.
7. **Rate Limiting в `session/manager.go`**:
   - Ограничить кол-во одновременных incoming handshakes.
