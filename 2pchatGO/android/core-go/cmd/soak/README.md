# 2PChat Core Soak Harness

Непрерывное тестирование Go-ядра 2PChat с реалистичной нагрузкой для обнаружения
утечек памяти, утечек goroutines, потерь сообщений и деградации производительности.

## Обзор

Харнес создаёт N изолированных виртуальных нод, каждая со своим identity,
Double Ratchet-сессиями и group succession state. Ноды общаются через
реальный TCP loopback, используя **настоящий** код Go Core (не моки).

**Цели:**
- Утечки памяти (RSS, heap)
- Утечки goroutines и file descriptors
- Потери/дубликаты сообщений
- Деградация outbox-дренажа
- Race conditions (в сочетании с `go test -race`)
- Поведение при network flap и clock skew

## Quick Start

```bash
cd 2pchatGO/android/core-go

# Smoke test: 1 минута, 2 ноды
go run ./cmd/soak --duration 1m --nodes 2 --scenarios pairwise

# Medium: 1 час, 8 нод
go run ./cmd/soak --duration 1h --nodes 8 --scenarios pairwise,group

# Full nightly: 6 часов, 12 нод
go run ./cmd/soak --duration 6h --nodes 12 \
  --scenarios pairwise,group,succession,outbox \
  --flap-rate 0.1 --clock-skew
```

## CLI

```
Usage: go run ./cmd/soak [flags]

Flags:
  -duration duration    Soak duration (default 6h)
  -nodes int            Number of nodes (default 12)
  -scenarios string     Comma-separated: pairwise,group,succession,outbox
                        (default "pairwise,group,succession")
  -flap-rate float      Network flap rate per minute 0.0–1.0 (default 0.1)
  -clock-skew           Enable ±5min clock skew on 30% of nodes (default true)
  -report string        Report output path (default "soak-report.json")
```

## Сценарии нагрузки

### `pairwise`
Непрерывная отправка 1-to-1 сообщений между случайными парами.
**Нагрузка:** ~10 msg/sec.  
**Проверяет:** Double Ratchet стабильность, ratchet state, outbox при peer offline.

### `group`
Создание групп, отправка групповых широковещательных сообщений.  
**Нагрузка:** ~3 msg/sec.  
**Проверяет:** Group wire protocol, delivery broadcast, envelope integrity.

### `succession`
Создание группы с succession-сертификатом, периодические heartbeat-события.  
**Нагрузка:** 1 heartbeat каждые 10 минут (или 10 сек в smoke).  
**Проверяет:** Succession certificates, heartbeat chain, claim verification.

### `outbox`
Send сообщений при offline peer, затем reconnect и дренаж очереди.  
**Нагрузка:** burst + reconnect.  
**Проверяет:** Outbox persistence, message ordering after reconnect.

## Network Flap

Флаг `--flap-rate 0.1` означает 10% вероятность kill/restart одной случайной
ноды в минуту. Down-период 5–30 секунд.

**Что проверяется:**
- Reconnection после kill
- Session state recovery
- Outbox drain после reconnect
- Split-brain resilience

## Clock Skew

Флаг `--clock-skew` применяет проверку устойчивости к расхождению часов.

**Что проверяется:**
- Timestamp tolerance в succession heartbeat (5 min window)
- Discovery record TTL validation
- Group event ordering

## Метрики и критерии провала

### Собираемые метрики (каждые 10 секунд)
- RSS (Linux: `/proc/self/status`, fallback на macOS)
- Heap allocated, heap objects
- Goroutines
- Open file descriptors (Linux: `/proc/self/fd`)
- Sent/received messages per session
- Message duplicates
- Outbox size
- Panics, race warnings

### Критерии PASS/FAIL

| Критерий | Лимит |
| :--- | :--- |
| RSS growth | ≤ 10 MB/hour |
| Goroutine growth | ≤ 5% per hour |
| FD growth | ≤ 5 per hour |
| Message loss | 0 |
| Message duplication | 0 |
| Panics | 0 |
| Races | 0 |

Если любой критерий нарушен — процесс завершается с кодом 1.

## Output

После завершения генерируется два файла:
- `soak-report.json` — полный отчёт с историей метрик
- `soak-report.md` — человекочитаемый summary с verdict

## CI Integration

Харнес запускается автоматически в `.github/workflows/soak-nightly.yml`:
- **Nightly (2:00 UTC):** `--duration 6h --nodes 12`
- **Weekly (воскресенье):** `--duration 7d --nodes 12 --flap-rate 0.2`

Отчёты сохраняются как GitHub Actions artifacts.
