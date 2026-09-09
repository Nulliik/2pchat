# Отчёт об устранении уязвимостей сетевого транспорта и реализации NetworkPolicy

> Статус документа уточнён 2026-09-05. Отчёт об отдельном изменении сетевой политики. Числа тестов относятся к описанному запуску, а не к постоянной гарантии качества.
**Проект:** 2PChat (Android + Go Core)  
**Дата:** 5 сентября 2026 г.  
**Статус:** Реализовано и верифицировано (100% тестов пройдены)  
**Идентификаторы безопасности:** SEC-07a, SEC-07b, SEC-08, SEC-09  

---

## 1. Executive Summary (Краткое резюме для команды)

По результатам аудита сетевой подсистемы и ревью были выявлены и полностью устранены критические архитектурные риски деанонимизации пользователей и несанкционированного сканирования локальных сетей (SSRF).

Внедрена система строгого разграничения сетевых транспортов (**`NetworkPolicy`**), устраняющая риски утечки clearnet-трафика в сеть для контактов Tor и обеспечивающая полную изоляцию оверлейных сетей (Tor, Yggdrasil) и прямого P2P (Direct LAN/WAN).

### Ключевые результаты:
1. **Per-Peer Authority в ядре Go (`session.Manager`):** Политика контакта теперь имеет абсолютный приоритет не только на исходящих подключениях, но и на автоматических повторных попытках (reconnect) и входящих рукопожатиях (inbound handshakes). Входящий clearnet-трафик от пиров с политикой `TOR_ONLY` отсекается на уровне ядра до создания сессии.
2. **Нулевые утечки DNS (Pre-Resolver Fail-Closed):** В `AdaptiveDialer` внедрена проверка доменных имён до обращения к системному DNS-резолверу. При запрете `AllowLocalDNS` диалер прерывает операцию с `ErrPolicyDenied` до обращения к DNS провайдера.
3. **Защита локальной сети (Anti-SSRF):** Ограничены допустимые порты для кандидатов из LAN/loopback. Запрещены все привилегированные порты $< 1024$ (SSH 22, HTTP 80, HTTPS 443 и др.) и известные локальные админ/прокси-порты (1080, 1900, 3000, 3128, 5000, 5353, 8000, 8080, 8443, 8888, 9050, 9051, 9053). При этом полностью сохранена поддержка пользовательской настройки «Порт входящих подключений» (любые непривилегированные порты 1024–65535 вне чёрного списка).
4. **Валидация битовых флагов JNI:** На границе Kotlin $\rightarrow$ Cgo $\rightarrow$ Go внедрена строгая валидация битовой маски `ValidateFlags(flags int)`. Невалидные или противоречивые комбинации флагов отклоняются до попадания в рантайм ядра.
5. **UI-события конфликтов (`PolicyConflict`):** При взаимоисключающих настройках (например, глобальный режим `Tor Strict` + контакт с политикой `DIRECT_ONLY` или `YGGDRASIL_ONLY`) соединение безопасно блокируется (fail-closed), а в интерфейс отправляется явное событие `PolicyConflict`.
6. **SQLCipher v14 Migration Backfill:** При обновлении со старых версий базы данных контакты с `.onion` адресом и без истории clearnet автоматически получают статус `TOR_ONLY` (2), исключая незаметную эрозию приватности при получении clearnet-эндпоинтов от собеседника.

---

## 2. Подробный разбор изменений по пунктам ревью

