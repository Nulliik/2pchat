# 2PChat: архитектура и техническая спецификация

Сверено с исходниками 2026-09-05. Точный wire-формат описан в [PROTOCOL.md](docs/PROTOCOL.md) и [GROUP_CHAT_PROTOCOL.md](docs/GROUP_CHAT_PROTOCOL.md).

## Реализации

- `messenger/`: Python CLI, Kivy GUI, FastAPI backend; общий код криптографии, discovery, сессий и транспортов.
- `2pchatGO/android/`: основной Android-клиент на Kotlin/Compose. Сетевые сессии и криптографические операции Go доступны через CGO/JNI (`core-go/cmd/lib2pcore`, `NativeBridge`).
- `2PChat android/android/`: предыдущий клиент с Chaquopy. Gradle генерирует Python-источники из корневого `messenger/`.
- Групповые runtime, ACL, хранение и UI находятся в Kotlin в обоих Android-деревьях. Перенос на Go заменил bridge криптографии/транспорта, а не весь групповой runtime.

## Обнаружение и транспорт

Discovery отделено от аутентификации. Кандидаты маршрутов поступают из трекеров, локального обнаружения и сохранённых адресов; Android также содержит интеграции Tor и Yggdrasil. Доступность зависит от политики контакта, NAT и состояния сети. Задержки обнаружения и прохождение любого NAT не гарантируются.

Python registry содержит `direct`, `tor`, `ygg`, `ygg-embedded`; `tor` использует DirectTransport с настройками прокси, поэтому одно название схемы не означает запуск Tor. См. `messenger/core/transport_manager.py` и `transport_direct.py`.

Ключ tracker lookup: `SHA-1("2pchat-rendezvous-v1:" + normalized_nickname + ":" + shared_code)`. Он служит для поиска маршрута и не заменяет проверку identity.

## Личные сессии

Python reference использует X25519 identity для fingerprint и Ed25519 для подписи prekey и transcript. Текущий handshake v3 выполняет live X3DH-style bootstrap, затем шифрование Double Ratchet packet v4. Совместимость с handshake v2 описана отдельно в протоколе.

Fingerprint по умолчанию — Base64 публичного X25519-ключа. TOFU и проверка QR/текста помогают закрепить контакт; первая непроверенная связь сама по себе не доказывает личность человека.

Framing: 4-байтовая big-endian длина и payload. Application frames включают сообщения, ACK, статусы и передачу файлов. Повторы и outbox обеспечивают восстановление доставки после разрыва; ACK не равен прочтению.

## Группы

Используется журнал подписанных событий, HLC/author sequence, SQLCipher-проекции, durable outbox и anti-entropy. Шифрование эпох — AES-256-GCM, подписи — Ed25519; suite v2 добавляет привязки control/roster. Это собственный протокол, **не MLS**. Секрет эпохи позволяет расшифровать доступные ciphertext этой эпохи.

Реализованы роли, ограничения, приглашения, текст, reply/edit/delete, реакции, pin, опросы, вложения, typing и mute. В Go-дереве схема `twopchat-groups.db` — v7; в Chaquopy-дереве — v6. Версия SQLCipher-зависимости и версия схемы — разные величины.

До 32 получателей обычные события доставляются напрямую; для больших групп используются три HRW-реплики и до трёх соседей кольца. Serialized membership control сохраняет полный fan-out. Вложения разбиваются на блоки по 512 КиБ. Подробные лимиты и ограничения — в групповом протоколе.

## Хранение и границы безопасности

Android хранит сообщения в SQLCipher и использует отдельные механизмы защиты ключей и медиа. Python identity/trust/outbox находятся в локальном конфигурационном каталоге; нельзя переносить на desktop гарантии Android Keystore или SQLCipher без проверки конкретного пути хранения.

Шифрование содержимого не скрывает все сетевые метаданные и не защищает скомпрометированное конечное устройство. Отсутствие центрального сервера сообщений не означает отсутствие внешней инфраструктуры discovery/overlay.

## Сборка и проверка

См. [корневой README](README.md), [Android README](2pchatGO/android/README.md) и [карту документации](docs/README.md). Исторические отчёты фиксируют отдельные проверки и не являются доказательством текущей готовности релиза.
