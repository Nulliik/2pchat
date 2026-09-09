# Доклад: Архитектура Privacy-Preserving телеметрии для Beta-тестирования 2PChat
**Версия документа:** 1.0  
**Статус:** Proposal / RFC  
**Целевая аудитория:** Core Team, Разработчики Android/Go, Инженеры безопасности  

---

## 1. Executive Summary (Введение и цели)

При переходе 2PChat на этап закрытого и открытого бета-тестирования ручной сбор отчётов («пришлите скриншот логов в чат») перестаёт работать:
1. До 80% критических падений происходят молча (пользователь просто перезапускает приложение).
2. Проблемы с установлением соединений (NAT traversal, задержки Tor, сбои маршрутизации Yggdrasil) остаются невидимыми без агрегированной статистики.
3. Сложные распределённые сценарии (например, передача владения группой Succession или детерминированное восстановление Tor Onion) требуют объективного подтверждения корректности работы на широком спектре версий Android (10–15) и вендорских прошивок (Samsung OneUI, Xiaomi MIUI/HyperOS, Google Pixel).

**Главная инженерная задача:**  
Построить систему автоматической телеметрии и мониторинга крэшей, которая покрывает **все три сетевых транспорта мессенджера (Direct P2P / LAN, Tor v3 Hidden Services, Yggdrasil Mesh)**, но при этом **математически исключает деанонимизацию бета-тестеров и сохраняет нулевое знание (Zero-Knowledge)**.

---

## 2. Модель угроз и фундаментальный инвариант приватности

### ⚠️ Критическая уязвимость наивной схемы «Мульти-транспортного аплоада»
Если клиент отправляет телеметрию на сервер телеметрии напрямую по тому же транспорту, который тестирует:
- При отправке через **Direct P2P / Clearnet** сервер (и провайдеры на пути) фиксируют **реальный IP-адрес** пользователя.
- При отправке через **Yggdrasil** сервер фиксирует **статический IPv6-адрес ноды** (который вычисляется из криптографического ключа Ed25519 устройства и является постоянным цифровым следом).
- Если один и тот же `device_id` или `session_id` сначала отправит лог через Clearnet/Yggdrasil, а затем через Tor — **анонимность Tor-сессий этого пользователя будет полностью разрушена через корреляцию идентификаторов**.

### 🛡️ Инвариант архитектуры 2PChat Telemetry
> **«Замеряем качество всех 3 транспортов локально — передаём телеметрию исключительно через анонимный канал Tor Hidden Service (либо локальный файл)»**.

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Android Client                                │
│                                                                        │
│  [Direct P2P Traffic] ───┐                                             │
│  [Yggdrasil Traffic]  ───┼─► [TransportStatsTracker (Внутри памяти)]   │
│  [Tor v3 Traffic]     ───┘               │                             │
│                                          ▼                             │
│                           [TelemetryCollector / Sanitizer]             │
│                             - Удаление любых IP/IPv6                   │
│                             - Ephemeral Batch ID                       │
│                             - Округление таймстемпов                   │
│                                          │                             │
│                                          ▼                             │
│                              [Только через Tor .onion]                 │
└──────────────────────────────────────────┬─────────────────────────────┘
                                           │ (E2E Encrypted Tor circuit)
                                           ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       Self-Hosted Backend                              │
│                                                                        │
│  [Tor Hidden Service Ingress] (Сервер видит лишь локальный Tor-сокет)   │
│                 │                                                      │
│                 ▼                                                      │
│      [Go Ingestion API] ──► [PostgreSQL] ──► [Grafana Dashboard]       │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Политика данных (Data Invariants)

### ✅ Разрешено к сбору (Strict Allowlist)
1. **Метаданные сборки:** Версия приложения (напр. `0.0.9.3(1)`), версия Go-ядра (`v1.8`), версия Android SDK (API 34), модель устройства (Pixel 6, Galaxy S23).
2. **Сводные метрики транспортов (за период):**
   - Количество успешных и неудавшихся хэндшейков (отдельно для Direct P2P, Tor, Yggdrasil).
   - Квантили задержки установления сессии (latency: min / median / max).
   - Коды ошибок разрыва (Timeout, NAT Traversal Failed, Circuit Collapse).
3. **Крэши приложения:**
   - Стек-трейс Java/Kotlin исключения.
   - Место падения в коде (класс, строка).
4. **Счётчики критических протокольных событий:**
   - `succession_cert_created`, `succession_claim_accepted`
   - `tor_onion_recovery_success`
   - `group_epoch_transition_failure`

### ❌ Категорический запрет на сбор (Strict Denylist)
- Никакого содержимого сообщений (Plaintext / Ciphertext).
- Никаких идентификаторов контактов (Onion-адреса собеседников, Yggdrasil IP, публичные ключи IdentityKey).
- Никаких реальных IP-адресов клиента.
- Никаких долговременных глобальных `device_id` или `user_id`. Для каждой пачки метрик генерируется случайный разовый `batch_id`.
- Никаких меток точного времени до секунд (таймстемпы квантуются до 15-30 минутного окна во избежание traffic-analysis correlation).

---