### 2.1 Авторитет политики контакта в ядре Go (Вопрос 1)
* **Проблема:** До правок политика контакта (`policyFlags`) передавалась только точечно в `ConnectPeerWithPolicy`. При фоновом автореконнекте (`connectPeerInternal`) или входящем подключении через слушающий сокет ядро использовало общую глобальную политику, что создавало уязвимость: контакт `TOR_ONLY` мог связаться или переподключиться по незащищённому clearnet TCP.
* **Решение:**
  * В [`core-go/pkg/session/manager.go`](../core-go/pkg/session/manager.go) добавлено хранилище политик пиров:
    ```go
    peerPolicies map[string]transport.NetworkPolicy
    ```
  * При вызовах `ConnectPeerWithPolicy` и `SetPeerPolicy(peerFP, policy)` политика фиксируется в ядре.
  * Метод `connectPeerInternal` проверяет `peerPolicies` для `expectedFingerprint`, если явная политика не была передана в аргументах.
  * В `handleIncomingConnection(conn net.Conn)` внедрена двухконтурная защита от раскрытия личности (SEC-03):
    1. **Pre-Handshake Transport Guard:** До начала криптографического рукопожатия проверяется глобальная политика (`m.policy.Allows(inboundClass)`). Если устройство работает в режиме `Tor Strict`, любые входящие clearnet-соединения закрываются мгновенно, без чтения/записи байт и без нагрузки на CPU.
    2. **Pre-Reply Peer Policy Guard (`WithPeerValidator`):** Внутри `performResponderHandshake` сразу после расшифровки `init`-пакета и вычисления отпечатка инициатора вызывается валидатор политики. Если для данного пира установлена политика `TOR_ONLY` (или другая запрещающая данный входящий транспорт), рукопожатие прерывается **ДО** генерации и отправки ответа (`reply`). Ответчик закрывает сокет, не передав ни единого байта своего постоянного ключа (`IdentityPub`). Атакующий получает EOF и не может деанонимизировать узел.
  * В методе `ApplyPolicy(p NetworkPolicy)` проверяется признак `sess.IsTorTransport()`: при переходе в `Tor Strict` закрываются только clearnet-сессии, а активные сессии через Tor Onion Service остаются онлайн.

### 2.2 Обработка конфликтов политик в UI (Вопрос 2)
* **Проблема:** Если глобально активирован `Tor Strict` (`AllowOnion = true`, остальные `false`), а для контакта выставлен `DIRECT_ONLY` (`AllowLAN | AllowWAN`) или `YGGDRASIL_ONLY`, их пересечение даёт пустое множество (`IsDenyAll() == true`). Без специального события пользователь видел бы бесконечную попытку подключения без объяснения причин.
* **Решение:**
  * В [`app/src/main/java/com/example/twopchat/tor/TransportEventManager.kt`](../app/src/main/java/com/example/twopchat/tor/TransportEventManager.kt) добавлено событие:
    ```kotlin
    data class PolicyConflict(
        val peerName: String,
        val contactPolicy: String,
        val globalPolicy: String
    ) : TransportEvent()
    ```
  * В [`NativeBridgeImpl.kt`](../app/src/main/java/com/example/twopchat/bridge/NativeBridgeImpl.kt) в методе `reconnectPeerSession` добавлена проверка конфликта:
    ```kotlin
    val isTorStrict = ProxyConfig.getEffectiveProxyConfig(context).enabled &&
        P2PPreferences.isTorStrictMode(context)
    if (isTorStrict && (pref == PeerTransportPreference.DIRECT_ONLY || pref == PeerTransportPreference.YGGDRASIL_ONLY)) {
        SafeLog.w(TAG, "[PolicyConflict] Peer $peerName policy ${pref.key} conflicts with global Tor Strict mode.")
        TransportEventManager.emit(
            TransportEvent.PolicyConflict(peerName, pref.key, "tor_strict")
        )
        return false
    }
    ```

### 2.3 Валидация битовых флагов политики на границе JNI (Вопрос 3)
* **Проблема:** Передача сырого `jint` через JNI без проверки маски несла риски некорректных внутренних состояний и паник при некорректных флагах.
* **Решение:**
  * В [`core-go/pkg/transport/policy.go`](../core-go/pkg/transport/policy.go) реализована функция `ValidateFlags(flags int) error`:
    - Проверяет маску `PolicyAllFlagsMask = 0x1F` (отклоняет нераспределённые биты).
    - Запрещает `flags != 0`, не разрешающие ни один транспорт (deny-all).
    - Запрещает `AllowLocalDNS` без `AllowLAN` или `AllowWAN`.
    - Запрещает `AllowWAN` без `AllowLAN`.
    - Запрещает одиночный `AllowYggdrasil` без clearnet или Tor.
  * В [`core-go/cmd/lib2pcore/main.go`](../core-go/cmd/lib2pcore/main.go) валидация встроена во все нативные методы JNI: `nativeApplyPolicy`, `nativeConnectPeer`, `nativeProbePeer`, `nativeSetPeerPolicy`.

