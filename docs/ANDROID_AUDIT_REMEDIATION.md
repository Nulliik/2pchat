# Проверка Android-аудита

Проверено по текущей ветке, а не по номерам строк из исходного отчёта.

| Пункт | Результат проверки | Действие |
|---|---|---|
| P2P-1 | Частично устарел: relay уже делегировал отправку, maintenance, avatar cache и notifications отдельным классам | Границы компонентов сохранены и расширены; LAN discovery и foreground lifecycle вынесены отдельно |
| P2P-2 | Подтверждён как долг по сопровождаемости, но не как самостоятельный runtime-баг | Состояние и media/attachment composables уже разделены; дальнейшее дробление большого layout следует делать отдельными UI-изменениями со screenshot tests |
| P2P-3 | Подтверждён для сообщений, ввода, reply/edit/selection | Добавлен `ChatScreenViewModel`; важное состояние переживает configuration change |
| AND-1 | Уже исправлен до аудита | История загружается через `Dispatchers.IO`; остальные DB writes переведены в background helper |
| AND-2 | Подтверждён | Bitmap и временные потоки освобождаются в `finally`/`use` |
| AND-3/4 | Формулировка аудита некорректна | SharedPreferences хранит небольшие metadata, сообщения находятся в SQLCipher; `remember` кэшировал handle, а не заявлял реактивность данных |
| SEC-1 | Рекомендация неприменима к текущему background-first threat model | Authentication-bound DB key сделал бы foreground receiver неспособным открыть очередь при заблокированном экране; root-компрометацию это само по себе не устраняет |
| SEC-2 | Подтверждён | Peer avatars теперь AES-GCM encrypted at rest, имеют hash-based filenames и мигрируются с legacy JPEG |
| SEC-3 | Исходный вывод неверен: `encrypted.db` уже был зашифрован | Temporary DB перенесена рядом с destination, гарантирована очистка в `finally` |
| SEC-4 | Не воспроизведён | Relay логирует размер сообщения, не body; ошибки записи логов больше не подавляются молча |
| SEC-7 | Низкоприоритетное ограничение API SQLCipher | Passphrase неизбежно материализуется для `getWritableDatabase`; изменение типа кэша не даёт надёжной очистки копий JVM String |
| NET-1 | Устарел | Уже используются несколько tracker-ов, Mainline DHT и persisted last endpoint |
| NET-2 | Подтверждён | Порт редактируется в Settings и listener безопасно перезапускается |
| NET-3 | Подтверждён | Добавлена DNS-SD/NSD публикация и discovery для ранее аутентифицированных контактов |
| NET-4 | Подтверждён | Ping/pong RTT отображается рядом с transport |
| CQ-1 | Подтверждён частично | UI больше не парсит file/reply повторно; relay передаёт единый структурированный `Message` с исходным ID |
| CQ-2 | Подтверждён | Добавлены канонические relay keys и генераторы per-peer keys |
| CQ-4 | Подтверждён в затронутых путях | Ошибки relay/DB теперь получают контекстное логирование |
| QA-1 | Уже исправлен до аудита | Reply callback использовал `replyText` |
| QA-2 | Подтверждён | Неиспользуемый `isMe` удалён из `updateMessageText` |
| QA-3 | Подтверждён | Zoom state теперь привязан к page, pager разблокируется при смене страницы |
| QA-4 / CL-1 | Подтверждены | Edit сохраняется в encrypted control queue до `edit_ack` и повторяется после reconnect |
| CL-2 | Подтверждён | Read receipts сохраняются в control queue, в том числе без текущего endpoint |
| CL-3 | Подтверждён | Active chat хранится в `AtomicReference` |
| CL-4 | Не дефект существующего single-device Saved Messages | Multi-device sync требует отдельного протокола устройств и модели владения ключами |
| OS-1 | Подтверждён | P2P listener теперь принадлежит sticky foreground service и запускается после boot |

Дополнительно исправлена рассинхронизация message ID: раньше active UI создавал новый ID для
входящих text/reply и отправлял read receipt не для того ID, который был передан отправителем.
