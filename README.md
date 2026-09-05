# 2PChat

P2P-мессенджер: Android-клиент на Kotlin/Jetpack Compose с Go-ядром и Python-клиент для настольных систем. Сообщения передаются через аутентифицированные зашифрованные сессии; обнаружение пиров и доступность маршрута зависят от выбранной сети.

## Структура репозитория

| Каталог | Назначение |
| --- | --- |
| [2pchatGO/android](2pchatGO/android/README.md) | Основной Android-клиент, цель релизного CI; Go-ядро в `core-go/` |
| [messenger](messenger/README.md) | Python: CLI, Kivy GUI, FastAPI, протокол и тесты совместимости |
| [2PChat android](2PChat%20android/GEMINI.md) | Предыдущее Android-дерево с Chaquopy; сохраняется для совместимости |
| [docs](docs/README.md) | Протоколы, интеграция и отчёты |
| [plans](plans/README.md) | Исторические планы и список замечаний |
| `scripts/` | Сборка desktop и публикация релизов |
| `tools/` | Android E2E и вспомогательные инструменты |
| [.agents/skills](.agents/skills/README.md) | Локальные инструкции для агентов |

## Запуск Python-клиента

Команды выполняются из корня репозитория. В Windows после создания окружения используйте `.venv\Scripts\python.exe` вместо `python`, либо активируйте окружение.

```sh
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r messenger/requirements.txt
.venv/Scripts/python.exe -m messenger.app.kivy_gui
```

Установку и запуск выполняйте Python из созданного окружения. На POSIX его путь — `.venv/bin/python`.

CLI: `python -m messenger.app.cli_chat --help`. Backend: `python -m messenger.app.web_api`.
Каталога `webui/` в репозитории нет; `web_launcher` требует его наличия и сейчас не является готовой командой запуска браузерного клиента.

## Проверки

```sh
python -m pip install -r messenger/requirements.txt
python -m pytest
```

`setup.cfg` задаёт полный Python-набор в `messenger/tests`. Проверки внешних трекеров включаются отдельно переменной `P2PCHAT_RUN_LIVE_TRACKER_TESTS=1`.
Go и Android проверяются отдельно по [инструкции сборки](2pchatGO/android/README.md).

## Документация

- [Карта документов и результаты проверки](docs/README.md)
- [Архитектура](2PCHAT_TECHNICAL_SPECIFICATION.md)
- [Pairwise-протокол](docs/PROTOCOL.md) и [групповой протокол](docs/GROUP_CHAT_PROTOCOL.md)
- [Roadmap](messenger/ROADMAP.md)
- [Правила разработки](RULES.md), [инструкции агента](AGENTS.md), [политика безопасности](SECURITY.md)

Лицензия: [MIT](LICENSE.txt).