### 2.4 Защита от DNS-утечек до системного резолвера (Вопрос 4)
* **Проблема:** Если диалер вызывал `net.Resolver` до оценки флага `AllowLocalDNS`, ОС Android отправляла UDP-запрос к системному DNS провайдера, раскрывая посещаемые домены.
* **Решение:**
  * В [`core-go/pkg/transport/dialer.go`](../core-go/pkg/transport/dialer.go) в `DialContext` добавлена проверка хоста перед любым вызовом резолвера:
    ```go
    isIP := net.ParseIP(host) != nil
    isOnion := strings.HasSuffix(strings.ToLower(host), ".onion")
    if !isIP && !isOnion && !p.AllowLocalDNS && class != TransportTor {
        return nil, fmt.Errorf("%w: local DNS resolution is prohibited by network policy for host '%s'", ErrPolicyDenied, host)
    }
    ```
  * Реализован метод `SetResolver(r *net.Resolver)` для модульных тестов утечек.

### 2.5 Ограничение портов LAN при пробинге (Anti-SSRF) (Вопрос 5)
* **Проблема:** Атакующий мог передать в качестве эндпоинта пира адрес `192.168.1.1:80` или `127.0.0.1:9050`, принуждая устройство сканировать внутреннюю сеть и локальные веб-админки роутеров.
* **Решение и поддержка произвольных портов:**
  * В интерфейсе приложения («Параметры сети») пользователь может настроить «Порт входящих подключений» (1024..65535, дефолт 50001), где явно указано: *«Одинаковый порт не требуется: он публикуется через DHT, трекеры и локальную сеть»*.
  * Поэтому вместо жёсткой привязки к единственному порту `50001` реализована функция `isSensitiveLocalPort`:
    - Запрещены все системные привилегированные порты $< 1024$ (HTTP 80, HTTPS 443, SSH 22, SMB 445 и т.д.).
    - Запрещены известные сервисные, веб-админ и прокси порты: `1080, 1900, 3000, 3128, 5000, 5353, 8000, 8008, 8080, 8081, 8443, 8888, 9050, 9051, 9053`.
    - Разрешены любые непривилегированные порты (1024–65535) вне чёрного списка:
    ```go
    if class == transport.TransportLAN || ip.IsLoopback() {
        if port < 1024 || isSensitiveLocalPort(port) {
            continue
        }
    }
    ```
  * Это надёжно блокирует SSRF-атаки на роутеры и инфраструктуру, при этом полностью сохраняя гибкость настройки нестандартных P2P-портов для пользователей.

### 2.6 Бэкфилл в миграции базы данных v14 (Вопрос 6)
* **Проблема:** Ранее при миграции v13 $\rightarrow$ v14 колонка `transport_policy` заполнялась дефолтным нулём (`AUTO`). Контакты, с которыми пользователь общался строго по Tor, могли незаметно переключиться на clearnet при получении обычного IP.
* **Решение:**
  * В [`ChatDatabaseHelper.kt`](../app/src/main/java/com/example/twopchat/data/ChatDatabaseHelper.kt) в блоке `oldVersion < 14` добавлен SQL-запрос:
    ```kotlin
    db.execSQL(
        "UPDATE $TABLE_PEERS SET $KEY_TRANSPORT_POLICY = 2 " +
        "WHERE $KEY_ONION_ADDRESS IS NOT NULL AND $KEY_ONION_ADDRESS != '' " +
        "AND ($KEY_LAST_ENDPOINT IS NULL OR $KEY_LAST_ENDPOINT = '' OR $KEY_LAST_ENDPOINT LIKE '%.onion%')"
    )
    ```
  * Все существующие контакты с `.onion` адресом и без истории clearnet гарантированно получают статус `TOR_ONLY` (2).