## 4. Клиентская архитектура (Android)

### 4.1. Сбор метрик транспортов без раскрытия адресов
В ядре приложения отслеживаются факты соединений с маскированием сетевых адресов:

```kotlin
enum class TransportKind {
    DIRECT_P2P,
    TOR_ONION,
    YGGDRASIL
}

data class TransportAttemptEvent(
    val transport: TransportKind,
    val success: Boolean,
    val latencyMs: Long,
    val errorCode: String? = null // "TIMEOUT", "HANDSHAKE_MISMATCH", "UNREACHABLE"
)

object TransportStatsTracker {
    private val p2pSuccess = AtomicInteger(0)
    private val p2pFailures = AtomicInteger(0)
    private val torSuccess = AtomicInteger(0)
    private val torFailures = AtomicInteger(0)
    private val yggSuccess = AtomicInteger(0)
    private val yggFailures = AtomicInteger(0)

    fun recordAttempt(transport: TransportKind, success: Boolean, latencyMs: Long, error: String? = null) {
        when (transport) {
            TransportKind.DIRECT_P2P -> if (success) p2pSuccess.incrementAndGet() else p2pFailures.incrementAndGet()
            TransportKind.TOR_ONION -> if (success) torSuccess.incrementAndGet() else torFailures.incrementAndGet()
            TransportKind.YGGDRASIL -> if (success) yggSuccess.incrementAndGet() else yggFailures.incrementAndGet()
        }
    }
}
```

### 4.2. Crash Resilience (Двухфазная фиксация сбоев)
Сетевые запросы внутри `Thread.UncaughtExceptionHandler` ненадёжны, так как ОС немедленно уничтожает процесс. Мы применяем двухфазный подход:

1. **Фаза 1 (Сбой):** Моментальная запись структурированного отчёта в изолированный локальный файл `crashes/pending_crash.json`. Никаких блокирующих сетевых вызовов.
2. **Фаза 2 (Следующий запуск):** При штатном холодном старте `Application.onCreate` проверяет наличие неотправленного крэша и передаёт его в очередь телеметрии.

```kotlin
class TelemetryCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val crashReport = JSONObject().apply {
                put("timestamp_bucket", System.currentTimeMillis() / (1000 * 60 * 30) * (1000 * 60 * 30))
                put("app_version", BuildConfig.VERSION_NAME)
                put("os_api", Build.VERSION.SDK_INT)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("exception_type", throwable.javaClass.simpleName)
                put("stack_trace", throwable.stackTraceToString().take(4000))
            }
            val crashFile = File(context.filesDir, "pending_crash.json")
            crashFile.writeText(crashReport.toString())
        } catch (_: Throwable) {
            // Защита от вторичного падения
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
```

### 4.3. Очередь и батчинг (`TelemetryCollector`)
- Метрики аккумулируются в памяти (`ConcurrentLinkedQueue`).
- Лимит очереди: не более 500 записей (FIFO, старые отбрасываются при переполнении).
- Отправка: раз в 6 часов или при накоплении 50+ событий, **только если запущен фоновый Tor-демон**.
- Если Tor недоступен, данные не уходят в Clearnet, а остаются в очереди до появления Tor-связи.

### 4.4. Локальный экспорт (Debug Report ZIP)
Для пользователей, находящихся в условиях полного блокирования Tor или желающих лично прикрепить лог в GitHub Issue / тикет:
- Кнопка в настройках: `«Экспортировать диагностический отчёт»`.
- Генерирует локальный `.zip` архив:
  - `crashes.log`
  - `transport_health.json` (анонимизированные счётчики)
  - `device_info.txt`
- Пользователь может просмотреть содержимое архива перед отправкой.

---

## 5. Серверная архитектура (Self-Hosted Stack)

Вся серверная инфраструктура размещается на собственном VPS без сторонних сервисов (без Google Analytics, Sentry или Firebase):

```
                       Tor Network
                            │
                            ▼
              [Tor v3 Hidden Service]
                 (abc123xyz.onion)
                            │
                     localhost:8080
                            ▼
                [Nginx Reverse Proxy]
                  - Rate Limiting (10 req/min per circuit)
                  - Отключение Access Log с IP-адресами
                            │
                            ▼
               [Go Telemetry Ingestion API]
                  - Валидация JSON схемы
                  - Сброс в PostgreSQL
                            │
                            ▼
                      [PostgreSQL]
                            ▲
                            │
                  [Grafana Dashboard]
```

### 5.1. Схема базы данных (PostgreSQL DDL)

