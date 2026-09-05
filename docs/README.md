# Документация 2PChat

Актуализация: 2026-09-05. Описание реализации сверено с локальными исходниками и конфигурацией сборки; внешние статьи и обещания релизной поддержки не использовались как подтверждение состояния кода.

Основной Android-клиент — `2pchatGO/android`. Предыдущее дерево `2PChat android/android` сохраняется для совместимости. Python-код — `messenger/`; каталога `webui/` нет.

Правила разработки описывают требования. ADR могут содержать целевую политику, которая ещё не реализована в CI. Исторические отчёты сохраняют выводы своих проверок; номера строк, счётчики тестов и слова «исправлено» относятся к тому срезу. Они не заменяют повторную проверку текущего кода.

## Проверка при актуализации

- Зависимости установлены из `messenger/requirements.txt` в `.venv`.
- Полный `python -m pytest`: **209 passed, 8 skipped, 1 warning**, 66.46 с; Windows, Python 3.10.11.
- Восемь пропусков — opt-in проверки внешних HTTP/UDP-трекеров. Предупреждение — Starlette/FastAPI о deprecated использовании `httpx` в TestClient.
- Android/Go сборки и инструментальные тесты в рамках этой актуализации не запускались; команды сверены с конфигурацией, прежние результаты не представлены как новые.
- Удалены семь служебных `.DS_Store`, временный `__tmp_ygg_classes.jar` и снимок UI `tools/temp-ui.xml`; добавлены правила игнорирования артефактов инспекции.
- Дубли RULES, SECURITY и GROUP_CHAT_PROTOCOL заменены ссылками на канонические файлы. Пользовательские ключи, настройки, медиа и локальные журналы сохранены.
- В пакете анимационных инструкций отсутствуют `AUDIT.md` и `PLAN-TEMPLATE.md`; это отмечено в самом документе. В layout-инструкции также отсутствуют два companion-файла. Их содержимое не восстановлено из предположений.

## Руководства и протоколы

- [README.md](../README.md)
- [2PCHAT_TECHNICAL_SPECIFICATION.md](../2PCHAT_TECHNICAL_SPECIFICATION.md)
- [messenger/README.md](../messenger/README.md)
- [messenger/ROADMAP.md](../messenger/ROADMAP.md)
- [2pchatGO/android/README.md](../2pchatGO/android/README.md)
- [docs/PROTOCOL.md](../docs/PROTOCOL.md)
- [docs/GROUP_CHAT_PROTOCOL.md](../docs/GROUP_CHAT_PROTOCOL.md)
- [docs/ANDROID_INTEGRATION.md](../docs/ANDROID_INTEGRATION.md)
- [docs/P2P_SIGNAL_ADAPTATION.md](../docs/P2P_SIGNAL_ADAPTATION.md)

## Правила и решения

- [AGENTS.md](../AGENTS.md)
- [RULES.md](../RULES.md)
- [SECURITY.md](../SECURITY.md)
- [2PChat android/GEMINI.md](../2PChat%20android/GEMINI.md)
- [2pchatGO/android/docs/ADR_001_PRIMARY_ANDROID_TREE.md](../2pchatGO/android/docs/ADR_001_PRIMARY_ANDROID_TREE.md)
- [2pchatGO/android/docs/ADR_002_JNI_BRIDGE_SAFETY_CONTRACT.md](../2pchatGO/android/docs/ADR_002_JNI_BRIDGE_SAFETY_CONTRACT.md)
- [2pchatGO/android/docs/ADR_003_TESTING_AND_CI_REPRODUCIBILITY.md](../2pchatGO/android/docs/ADR_003_TESTING_AND_CI_REPRODUCIBILITY.md)

## Исторические отчёты

- [2pchatGO/android/FULL_PROJECT_AUDIT_REPORT.md](../2pchatGO/android/FULL_PROJECT_AUDIT_REPORT.md)
- [2pchatGO/android/docs/SECURITY_TRANSPORT_OVERHAUL_REPORT.md](../2pchatGO/android/docs/SECURITY_TRANSPORT_OVERHAUL_REPORT.md)
- [2pchatGO/android/docs/remediation/M1_M2_M3_SUMMARY.md](../2pchatGO/android/docs/remediation/M1_M2_M3_SUMMARY.md)
- [2pchatGO/android/docs/remediation/h1.md](../2pchatGO/android/docs/remediation/h1.md)
- [2pchatGO/android/docs/remediation/h1_inventory.md](../2pchatGO/android/docs/remediation/h1_inventory.md)
- [2pchatGO/android/docs/remediation/m1.md](../2pchatGO/android/docs/remediation/m1.md)
- [2pchatGO/android/docs/remediation/m2.md](../2pchatGO/android/docs/remediation/m2.md)
- [2pchatGO/android/docs/remediation/m3.md](../2pchatGO/android/docs/remediation/m3.md)
- [docs/ANDROID_AUDIT_REMEDIATION.md](../docs/ANDROID_AUDIT_REMEDIATION.md)
- [docs/GROUP_PORT_AUDIT_2026-09-05.md](../docs/GROUP_PORT_AUDIT_2026-09-05.md)
- [docs/MOBILE_UI_UX_REDESIGN_REPORT.md](../docs/MOBILE_UI_UX_REDESIGN_REPORT.md)

## Планы

- [plans/001-motion-enhancements.md](../plans/001-motion-enhancements.md)
- [plans/002-motion-audit-improvements.md](../plans/002-motion-audit-improvements.md)
- [plans/003-bug-list.md](../plans/003-bug-list.md)
- [plans/README.md](../plans/README.md)

## Совместимые точки входа

- [2pchatGO/android/RULES.md](../2pchatGO/android/RULES.md)
- [2pchatGO/android/SECURITY.md](../2pchatGO/android/SECURITY.md)
- [2pchatGO/android/docs/GROUP_CHAT_PROTOCOL.md](../2pchatGO/android/docs/GROUP_CHAT_PROTOCOL.md)

## Локальные инструкции агентов

- [.agents/skills/README.md](../.agents/skills/README.md)
- [.agents/skills/2pchat-rules/RULES.md](../.agents/skills/2pchat-rules/RULES.md)
- [.agents/skills/SECURITY_TESTING_SKILL/skill.md](../.agents/skills/SECURITY_TESTING_SKILL/skill.md)
- [.agents/skills/SKILL (2).md](../.agents/skills/SKILL%20%282%29.md)
- [.agents/skills/SKILL.md](../.agents/skills/SKILL.md)
- [.agents/skills/SKILLanim.md](../.agents/skills/SKILLanim.md)
- [.agents/skills/mobile-app-ui-design-main/INDEX.md](../.agents/skills/mobile-app-ui-design-main/INDEX.md)
- [.agents/skills/mobile-app-ui-design-main/README.md](../.agents/skills/mobile-app-ui-design-main/README.md)
- [.agents/skills/mobile-app-ui-design-main/SKILL.md](../.agents/skills/mobile-app-ui-design-main/SKILL.md)
- [.agents/skills/mobile-app-ui-design-main/references/industry-conventions.md](../.agents/skills/mobile-app-ui-design-main/references/industry-conventions.md)