---

## 3. Матрица пересечения политик ($4 \times 3$)

Система поддерживает 4 политики для конкретного контакта и 3 глобальных режима устройства:

| Политика контакта / Глобальный режим | Dual-Stack / Speed (31) | Clearnet / Tor Off (7) | Tor Strict (8) |
|---|---|---|---|
| **AUTO (0)** | Все транспорты (31) | LAN, WAN, Yggdrasil (7) | Только Tor (.onion) (8) |
| **DIRECT_ONLY (3)** | LAN, WAN (3) | LAN, WAN (3) | **Блокировка (PolicyConflict)** |
| **YGGDRASIL_ONLY (4)** | Yggdrasil IPv6 (4) | Yggdrasil IPv6 (4) | **Блокировка (PolicyConflict)** |
| **TOR_ONLY (8)** | Только Tor (.onion) (8) | **Блокировка (TorUnavailable)** | Только Tor (.onion) (8) |

---

## 4. Результаты тестирования и приёмочного контроля

### 4.1 Тесты ядра Go (`go test -race -count=1 ./...`)
Все пакеты ядра Go протестированы с включённым детектором состояний гонки:
* `twopchat/core/pkg/bridge` — **PASS**
* `twopchat/core/pkg/crypto` — **PASS**
* `twopchat/core/pkg/discovery` — **PASS**
* `twopchat/core/pkg/session` — **PASS**
* `twopchat/core/pkg/transport` — **PASS**

Специализированные тестовые наборы:
* `policy_monotone_test.go`: математическая монотонность `Intersect` и валидатор битовых масок.
* `dns_leak_test.go`: перехват и подтверждение нулевого вызова DNS при `AllowLocalDNS = false`.
* `probing_port_filter_test.go`: сброс LAN-кандидатов с портами 80, 443, 8080, 9050, 22.
* `listener_rebind_test.go`: перепривязка слушателя на `127.0.0.1` при активации Strict-режима.
* `manager_peer_authority_test.go`: отказ во входящем clearnet-соединении от пира `TOR_ONLY`, реконнект с сохранением политики пира, сохранение Tor-сессий в `ApplyPolicy`.

### 4.2 Тесты Android слоя (`./gradlew testDebugUnitTest`)
* Успешная компиляция NDK `lib2pcore.so` под архитектуры `arm64-v8a`, `x86_64`, `armeabi-v7a`.
* Пройдены новые тесты Kotlin:
  * `ChatDatabaseBackfillTest`: верификация SQL-запроса миграции и логики классификации контактов.
  * `NativeBridgeFlagMappingTest`: проверка матрицы $4 \times 3$, эмиссии `PolicyConflict` и защиты от даунгрейда.

### 4.3 Репозиторный тестовый набор (`pytest`)
* В соответствии с инструкцией `AGENTS.md` запущен полный Python-сьют:
  ```
  209 passed, 8 skipped (внешние live-трекеры) — 100% SUCCESS
  ```

---

## 5. Анализ остаточных рисков (SEC-09)

* **Уязвимость:** Доверие к локальному сокету Tor SOCKS5 / Listener (`127.0.0.1:9050`, `127.0.0.1:50001`).
* **Уровень риска:** MEDIUM.
* **Описание:** На Android устройствах любое установленное стороннее приложение с разрешением `android.permission.INTERNET` может подключиться к локальному loopback TCP-порту.
* **Компенсирующие меры:** 
  * Входящие P2P-соединения требуют обязательного криптографического рукопожатия X3DH с подписью Ed25519. Неавторизованный трафик мгновенно закрывается без создания сессии.
  * Доступ к Tor SOCKS5 прокси защищён изоляцией процесса, однако локальный порт слушает `127.0.0.1`.
* **Рекомендация на следующую версию:** Переход на Unix Domain Sockets (UDS) вместо TCP loopback для взаимодействия с Tor daemon на поддерживаемых версиях Android (API 29+).