```sql
-- Таблица батчей телеметрии
CREATE TABLE telemetry_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_id UUID NOT NULL,
    app_version VARCHAR(32) NOT NULL,
    android_api INTEGER NOT NULL,
    device_model VARCHAR(64),
    received_at TIMESTAMPTZ DEFAULT NOW(),
    event_count INTEGER NOT NULL
);

-- Метрики соединений по типам транспорта
CREATE TABLE transport_metrics (
    id BIGSERIAL PRIMARY KEY,
    batch_id UUID NOT NULL,
    transport_type VARCHAR(16) NOT NULL, -- 'DIRECT_P2P', 'TOR_ONION', 'YGGDRASIL'
    attempts_success INTEGER DEFAULT 0,
    attempts_failed INTEGER DEFAULT 0,
    avg_latency_ms INTEGER DEFAULT 0,
    recorded_hour TIMESTAMPTZ NOT NULL
);

-- Стек-трейсы падений
CREATE TABLE crash_reports (
    id BIGSERIAL PRIMARY KEY,
    batch_id UUID NOT NULL,
    app_version VARCHAR(32) NOT NULL,
    android_api INTEGER NOT NULL,
    device_model VARCHAR(64),
    exception_type VARCHAR(128) NOT NULL,
    stack_trace TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_crashes_version ON crash_reports(app_version);
CREATE INDEX idx_crashes_exception ON crash_reports(exception_type);
CREATE INDEX idx_transport_type_hour ON transport_metrics(transport_type, recorded_hour);
```

### 5.2. Docker-Compose развёртывание (`docker-compose.yml`)

```yaml
version: '3.8'

services:
  tor-gateway:
    image: goldy/tor-hidden-service:latest
    restart: always
    environment:
      TELEMETRY_SERVICE_PORTS: "80:telemetry-api:8080"
    volumes:
      - ./tor_keys/:/var/lib/tor/hidden_service/

  telemetry-api:
    build: ./telemetry-api
    restart: always
    environment:
      - DB_HOST=postgres
      - DB_PORT=5432
      - DB_NAME=telemetry
      - DB_USER=telemetry_user
      - DB_PASSWORD=${DB_PASSWORD}
    depends_on:
      - postgres

  postgres:
    image: postgres:16-alpine
    restart: always
    environment:
      POSTGRES_DB: telemetry
      POSTGRES_USER: telemetry_user
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data

  grafana:
    image: grafana/grafana:latest
    restart: always
    ports:
      - "127.0.0.1:3000:3000"
    volumes:
      - grafana-data:/var/lib/grafana
    depends_on:
      - postgres

volumes:
  pgdata:
  grafana-data:
```

---

## 6. Что мы увидим в Grafana (Операционная польза)

После запуска системы команда разработки получает ответы на ключевые вопросы:

1. **Качество транспортов в реальном мире:**
   - Какой % пользователей успешно поднимает Tor v3 Onion?
   - Какова медианная задержка в Yggdrasil по сравнению с Direct LAN?
   - В каких сетях Direct P2P проваливается из-за симметричного NAT?
2. **Мониторинг крэшей в реальном времени:**
   - Топ-5 исключений после релиза новой версии (например, `v0.0.9.3(1)`).
   - Зависимость сбоев от версии Android (выявление проблем с Background Service на Android 14/15).
3. **Алертинг инвариантов:**
   - Алерт при превышении порога крэшей > 1% от активных установок.
   - Алерт при аномальном росте ошибок протокола (`HandshakeRejected`).

---

## 7. Пошаговый план реализации (4-недельный роадмап)

| Этап | Задачи | Срок | Результат |
|---|---|---|---|
| **Этап 1: Local Diagnostics & Crash Recovery** | • Реализация `CrashStorage` в Android.<br>• Двухфазный перехватчик `TelemetryCrashHandler`.<br>• Добавление `TransportStatsTracker` (P2P/Tor/Ygg).<br>• UI кнопка «Экспорт отчёта (ZIP)» в настройках. | **1 неделя** | Бета-тестеры уже могут отправлять диагностические архивы в 1 клик при обнаружении багов. |
| **Этап 2: Self-Hosted Tor Ingestion Server** | • Написание легковесного Go API (`telemetry-collector`).<br>• Настройка Tor Hidden Service и PostgreSQL в docker-compose.<br>• Проверка приёма тестовых JSON через Tor SOCKS5. | **1 неделя** | Сервер развёрнут на VPS и готов принимать зашифрованные батчи на `.onion`. |
| **Этап 3: Android Client Background Delivery** | • Интеграция `TelemetryCollector` с локальным Tor SOCKS прокси мессенджера.<br>• Очередь с лимитом размера и квантованием таймстемпов.<br>• Экран Opt-In / Настройки приватности телеметрии. | **1 неделя** | Автоматическая доставка метрик и крэшей без вмешательства пользователя. |
| **Этап 4: Grafana Dashboards & Alerting** | • Настройка готовых дашбордов Grafana.<br>• Prometheus/Grafana алерты в Telegram разработчиков при резком всплеске ошибок. | **1 неделя** | Полный мониторинг здоровья бета-версии в реальном времени. |

---

## 8. Заключение и рекомендация

Предложенная архитектура решает задачу автоматизации бета-тестирования без единого компромисса в вопросах приватности:
- **Никаких сторонних вендоров** (Google, Sentry, Crashlytics исключены).
- **Никаких утечек реальных IP** (отправка строго через Tor v3 Hidden Service).
- **Полная видимость работы всех трёх транспортов** (Direct P2P, Yggdrasil, Tor).
- **Полный контроль пользователя** (явный Opt-In, возможность отключения и локального экспорта).

Документ готов к обсуждению с командой и утверждению в качестве архитектурного стандарта 2PChat.
