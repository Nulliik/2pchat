# Групповые чаты 2PChat: фактическая Android-реализация

Сверено 2026-09-05. Основная область документа — `2pchatGO/android`; различия предыдущего Chaquopy-клиента указаны отдельно.

- версия JSON wire-протокола: `1`;
- версия отдельной SQLCipher-схемы групп: `7` в Go-клиенте, `6` в Chaquopy-клиенте;
- транспорт: существующие аутентифицированные pairwise-сессии 2PChat;
- модель доставки: реплицируемый журнал событий, durable outbox и anti-entropy;
- криптографические suites: `2pchat-epoch-aes256gcm-ed25519-v2` и совместимость с v1.

Документ описывает текущий код, а не желаемую архитектуру. В частности, это
уже не только набор моделей: `GroupChatCoordinator` обрабатывает групповые
кадры в `P2PMessageRelay`, сохраняет их, применяет ACL, обновляет UI, повторяет
доставку и запускает синхронизацию через WorkManager.

## 1. Критически важное ограничение: это не MLS

Текущая реализация **не является RFC 9420 MLS**.

Она использует:

- общий случайный 32-байтовый секрет на эпоху членства;
- AES-256-GCM со случайным 96-битным nonce для каждого события;
- Ed25519-подпись канонического envelope;
- раздачу нового секрета оставшимся устройствам внутри существующих
  аутентифицированных pairwise Double Ratchet-сессий.

В ней нет TreeKEM, MLS KeyPackage, Proposal, Commit, Welcome, ratchet tree,
transcript hash, update path и MLS state machine. Поэтому она не обеспечивает
определённые MLS свойства forward secrecy и post-compromise security (PCS).
Компрометация секрета эпохи позволяет прочитать доступные атакующему
ciphertext этой эпохи. Обычный rekey после удаления участника закрывает ему
будущие эпохи, но не восстанавливает безопасность уже скомпрометированного
активного endpoint.

Название модели `MLS_COMMIT` является только зарезервированным значением и не
означает наличие MLS. UI, документация и capability negotiation не должны
называть текущий suite MLS.

При будущей миграции на RFC 9420 можно сохранить внешний журнал,
content-addressed вложения, outbox и anti-entropy. Заменить необходимо
криптографическое состояние группы, приглашения и membership transitions:
сырой `epoch_secret` должен уступить место MLS KeyPackage/Welcome/Commit, а
состояние эпохи — храниться и изменяться проверенной MLS-библиотекой.

## 2. Область применения

Текущий режим предназначен для приватных групп между уже известными
контактами, у которых 2PChat установил и закрепил pairwise identity.

Реализованы:

- создание группы и приглашение контактов;
- принятие и отклонение приглашения;
- текст, ответы, редактирование и удаление сообщений;
- реакции, pin/unpin и read receipts;
- файлы как зашифрованные content-addressed блоки;
- роли владельца, администратора, модератора и участника;
- индивидуальные ограничения, удаление, ban и безопасный rejoin по повторному
  приглашению;
- передача владения с инвариантом «ровно один владелец»;
- изменение названия и описания;
- локальный журнал административных действий;
- offline-first отправка, retry, ACK, синхронизация после reconnect;
- уведомления, счётчик непрочитанных и cursor-пагинация истории;
- приглашения по capability-ссылке, опросы, typing presence и локальный mute.

Не реализованы как законченные пользовательские функции:

- RFC 9420 MLS;
- публичный каталог групп и username join;
- настоящий multi-device account: `device_id` сейчас детерминирован от одного
  transport fingerprint;
- topics/threads и broadcast channels;
- серверная или иерархическая инфраструктура для сотен тысяч участников;
- Byzantine-safe согласование при злонамеренном fork владельца.

## 3. Компоненты

| Компонент | Фактическая ответственность |
| --- | --- |
| `group.model` | роли, permissions, HLC-модель, ACL, retry и HRW replica planner |
| `group.crypto` | epoch AES-256-GCM и bridge к Ed25519 identity |
| `group.protocol` | bounded JSON v1 для событий, приглашений, control, sync, roster и вложений |
| `group.storage` | отдельная SQLCipher БД, event log, проекции, outbox, receipts, invites и cursors |
| `group.attachments` | chunking, AES-GCM блоков, CID, проверка и атомарная сборка файла |
| `group.runtime` | reducer, owner-serializer, relay routing, recovery, anti-entropy, UI state и WorkManager |
| `group.ui` | создание группы, pending invites, timeline, вложения и управление участниками |

Основной поток данных:

```text
Compose UI
   |
   v
GroupChatCoordinator
   |---- SQLCipher event log + materialized views + durable outbox
   |---- encrypted content-addressed attachment blocks
   |
   v
P2PMessageRelay -> authenticated pairwise session -> peer
   ^
   |
WorkManager / peer reconnect / manual retry
```

Групповой кадр перехватывается до legacy-роутера личного чата. Даже
повреждённый кадр с известным group `type` не отображается как обычный текст.
Общий предел проверяется до асинхронной обработки.

## 4. Идентичность и граница доверия

Runtime различает:

- transport fingerprint — закреплённая identity pairwise-сессии;
- `device_id` — `SHA-256("2pchat-group-device-v1\0" + fingerprint)`;
- `peer_name` — транспортное имя контакта;
- Ed25519 verification key — ключ подписи событий устройства.

Локальный Ed25519 private key обслуживается криптографическим bridge: Go NativeBridge в основном клиенте, Python identity в Chaquopy-клиенте. Kotlin получает verification key и вызывает bridge для подписи.

Для входящего события недостаточно ключа из самого envelope. Runtime находит
автора в принятом roster и требует одновременно:

1. чтобы fingerprint фактической входящей pairwise-сессии точно соответствовал
   ровно одному participating member группы;
2. совпадение fingerprint и `author_device_id` автора с roster;
3. совпадение wire signing key с закреплённым roster key;
4. корректную Ed25519-подпись.

Для определения отправителя transport-имя не используется как запасная
identity: по `peer_name` извлекается уже закреплённый fingerprint, после чего
ищется единственный participating member с **точно тем же** fingerprint.
Отсутствующая, неоднозначная или конфликтующая привязка отклоняет весь
групповой кадр.

Исходящий маршрут также строгий. У roster member должны быть непустые
`peer_name` и fingerprint, а `device_id` должен равняться детерминированному
hash этого fingerprint. Если локальной transport-привязки ещё нет, runtime
закрепляет fingerprint из подписанного roster и запоминает peer name. Если
привязка уже существует и отличается, отправка запрещается; fallback только по
совпадению `peer_name` отсутствует.

Это сейчас модель «одна identity — одно устройство». Для настоящего
multi-device нужны отдельные device credentials, независимые device keys и
явное добавление/отзыв каждого устройства.

## 5. Wire-протокол v1

Все кадры — bounded JSON и идут внутри уже аутентифицированного pairwise
канала.

| `type` | Назначение | Независимая подпись |
| --- | --- | --- |
| `group_event_v1` | зашифрованное приложение или control event | Ed25519 автора |
| `group_invite_v1` | приглашение и секрет текущей эпохи | Ed25519 владельца |
| `group_invite_response_v1` | accept/decline | Ed25519 приглашённого |
| `group_key_package_v1` | секрет новой legacy-эпохи получателю | Ed25519 владельца |
| `group_roster_snapshot_v1` | страница полного активного roster | Ed25519 владельца |
| `group_store_ack_v1` | подтверждение durable ingest | только pairwise-аутентификация |
| `group_sync_request_v1` | per-author cursors | только pairwise-аутентификация |
| `group_sync_batch_v1` | до 100 сохранённых wire events | события подписаны отдельно |
| `group_attachment_request_v1` | запрос CID для конкретного media event | только pairwise-аутентификация |
| `group_attachment_block_v1` | один opaque ciphertext block | CID + pairwise-аутентификация |

ACK, sync и attachment frames не имеют собственной переносимой подписи. Их
можно принимать только из закреплённой pairwise-сессии ожидаемого участника.
Событие сохраняет независимую подпись и может безопасно реплицироваться
другими участниками.

## 6. Envelope события

`group_event_v1` содержит:

- `group_id`, `event_id`, `epoch`, `kind`;
- fingerprint, device ID и signing key автора;
- монотонный `author_sequence` и `previous_author_event`;
- `control_head`, относительно которого событие было создано;
- физическую и логическую части HLC;
- необязательный `target_event_id`;
- AES-GCM nonce и ciphertext;
- suite, Ed25519 signature и необязательный expiry.

`event_id` — SHA-256 канонического unsigned envelope. Parser пересчитывает его
и отклоняет несовпадение. Подпись покрывает все значимые открытые поля,
nonce, ciphertext, suite и expiry.

AES-GCM AAD v1 связывает ciphertext с:

- группой;
- эпохой;
- типом события;
- device ID и sequence автора;
- control head.

### 6.0. Hardening Фазы 1 (Suite v2, Roster Hash, Интервалы, Tombstone, Безопасный транспорт)

В рамках протокольного обновления безопасности (Фаза 1) зафиксированы следующие обязательные инварианты:

1. **Криптографический suite v2 (`2pchat-epoch-aes256gcm-ed25519-v2`) и Rollout**:
   - `suite` является неизменяемым структурным атрибутом эпохи и сохраняется в таблице `group_epoch_keys(group_id, epoch, key_material, suite)`.
   - В `receiveEventLocked` выполняется обязательная структурная проверка:
     `require(event.cryptoSuite == epoch.suite) { "event suite does not match epoch suite" }`
     Эпоха со сьюитом v2 безусловно отклоняет любые события со сьюитом v1 (защита от downgrade).
   - В AAD v2 включается канонический `roster_hash`:
     $$\text{AAD}_{v2} = \text{"2pchat-group-aad-v2\n"} \parallel groupId \parallel epoch \parallel kind \parallel authorDeviceId \parallel authorSequence \parallel controlHead \parallel rosterHash$$
   - Канонический `roster_hash` вычисляется как SHA-256 от лексикографически отсортированного списка `deviceId:signingKey` всех участников со статусом `ACTIVE` и `RESTRICTED` (исключая `LEFT` и `BANNED`).
   - Эпоха 0 фиксирует `roster_hash` начального ростера в событии создания группы.
   - **Rollout-политика и wire capability**: участники анонсируют capability `supports_v2` в виде подписанного поля в accept-ответе на приглашение (`invite_response`) либо отдельного подписанного capability-кадра. Владелец откладывает ротацию группы на v2, пока все активные участники не заявят `supports_v2`.
   - **Owner Override**: если вечно-оффлайн участник без флага блокирует v2, владелец имеет явный UI-override («Ротировать принудительно, участник X потеряет доступ к новым сообщениям»), исключающий блокировку прогресса безопасности группы.

2. **Owner-Serializer и wire-flow предложений (Grandfathering v1)**:
   - **Grandfathering привязан к suite эпохи**: пока владелец не провёл ротацию группы на сьюит v2 (текущая эпоха имеет `suite = v1`), администраторы (включая обновлённые клиенты) продолжают создавать v1-эпохи по прежним правилам. Это предотвращает расхождение состояния в смешанных группах.
   - Известное ограничение (residual risk): группа, в которой владелец никогда не обновляется, остаётся на сьюите v1 со всеми присущими v1 ограничениями.
   - **Wire-flow для v2-эпох**: исключение участника администратором отправляется как `MEMBER_REMOVAL_PROPOSED` (`member_removal_proposed`). Владелец при получении ратифицирует предложение, выпуская каноническое событие `MEMBER_REMOVED` и новый `GroupEpochKeyPackage`. Если владелец оффлайн, предложение ожидает его возвращения в сеть (известное ограничение Фазы 1).
   - Подпись `GroupEpochKeyPackage` верифицируется строго по `signingKey` владельца на момент `controlHead` из ростера БД, а не по ключу, присланному внутри пакета.
   - Сверка `rosterHash`: выполняется **только после** локального применения `controlHead`. Несовпадение ростера при совпадении `controlHead` трактуется как раскол группы владельцем (equivocation).

3. **Интервалы членства как проекция (`group_membership_intervals`)**:
   - Интервалы членства строятся и перестраиваются **строго редьюсером** (`applyControlMutation` / `rebuildProjections`).
   - Начальный ростер эпохи 0 получает интервал `[0, NULL)`.
   - **Семантика границы**: Control-событие в эпохе $E$ с `next_epoch = E+1` закрывает интервал исключённого участника как $[joined, E+1)$. Сообщения автора из эпохи $E$ принимаются, а сообщения из эпохи $E+1$ и последующих — отклоняются.
   - Закрывающие статусы членства в коде: `LEFT` (добровольный выход) и `BANNED` (бан/исключение администратором).
   - **Out-of-order доставка и buffering (Правило 1.2)**: если входящее событие не попадает в активный интервал (`!isMemberActiveAtEpoch`):
     - Если `event.controlHead` ещё **не применён локально** (сообщение нового участника пришло раньше `MEMBER_ADDED` через HRW/sync) — событие **буферизуется** в `pendingEpochEvents` (лимит 50 на группу), а не отклоняется.
     - Если `event.controlHead` уже применён локально (интервалы для этой точки истории окончательны) — событие безусловно отклоняется (`SecurityException`).
   - Входящие события будущих эпох (`event.epoch > currentEpoch`) также буферизуются в `pendingEpochEvents` (лимит 50 на группу).
   - Запрос `isMemberActiveAtEpoch` использует локальный in-memory кэш на время обработки батча, инвалидируемый control-событиями.

4. **Tombstone при удалении сообщений и привязка к `DELETE`**:
   - `event_id` при первичном приёме события **всегда строго перевычисляется** как `SHA-256(canonical unsigned envelope + nonce + ciphertext + signature)` и не доверяется из wire.
   - При затирании события (tombstone) `body` обнуляется (`""`), `payload` устанавливается в `NULL`, выставляется флаг `is_tombstoned = 1`. Исходный `event_id` (первичный ключ) сохраняется неизменным, поэтому хеш-цепочка `previous_author_event` для последующих сообщений автора остаётся валидной.
   - **Криптографическая привязка tombstone к `DELETE` (Дефект 1.1)**: событие `DELETE`, подписанное удаляющим (который видел оригинал), включает заголовок цели:
     `target_event_id, target_author_device_id, target_author_sequence, target_previous_author_event, target_hlc_physical_ms, target_hlc_logical`.
     При приёме tombstone-плейсхолдера от реплики все поля заголовка плейсхолдера **обязаны точно совпадать** с полями из авторизующего `DELETE`. В противном случае tombstone отклоняется как неподтверждённый.
   - **DELETE раньше оригинала**: если событие `DELETE` прибыло до оригинала (out-of-order), оригинальное сообщение затирается **в момент прибытия** (tombstoned on arrival) и никогда не попадает в БД в открытом виде.
   - **Область затирания**: затираются `group_events` (payload=NULL, body="", is_tombstoned=1), проекция `group_messages` (body="", deleted=true), поисковые индексы, кэш уведомлений и превью последнего сообщения.
   - Вложения: зашифрованные блоки чанков удаляются с диска только при reference count = 0 по всей базе данных.

5. **Изоляция транспорта и превентивная блокировка Clearnet**:
   - Контакты из группы получают `peer_source = "group:<groupId>"` в БД `peers`.
   - **Превентивная блокировка входящих (G-03)**: в момент применения `MEMBER_ADDED` до подтверждения пользователем политика для нового пира сразу устанавливается в Go Core (`setPeerPolicy = deny-clearnet`), чтобы входящие clearnet-подключения от пира, нашедшего локальное устройство через DHT/трекер, отбрасывались.
   - Исходящий Clearnet-набор к новому участнику из группы блокируется до явного подтверждения пользователя.
   - `TOR_ONLY` побеждает всё: если группа требует Tor, сессия пира переводится в `TOR_ONLY` по всем контекстам (включая личный чат).
   - Атрибут `torOnlyGroup` задаётся владельцем через `GROUP_UPDATED` и является **монотонным**: ослабление `true -> false` безусловно отклоняется редьюсером.

6. **Outbox и запрос ключа (`group_key_request_v1`)**:
   - В outbox хранится **намерение отправки** (plaintext, kind, reply-to), шифрование и нумерация sequence происходят в момент дренажа под актуальную эпоху.
   - Если автор намерения к моменту дренажа был исключён из группы, намерение удаляется из outbox с уведомлением пользователя в UI, без попыток шифрования.
   - На кадр `group_key_request_v1` отвечает только владелец группы. Владелец проверяет членство запрашивающего и выдаёт пакеты для **всех** допустимых эпох, в которых запрашивающий состоял в ростере (rate-limited).
   - Таймаут 60с генерирует событие в UI для ручного повтора.

7. **Транзакционность миграции схемы (v6 → v7)**:
   - Миграция выполняется в отдельной транзакции вне UI-потока.
   - Если `rebuildProjections()` завершается ошибкой, транзакция откатывается и версия схемы остаётся неизменной (`migrationFailureMidwayLeavesSchemaVersionUnchanged`).

8. **Residual-риски (выносятся в Фазу 3 и SEC-трекер)**:
   - Владелец оффлайн временно блокирует исключение участников и выдачу ключей новых эпох.
   - Группы с пассивным владельцем остаются на v1.
   - Риск деанонимизации через Bittorrent-трекер по общему info-hash при смешанном режиме (закрывается полным переходом в Tor/Yggdrasil).

Остальные открытые поля защищены Ed25519-подписью.

Python и Go подписывают один и тот же transcript:
`"2pchat-group-event-signature-v2\0" || canonical_payload`. Новые подписи всегда
используют v2; проверка на время миграции также принимает прежний Python
transcript `"2pchat-group-signature-api-v1\0" || canonical_payload` и прежнюю
Go-подпись самого `canonical_payload`. Это меняет только domain separation
native bridge, а JSON wire-версия `group_event_v1` остаётся прежней.

Plaintext события ограничен 256 KiB. Он является JSON и расшифровывается
только после проверки размера кадра, event ID, pinned identity, подписи,
членства, времени и наличия ключа эпохи.

### 6.1. Feed автора и идемпотентность

Каждое устройство имеет собственную возрастающую feed.

- `(group_id, event_id)` уникален;
- `(group_id, author_device_id, author_sequence)` уникален;
- точный повтор превращается в no-op и получает повторный ACK;
- другой event на уже занятом sequence считается equivocation и не ACK-ается.

`previous_author_event` включён в подпись и помогает диагностировать gap.
Входящий sequence не может опережать максимум принятого sync baseline и уже
сохранённого sequence более чем на 4096. Для sequence `1`
`previous_author_event` обязан отсутствовать, для остальных — быть непустым.
Если predecessor уже сохранён, ссылка обязана указывать на его `event_id`;
если уже сохранён successor, проверяется и его обратная ссылка на входящий
event. Поэтому доставка может быть вне порядка, но известная часть hash chain
не может расходиться. Runtime не требует немедленного наличия predecessor:
contiguous cursor останавливается перед дырой, а anti-entropy повторно
запрашивает недостающий диапазон.

### 6.2. Порядок отображения

Materialized timeline имеет полный детерминированный порядок:

1. `hlc_physical_ms`;
2. `hlc_logical`;
3. `author_device_id`;
4. `author_sequence`;
5. `event_id`.

UI читает ленту newest-first через полный cursor без `OFFSET`, затем показывает
её хронологически. Вставка задержанного сообщения не ломает границы страницы.
Одна storage-страница содержит до 1000 сообщений; UI увеличивает окно шагом
200 до 100 000 сообщений.

При локальной отправке HLC наблюдает последнее событие локального автора,
новейшее событие группы, текущий control head и target операции. Физическая
часть берётся как максимум wall clock и наблюдённых HLC, поэтому откат часов не
откатывает локальный порядок. При совпадении physical component logical
увеличивается от максимального наблюдённого значения, иначе начинается с `0`.
Parser и модель принимают logical component только от `0` до `1 000 000`.
При достижении `1 000 000` следующий локальный `tick/observe` не переполняет
счётчик: physical component увеличивается на `1 ms`, а logical сбрасывается в
`0`. Если при этом physical уже равен `Long.MAX_VALUE`, HLC завершается ошибкой,
а не оборачивается в отрицательное значение.

Для входящего события физическое время не может быть более чем на пять минут в
будущем, а expiry не может быть прошедшим. Отдельной проверки монотонности
чужого HLC относительно predecessor feed нет: причинный feed-контроль здесь
обеспечивают sequence и `previous_author_event`, а HLC задаёт порядок
отображения.

## 7. Типы событий и проекции

| События | Поведение |
| --- | --- |
| `MESSAGE`, `REPLY` | сообщение в timeline; reply хранит target |
| `MEDIA` | сообщение с зашифрованным manifest вложения |
| `EDIT`, `DELETE` | мутация target; может прийти до исходного сообщения |
| `REACTION_ADD`, `REACTION_REMOVE` | LWW-состояние по message/emoji/author |
| `PIN`, `UNPIN` | детерминированная проекция pinned event |
| `READ_RECEIPT` | receipt для target event |
| `GROUP_UPDATED` | название и описание |
| `MEMBER_ADDED` | состояния `INVITED` и `ACTIVE` |
| `MEMBER_REMOVED` | `LEFT`/`BANNED` и переход эпохи |
| `ROLE_CHANGED`, `MEMBER_RESTRICTED` | роль или permission mask |
| `OWNERSHIP_TRANSFERRED` | атомарная смена единственного владельца |
| `SYSTEM` | сообщение и подписанное предложение owner-serializer |
| `TYPING` | распознаётся политикой, но не отправляется и не показан в UI |

Event log остаётся источником истины. В той же SQL-транзакции обновляются:

- `group_messages` для message/media/edit/delete;
- unread для новых удалённых сообщений;
- pin projection;
- read receipt;
- reaction projection.

Edit/delete, reaction и pin сходятся при перестановке доставки благодаря
полному порядку HLC/device/sequence/event ID.

## 8. Роли, permissions и ACL

Активные роли:

| Роль | Возможности по умолчанию |
| --- | --- |
| `OWNER` | все известные permissions и передача владения |
| `ADMINISTRATOR` | полный permission mask; действует ниже владельца |
| `MODERATOR` | отправка, media, реакции, свои edit/delete, delete чужих, pin, restrict, ban, admin log |
| `MEMBER` | отправка, media, реакции, edit/delete своих сообщений |

Состояния членства хранятся отдельно от роли:

- `INVITED` — приглашение выдано, писать нельзя;
- `JOINING` — локальное предварительное состояние после accept; оно сохраняется
  после canonical activation и до атомарного применения полного подписанного
  roster snapshot, писать нельзя;
- `ACTIVE` — обычный участник;
- `RESTRICTED` — участвует в доставке, но использует выданный permission mask;
- `BANNED` и `LEFT` — не участвует и имеет нулевые permissions.

Permission mask включает отдельные права на сообщения, media, реакции,
редактирование и удаление своих/чужих сообщений, pin, group info, invite,
remove, restrict, ban, assignment ролей, invite links и admin log. Наличие
permission в модели не означает наличие соответствующего UI: например,
управление invite links пока не реализовано.

Основные инварианты:

- неактивный, приглашённый или `JOINING` автор не может отправлять события;
- нельзя модерировать себя;
- нельзя удалить, заблокировать или изменить владельца обычной ролевой
  операцией;
- роль и цель должны быть строго ниже actor по иерархии;
- новый `OWNER` создаётся только через ownership transfer;
- roster должен содержать ровно одного активного владельца;
- transfer атомарно повышает target и понижает прежнего владельца до
  администратора;
- ownership transfer запрещён, пока есть `INVITED` участники или недоставленные
  roster pages прежнего владельца;
- ban использует отдельное право `BAN_MEMBERS`, remove — `REMOVE_MEMBERS`;
- редактирование/удаление чужого сообщения требует отдельного права.

Обычное задержанное data event может ссылаться на текущий control head или его
канонического предка. Serialized control, pin/unpin, control proposal и
edit/delete чужого сообщения обязаны ссылаться ровно на текущий head.

Есть важное фактическое ограничение: ACL дополнительно требует, чтобы автор
участвовал **сейчас**. Поэтому сообщение, честно созданное до удаления автора,
но впервые пришедшее после его `LEFT/BANNED`, будет отклонено. Это защищает от
поздней инъекции старого ciphertext, но не сохраняет все pre-removal delayed
events.

## 9. Control plane и owner-serializer

Изменения membership, ролей и metadata образуют линейную control chain.

К serialized control относятся:

- `GROUP_UPDATED`;
- `MEMBER_ADDED`;
- `MEMBER_REMOVED`;
- `ROLE_CHANGED`;
- `MEMBER_RESTRICTED`;
- `OWNERSHIP_TRANSFERRED`.

Только текущий владелец подписывает и применяет такие переходы. Если
администратор или модератор имеет нужное право, он отправляет `SYSTEM` event с
`control_proposal`. Владелец повторно проверяет ACL относительно текущего head
и создаёт канонический control event.

В большой группе proposal не полагается только на HRW-размещение обычного
`SYSTEM` event: текущий владелец принудительно добавляется в его durable
recipients. После приёма owner ещё раз загружает сохранённый proposal уже под
`controlMutex` и непосредственно перед commit проверяет:

- что proposal всё ещё ссылается на точный текущий control head;
- что wire author совпадает с автором сохранённого event;
- что action и payload не подменены;
- что proposer всё ещё имеет нужное право относительно актуального roster;
- что для proposal ещё не существует канонического serialized control.

Таким образом предварительная проверка на устройстве proposer не создаёт
TOCTOU-разрешение: окончательное решение повторяется владельцем под lock.

Каждый control event:

1. подписан владельцем;
2. ссылается на точный предыдущий `control_head`;
3. проверяется по ACL предыдущего состояния;
4. сохраняется в event log;
5. применяет head, roster/metadata/epoch одной SQL-транзакцией с CAS.

### 9.1. Durable owner lineage

Каждый `OWNERSHIP_TRANSFERRED` дополнительно несёт
`GroupOwnerTransitionCertificate`. Сертификат подписан старым владельцем и
канонически связывает:

- group ID, предыдущий owner anchor и непрерывный `lineage_sequence`;
- предыдущий точный `control_head`;
- точные credentials старого и нового владельцев:
  `(transport fingerprint, stable device_id, Ed25519 signing key)`;
- timestamp и случайный nonce.

`device_id` обоих владельцев повторно выводится из fingerprint, credentials
должны точно совпасть с уже закреплёнными member rows, а `target_event_id`
control event обязан равняться `transitionId` сертификата. Root anchor — hash
group ID и исходных owner credentials; каждый следующий anchor равен
`transitionId` предыдущего сертификата. Sequence начинается с `1`, не имеет
дыр, а вся цепочка ограничена
`GroupOwnerLineage.MAX_TRANSITIONS = 128` переходами. После достижения этого
предела новый transfer не создаётся; compaction/перезапуск lineage не
реализован.

SQLCipher v3 хранит сертификаты по `(group_id, sequence)`. CAS-транзакция
ownership transfer одновременно меняет control head, owner device, роли
старого/нового владельцев и добавляет ровно следующий сертификат. Конфликтный
сертификат с тем же sequence или transition ID завершает операцию без
частичного перехода.

Signed invite переносит всю упорядоченную lineage и сам подписывает её состав и
порядок. Это позволяет удалённому участнику проверить законную смену владельца
за время отсутствия; подробный rejoin описан ниже.

Если следующий уже сохранённый child становится применим после родителя,
`drainStoredControlChain` пытается продолжить цепь. Невалидный child
пропускается, а не блокирует остальные.

При офлайне владельца data plane текущей эпохи продолжает работать:
сообщения, реакции, свои edit/delete и разрешённая модерация контента
реплицируются. Новое membership/role/metadata состояние ждёт возвращения
владельца и обработки proposal.

Это явная точка отказа доступности управления: если единственный владелец
утрачен до передачи владения, протокол не умеет избрать или восстановить нового
владельца. Data plane может продолжать работать с уже известным roster и
секретом эпохи, но invite, remove/ban, роли, metadata и rekey останутся
заблокированы. Следовательно, owner governance является SPOF.

Owner-serializer и owner lineage не являются consensus-протоколом. При честном
владельце локальный mutex, author sequence и CAS дают линейную цепь.
Злонамеренный владелец может подписать разные control children или несколько
валидных сертификатов следующего владельца от одного anchor для разных
partition. Runtime отклоняет второй локально принятый branch, но не имеет
Byzantine-safe fork resolution, поэтому разные partition могут выбрать разные
первые ветви или successor lineage.

Локальный admin log показывает последние 100 control/pin/delete событий из
bounded event scan. Это проверяемая проекция подписанного журнала, но не
отдельный неизменяемый аудит-сервис.

## 10. Приглашение, `JOINING` и rekey

### 10.1. Создание

Создатель становится `OWNER`, выбранные контакты — `INVITED`, создаётся epoch
`1` и случайный секрет. Invite формируется владельцем и хранится в durable
outbox.

Invite содержит только владельца и конкретного получателя в массиве `members`,
а не весь roster. Отдельное подписанное поле `roster_size` содержит число
participating members с учётом получателя, проверяется в диапазоне от длины
`members` до 10 000 и используется UI как размер приглашённой группы. Это
удерживает кадр в общем wire-limit; `roster_size` не является доказательством
состава. После принятия полный активный roster приходит подписанными
страницами.

Invite несёт сырой `epoch_secret` Base64. Подпись защищает целостность, но не
конфиденциальность; конфиденциальность обеспечивает только существующая
pairwise Double Ratchet-сессия. Invite нельзя публиковать в DHT, magnet link,
нешифрованном relay или использовать как пересылаемую публичную ссылку.

Срок действия invite — семь суток. ID детерминирован от группы, эпохи,
**точного `control_head`**, владельца, получателя и семидневного validity
window, что делает повторную постановку одного состояния идемпотентной, но
выдаёт новый ID после любого control transition даже без смены эпохи.

Срок не является sliding window. Anti-entropy владельца не перевыпускает invite
после семи суток от `MEMBER_ADDED(INVITED)`: он сериализованно применяет
`MEMBER_REMOVED(LEFT)` и выполняет обязательный rekey. Поэтому уже раскрытый
приглашённому секрет invitation epoch перестаёт защищать новый data plane даже
если получатель никогда не отправил decline. Если владелец всё это время
недоступен, отзыв, как и прочие governance-операции, ждёт его возвращения.

ACK приглашения не принимается как произвольный ACK от ещё не участвующего
устройства. Owner ищет точную ранее выданную outbox-задачу по
`(group_id, invite_id, recipient_device_id)`, заново разбирает и проверяет
подпись invite, находит в нём единственного подписанного получателя и требует
точного совпадения его fingerprint с transport-сессией и локальным roster.
Только после этого ACK закрывает именно эту issued task.

### 10.2. Динамическое добавление

Перед приглашением нового или ранее удалённого участника владелец:

1. создаёт `MEMBER_ADDED(status=INVITED)`;
2. увеличивает epoch;
3. генерирует новый независимый секрет;
4. раздаёт signed legacy key packages текущим участникам;
5. передаёт тот же новый секрет приглашённому внутри signed invite.

Таким образом новый участник не получает старые epoch secrets. Sync также
фильтрует события раньше его `joined_epoch`.

При этом pending invite уже содержит секрет invitation epoch `E` **до нажатия
Accept в UI**, то есть секрет доступен устройству со статусом `INVITED`. Токен
хранится в локальной SQLCipher БД, а штатный fan-out и sync не отправляют
события `INVITED/JOINING` устройству, но это лишь транспортное ограничение:
криптографически устройство сможет прочитать ciphertext эпохи `E`, если
получит его вне штатной доставки.

Не принятый за семь суток invite автоматически закрывается owner-событием
удаления и rekey. Это ограничивает время раскрытия invitation epoch, но не
устраняет сам факт ранней передачи секрета.

Активация теперь действительно выполняет дополнительный rekey `E -> E+1`,
поэтому будущий активный data plane переходит на новый секрет. Это не устраняет
раскрытие invitation-epoch ciphertext и не превращает схему в MLS:
`epoch_secret` по-прежнему передаётся raw внутри pairwise-канала, TreeKEM/PCS
отсутствуют.

### 10.3. Accept

Получатель проверяет pairwise identity, owner signature, suite, время,
уникальность identity/device и адресацию приглашения. После accept он:

- создаёт локальную группу или обновляет предварительную;
- сохраняет invite key эпохи `E`;
- помечает себя `JOINING`;
- ставит signed response владельцу в durable outbox;
- остаётся в read-only UI до подтверждения.

Владелец связывает response с конкретной ранее выданной outbox-задачей,
получателем, fingerprint и временем. Для accept выданный invite обязан
совпадать не только с текущей epoch `E`, но и с **точным текущим
`control_head`**. После проверки владелец:

1. создаёт новый независимый секрет `E+1`;
2. выпускает canonical `MEMBER_ADDED(status=ACTIVE)` в эпохе `E`, где
   `joined_epoch = next_epoch = E+1`;
3. атомарно переводит свой authoritative roster в `ACTIVE`, control head и
   группу в `E+1`;
4. раздаёт key package `E+1` всем участникам, активным в новой эпохе, включая
   принятого;
5. ставит принятому подписанные страницы актуального roster.

На принимающем устройстве canonical `MEMBER_ADDED(ACTIVE)` **не переводит
локальную строку сразу в `ACTIVE`**. Оно подтверждает membership, продвигает
head/epoch, но projection намеренно сохраняет локальный статус `JOINING`.
Пришедший key package `E+1` также не снимает этот барьер. Пока устройство
`JOINING`, runtime принимает из group events только точный activation
`MEMBER_ADDED(ACTIVE)` для локального device и его control context; любые
следующие сообщения и control events отклоняются до завершения roster
bootstrap и будут добраны повторной доставкой/anti-entropy.

В `ACTIVE` локальная строка переходит только внутри CAS-транзакции применения
**полного согласованного signed multipage roster snapshot**. Composer после
этого всё равно отдельно требует key текущей эпохи. Следовательно, snapshot и
key package могут приходить в любом порядке, но отправка разрешается лишь
после обоих условий.

Если accept долго лежал офлайн и относится к старой эпохе **или уже не
текущему head**, владелец ACK-ает старый response, ставит свежее приглашение и
не применяет старое состояние. Поскольку head входит в `invite_id`, refreshed
invite имеет другую точную issued outbox-задачу. Клиент в `JOINING` сохраняет
более новый invite, атомарно обновляет epoch/head и автоматически повторяет
accept.

### 10.4. Decline, remove и ban

Decline хранится локально как `DECLINED` и повторяется напрямую владельцу до
pairwise ACK даже без созданной группы и после перезапуска. Владелец связывает
его с точной ранее выданной подписанной invite-задачей, но намеренно обрабатывает
ветку decline **до** проверки актуальности её epoch/head. Если получатель всё
ещё `INVITED`, владелец применяет `MEMBER_REMOVED(LEFT)`, создаёт следующую
эпоху и не выдаёт отклонившему новый секрет. Уже достигнутые `LEFT/BANNED`
состояния только идемпотентно ACK-аются. Если очень задержанный decline пришёл
после более позднего успешного accept и участник уже `ACTIVE`, он также только
ACK-ается и не отменяет новое членство.

Remove, ban и добровольный выход невладельца также создают новую эпоху.
Новый key package получают только оставшиеся participating members. Обычные
старые outbox-задачи удалённому получателю переводятся в `FAILED`, а не
повторяются бесконечно; исключение — его подписанный removal notice. Владелец
обязан сначала передать владение и не может просто выйти.

Role change, restriction, metadata update и ownership transfer сами по себе
не меняют состав и поэтому не вращают epoch.

### 10.5. Безопасный rejoin после `LEFT`/`BANNED`

Локальная tombstone-группа не перезаписывается любым invite с тем же group ID.
Rejoin допускается только если:

- локальный статус равен `LEFT` или `BANNED`;
- invite адресует локальное устройство как `INVITED` и содержит control head;
- invite epoch не ниже локально известной current/removed epoch;
- timestamp invite не старее локальной tombstone с учётом clock skew;
- текущие owner credentials либо точно совпадают с ранее закреплёнными, либо
  signed owner lineage непрерывно доказывает переход от локально доверенного
  anchor к новому владельцу.

Для changed-owner rejoin каждый сертификат проверяется по signature, group ID,
непрерывным sequence/anchor, времени и точным old/new credentials; цепь должна
достичь локально сохранённого anchor и закончиться ровно credentials владельца
invite. Известный `device_id` никогда не может получить другой fingerprint или
signing key. Полная проверенная lineage, новый owner/head/epoch, invite key,
quarantined roster и локальный `JOINING` применяются одной DB-транзакцией с
CAS по прежним owner/head/epoch. Поэтому crash не оставляет «нового владельца
со старой lineage» или наоборот.

На стороне владельца accept также должен относиться к точной issued
outbox-задаче и к его точным текущим invite epoch **и head**; stale response
получает свежий invite вместо применения старого состояния.

При нажатии Accept runtime сохраняет актуальные epoch/head владельца, но
временно quarantines все старые roster rows, кроме owner и локального
устройства: они становятся локальными `LEFT` с нулевыми permissions. Это не
даёт доверять roster, сохранённому до удаления. Локальное устройство снова
переходит в `JOINING`; подписанные paged snapshots после canonical acceptance
заново наполняют roster актуальными участниками.

Видимость группы выводится из durable membership, а не из отдельного
`hidden_group_<id>` preference: отсутствующий local member и `LEFT/BANNED` не
показываются; любая другая local membership row, включая
`INVITED/JOINING/ACTIVE/RESTRICTED`, делает группу видимой. Поэтому pending
rejoin остаётся только в списке приглашений, а после локального Accept группа
сразу возвращается в список как read-only `JOINING`. Полноценная отправка, как
и при первом вступлении, ждёт одновременно complete roster snapshot и key новой
joined epoch.

## 11. Durable outbox и at-least-once доставка

Локальная отправка не ждёт сеть. Событие, его materialized projection и
per-recipient outbox-задачи коммитятся одной SQL-транзакцией. После успешного
commit приложение может быть остановлено без потери отправки.

Состояния задачи:

- `PENDING`;
- `RETRY`;
- `ACKED`;
- `FAILED`.

Цикл доставки:

1. выбирается до 200 due-задач;
2. кадр отправляется в pairwise-сессию;
3. после transport success sender ждёт durable store ACK 30 секунд;
4. потерянный ACK приводит к безопасному повтору;
5. transport failure использует exponential backoff от 1 секунды до 15 минут
   с детерминированным jitter ±20%;
6. ACK атомарно переводит задачу в `ACKED` и записывает `STORED` receipt.

В runtime retry budget фактически не ограничен (`maxAttempts = Int.MAX_VALUE`)
для временно недоступных участников. Явно удалённый/заблокированный recipient
даёт `FAILED`, **кроме** задачи, несущей его собственный `MEMBER_REMOVED`: такой
removal notice разрешено доставить уже после атомарного перехода recipient в
`LEFT/BANNED`. Повреждённый сохранённый frame откладывается до
`Long.MAX_VALUE`. Пользователь может requeue-ить задачи конкретного сообщения.

Доставка по сети — at-least-once. Exactly-once достигается только на локальной
границе ingest двумя UNIQUE-ограничениями. Офлайн одного получателя не
блокирует остальных.

Текущий UI различает `QUEUED`, `REPLICATING`, `DELIVERED`, `REPLICATED`,
`FAILED` и `READ`. Для локального сообщения planned set берётся из его outbox,
а обязательный storage quorum равен `min(3, distinct planned recipients)`.

- любой `READ` receipt немедленно даёт `READ`;
- достижение storage quorum даёт `REPLICATED`;
- хотя бы один `STORED`, но ещё не quorum, даёт `DELIVERED`;
- отсутствие `STORED` при незавершённых задачах даёт `REPLICATING`;
- невозможность достигнуть quorum после failures даёт `FAILED`;
- отсутствие outbox-задач даёт `QUEUED`.

Это клиентская агрегация receipt, а не consensus или Byzantine quorum. Read
receipt сам является group event и для очень больших групп потребует
агрегации, чтобы не создавать `O(N)` событий.

Outbox запускается:

- сразу после локальной операции;
- после старта runtime;
- при установлении peer-сессии;
- из периодического anti-entropy worker.

Периодический worker имеет ограничение `NetworkType.CONNECTED` и минимальный
Android-интервал 15 минут. Перед обращением к group runtime worker запускает
bridge клиента: `NativeBridge` в Go-дереве или `PythonBridge` в Chaquopy-дереве и требует доступную
криптографическую identity; исключение, вышедшее из `doWork`, возвращает
WorkManager `Result.retry`. Если startup recovery ранее не завершился,
`runAntiEntropy` повторяет `reconcileDurableState` перед outbox/sync. Ошибка
этого deferred recovery логируется и оставляет recovery-флаг установленным,
поэтому следующий worker/run повторит попытку. Helper для one-shot запуска
предусмотрен, но текущий production flow его не вызывает.

## 12. Репликация и масштабирование fan-out

Маршрутизация разделяет serialized control и обычные application events.

### Serialized control: полный roster fan-out

`GROUP_UPDATED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `ROLE_CHANGED`,
`MEMBER_RESTRICTED` и `OWNERSHIP_TRANSFERRED` не ограничиваются HRW-репликами.
Исходный owner создаёт durable outbox-задачу каждому participating member,
активному в эпохе control event. Это полный fan-out по активному roster — вплоть
до общего лимита 10 000 участников.

Для membership control target добавляется отдельно, даже если он ещё
`INVITED` или уже стал `LEFT/BANNED`. В частности, задача удаления не
отбрасывается после локального применения `MEMBER_REMOVED`, поэтому ушедший
участник может получить подписанный removal notice. Цена такой сходимости
управления — `O(N)` outbox/rekey и отсутствие Telegram-подобной
масштабируемости control plane.

### Обычные события

До 32 получателей создаются прямые durable outbox-задачи. Для больших групп выбираются три стабильные HRW-реплики author feed и до трёх подключённых соседей детерминированного кольца. Каждый первый получатель ретранслирует событие один раз; идемпотентность журнала препятствует повторному применению.

Serialized membership control сохраняет полный fan-out. Пропуски восстанавливает anti-entropy. Лимит roster 10 000 — ограничение модели и парсера, а не результат испытания 10 000 устройств. Ротация ключа и изменения членства остаются O(N).

Подробнее о моделировании и границах сетевой проверки: [аудит переноса](GROUP_PORT_AUDIT_2026-09-05.md).

## 13. Anti-entropy и восстановление gaps

На группу допускается один sync-сеанс и один ожидаемый ответ. Курсоры отправляются последовательно; ответ связывается с request ID, группой и аутентифицированным пиром. Таймаут ожидания — 15 секунд; после отсутствия ответа или трёх пакетов без прогресса выбирается другой пир. Периодический путь рассматривает до трёх пиров, постепенно меняя выбор. Reconnect повторяет ключевой пакет только для подключившегося участника.

- roster feed IDs разбиваются по 256 cursors на request;
- cursor — последний непрерывный author sequence, а не просто максимум;
- gap не перескакивается даже при наличии более поздних событий;
- response содержит не более 100 исходных signed wire events;
- response строится с проверкой сериализованного размера и никогда не превышает
  общий budget 768 KiB;
- если первый event сам помещается в wire-limit, но не помещается вместе с
  wrapper `group_sync_batch_v1`, он отправляется напрямую как
  `group_event_v1`, после чего следует bounded batch с `has_more`;
- `has_more` запускает следующую страницу;
- неуспешный batch повторяется с backoff, до пяти последовательных batch
  failures в одной серии.

Новый участник получает в invite baseline для feed владельца и собственной
feed. После accept signed roster pages несут `last_author_sequence` остальных
активных устройств. При выдаче истории responder дополнительно фильтрует
`epoch >= requester.joined_epoch` непосредственно в storage query через
`minimumEpoch = requester.joinedEpoch`, поэтому requester cursor не может
запросить pre-join epoch. После accept из invite epoch `E` canonical membership
задаёт `joined_epoch = E+1`: серверный sync не выдаёт такому участнику события
эпохи `E` и старше. Сам acceptance event эпохи `E` доставляется target отдельно
как membership control.

Sync batch не доверяет реплике как автору: каждый вложенный event снова
проходит event ID, pinned signing key, membership, ACL, epoch AEAD и
deduplication.

## 14. Подписанные страницы roster

После принятия участника владелец отправляет полный participating roster
страницами до 256 записей. При максимуме 10 000 участников это не более 40
страниц.

Подпись страницы связывает:

- конкретного получателя;
- group ID, конкретный canonical control head и epoch;
- индекс и общее число страниц;
- identity и signing key текущего владельца;
- все identity, role, permission, status, membership epoch, feed cursor и
  timestamps участников.

Получатель принимает страницу только от **текущего** владельца в
аутентифицированной pairwise-сессии, для своей identity и только для **точных
текущих** `control_head` и epoch. Ancestor snapshot после продвижения группы и
страница прежнего владельца после ownership transfer не принимаются.

Каждая успешно проверенная страница сначала сохраняется durably в
`roster_snapshot_pages`; одинаковый повтор идемпотентен, а другая payload для
того же `(group, head, page_index)` является конфликтом. Поэтому частичный
snapshot переживает process death. Отдельные страницы не меняют member rows и
не снимают `JOINING`.

Применение начинается только когда durably присутствуют все индексы
`0..total_pages-1`. Runtime заново разбирает и проверяет подпись каждой
страницы, а затем требует, чтобы они образовывали одну generation с одинаковыми
recipient, group, head, epoch, `total_pages`, `created_at_ms` и точными owner
credentials. После объединения проводится **глобальная**, а не только
постраничная проверка:

- всего от 1 до 10 000 участников;
- ни один `device_id` и ни один fingerprint не повторяется между страницами;
- существует ровно один активный `OWNER`, и его credentials совпадают с
  текущим владельцем группы;
- локальный device присутствует ровно с закреплёнными fingerprint/signing key
  и статусом `ACTIVE`;
- snapshot не меняет уже закреплённую identity известного member.

Только после этого одна SQL-транзакция с CAS по точным ожидаемым
`(control_head, epoch)` применяет весь roster, монотонно обновляет per-author
cursors и удаляет сохранённые страницы этой generation. Если группа успела
продвинуться, CAS отклоняет snapshot целиком: частично новый roster не виден.
Успешный commit переводит локальную строку `JOINING -> ACTIVE`; до него runtime
блокирует все более поздние group events.

Runtime формирует snapshot только из `ACTIVE`/`RESTRICTED` участников с
известными fingerprint и signing keys. `INVITED`, `LEFT` и `BANNED` в полном
активном snapshot не отправляются.

Страницы ACK-аются отдельно и находятся у владельца в durable outbox; после
частичной доставки недостающие страницы добираются retry. Отдельного Merkle
root или подписанного финального commit-объекта нет, но activation всё равно
требует полный набор одной signed generation и атомарный CAS commit.

## 15. «Торрентоподобные» вложения

Вложение не является одним обновляемым torrent-файлом группы. Реализован более
безопасный вариант: encrypted manifest в подписанном group event и
content-addressed immutable ciphertext blocks.

Один изменяемый «файл группы» давал бы новый info-hash после каждого сообщения
и конфликт двух офлайн-авторов. Здесь каждое событие и каждый блок неизменяемы,
поэтому параллельные отправки объединяются как set подписанных объектов, а
потерянные части можно запрашивать независимо.

Отправитель:

1. копирует Android content URI во временный bounded файл;
2. генерирует случайные `attachment_id` и 256-битный content key;
3. режет файл на блоки по 512 KiB;
4. шифрует каждый блок AES-256-GCM с отдельным nonce и AAD
   `attachment_id:block_index`;
5. вычисляет CID как SHA-256 ciphertext;
6. сохраняет блок атомарно по CID;
7. помещает manifest и content key внутрь зашифрованного `MEDIA` event.

Manifest содержит имя, MIME, полный plaintext size/hash, chunk size, content
key и для каждого блока index, CID, nonce и размеры. Он доступен только
участнику, способному расшифровать group event. Сами блоки opaque и
проверяются без plaintext key по CID.

Получатель:

- вычисляет отсутствующие CIDs;
- запрашивает до 4 CIDs за кадр, привязывая запрос к group ID и media event ID;
- обращается максимум к трём подключённым peers;
- принимает только ожидаемые блоки в рамках request ID;
- проверяет Base64, размер и SHA-256 CID до сохранения;
- при сборке проверяет GCM каждого блока, его plaintext size, общий размер и
  SHA-256 всего файла;
- пишет во временный `.part` и атомарно переименовывает только после полной
  проверки.

Перед раздачей seed загружает указанный `MEDIA` event из той же группы,
проверяет его encrypted manifest и отдаёт только CID из этого manifest.
Физический block store дополнительно разделён по hash group ID. Участник одной
группы поэтому не может использовать attachment request как oracle для чтения
ciphertext blocks другой группы. Для каждой пары `(group, requester)` seed
отдаёт не более 32 MiB ciphertext за окно 60 секунд.

Автор всегда является seed. Дополнительные 16 seed-реплик выбираются по
`author_device_id` из того же participating candidate set, что и HRW-доставка
обычных событий. Поэтому attachment placement **совпадает с author-feed
HRW-3**, а не вычисляется отдельно по media `event_id`. Периодический repair
просматривает до 1000 последних событий и запрашивает недостающие блоки media
objects, назначенных локальному устройству. Явная загрузка из UI также
возобновляет отсутствующие блоки.

Attachment request хранится в памяти, но источник истины — durable media event
с manifest; после рестарта явная загрузка или replica repair создают новые
запросы. Одновременно хранится не более 2048 pending request descriptors.

Store принимает chunk от 256 KiB до **512 KiB**; runtime создаёт блоки ровно по
512 KiB. Максимум — **1024 блока** и **512 MiB plaintext**. Размеры,
последовательные индексы, число блоков, nonce, content key и соотношение
plaintext/ciphertext строго проверяются при разборе manifest. Один
Base64-блок вместе с JSON должен помещаться в 768 KiB wire frame.
Дополнительный предел manifest внутри event — 220 KiB, поэтому практический
максимум может быть ниже 512 MiB.

CID вычисляется от случайно зашифрованного блока, поэтому это проверяемое
content addressing и resume, но не гарантированная дедупликация одинаковых
файлов между разными загрузками.

У attachment data plane нет durable per-block outbox, ACK-кворума или гарантии
долговременного seed. Block frames и фоновый repair работают best-effort:
успешный group event гарантирует сохранность manifest, но не всех его блоков.
`discard` удаляет локально staged blocks при неудачной отправке manifest, а
общего retention/garbage collector для успешных или осиротевших блоков нет.
Если все seeds потеряли блок, восстановить файл по одному manifest невозможно.

## 16. Хранилище

Группы находятся в отдельном SQLCipher-файле `twopchat-groups.db`. Passphrase
получается через `SecureStorage`. Plaintext сообщений и legacy epoch secrets
хранятся внутри зашифрованной БД; отдельного hardware-backed wrapping каждого
epoch key нет.

Схема v3:

| Таблица | Назначение |
| --- | --- |
| `groups` | metadata, local/owner device, epoch, control head, pin и unread |
| `group_members` | identity, peer, signing key, role, permissions и membership epochs |
| `group_epoch_keys` | legacy secret по `(group_id, epoch)` |
| `group_events` | append-only журнал и исходный signed wire payload |
| `group_messages` | rebuildable timeline projection |
| `group_reactions` | LWW-проекция reaction state |
| `outbox_tasks` | durable per-recipient fan-out |
| `receipts` | `STORED` и `READ` receipts |
| `pending_invites` | входящие pending/declined invites до создания группы |
| `owner_lineage_certificates` | durable ordered chain передачи владения |
| `roster_snapshot_pages` | проверенные, но ещё не применённые страницы roster bootstrap |
| `sync_cursors` | per-author history floor и последний event |

Миграция v1 → v2 создаёт `group_reactions` и переигрывает уже сохранённые
reaction events. Миграция v2 → v3 без сброса групп создаёт
`owner_lineage_certificates` и `roster_snapshot_pages`. Для старой группы без
сохранённой истории текущие owner credentials становятся её локальным root
anchor; переходы, случившиеся до v3 и не представленные сертификатами, задним
числом не восстанавливаются.

Транзакционные границы:

- создание группы + roster + первый epoch key;
- локальный event + projection + весь первоначальный outbox;
- входящий event + projection + unread;
- control head + metadata/roster/epoch mutation; при ownership transfer в той
  же транзакции добавляется следующий lineage certificate;
- changed-owner invite: проверенная lineage + owner/head/epoch + epoch key +
  quarantined/member rows;
- ACK + receipt;
- одна durable roster page;
- полный roster + monotonic cursors + удаление его страниц с CAS по head/epoch.

Metadata update использует `UPDATE`, а не SQLite `REPLACE`, поэтому не
активирует foreign-key cascade. Удаление аккаунта/группы намеренно удаляет
group rows, attachment blocks и downloads.

Retention, snapshot compaction и secure erasure старых epoch keys не
реализованы. Журнал append-only в нормальном ingest-пути, но не является WORM.

## 17. Startup и crash recovery

При инициализации runtime:

1. пытается продолжить сохранённые control chains;
2. восстанавливает outbox для последнего локального wire event, если это
   требуется после данных старой версии;
3. проходит текущую каноническую control chain назад до отсутствующего
   события или цикла;
4. для **каждого** локально подписанного канонического `MEMBER_ADDED` или
   `MEMBER_REMOVED` с доступным секретом `next_epoch` повторно создаёт key
   packages допустимым получателям;
5. регенерирует актуальные invites для `INVITED`;
6. возобновляет declined responses;
7. обновляет UI, запускает outbox и anti-entropy.

Обычный новый local event не может остаться без первоначальных outbox-задач,
поскольку они коммитятся атомарно. Key packages, roster pages и invites
имеют детерминированные task IDs. Startup явно восстанавливает key packages по
всей доступной канонической цепи и актуальные invites; roster pages уже сами
лежат в durable outbox, а повторный accept активного участника снова ставит
snapshot. На принимающем устройстве частично собранные roster pages также
лежат в SQLCipher v3: повтор любой страницы после рестарта заново проверяет
полный набор и завершает CAS-activation, если теперь присутствуют все страницы.

Если crash произошёл после создания секрета следующей эпохи, но до завершения
control операции, runtime повторно использует уже сохранённый секрет этой
эпохи, а не создаёт конфликтующий.

Startup recovery имеет явный флаг незавершённости. Он снимается только после
успешного прохода control chains и `reconcileDurableState`. Периодический
worker перед recovery поднимает и проверяет crypto bridge выбранного клиента; если identity
недоступна или наружу выходит другая ошибка worker, WorkManager возвращает
`Result.retry`. `runAntiEntropy` сам ещё раз запускает durable recovery, если
предыдущая попытка не сняла флаг; локально перехваченная ошибка reconcile
оставляет флаг установленным для следующего запуска.

## 18. Поведение при сбоях

| Сценарий | Реальное поведение |
| --- | --- |
| отправитель офлайн | event и outbox сохраняются; отправка продолжится позже |
| один recipient офлайн | остальные не блокируются; его задача остаётся `RETRY` |
| ACK потерян | event повторяется, recipient deduplicate и снова ACK-ает |
| duplicate/reorder | UNIQUE и детерминированные проекции дают идемпотентность |
| edit/delete раньше base message | мутация хранится; projection достраивается после base |
| reaction add/remove переставлены | выигрывает полный детерминированный порядок |
| процесс Android убит | SQLCipher log/outbox/invites/cursors переживают restart |
| feed имеет gap | contiguous cursor остаётся перед gap; sync запрашивает снова |
| canonical acceptance `E -> E+1` применён | локально участник остаётся `JOINING`; все более поздние events блокируются до complete signed roster snapshot |
| roster snapshot применён, key package задержался | участник уже `ACTIVE`, но composer остаётся выключенным до ключа `E+1` |
| часть roster pages задержалась или процесс убит | принятые pages остаются durable; UI read-only `JOINING`, retry достраивает generation |
| другой новый ключ задержался | event новой эпохи отклоняется до получения key package и приходит повторно/sync |
| владелец офлайн | data plane работает; serialized control ждёт владельца |
| accept относится не к точной current epoch/head | владелец ACK-ает response, выдаёт invite с новым head-bound ID; `JOINING` повторяет accept |
| decline сильно задержался | пока member `INVITED`, removal/rekey выполняется; `LEFT/BANNED/ACTIVE` не откатываются и только ACK-аются |
| rejoin `LEFT/BANNED` после смены owner | проверяется non-rollback epoch и lineage от локального anchor; старый roster quarantined до complete snapshot |
| видимость rejoin | pending tombstone скрыт статусом `LEFT/BANNED`; после Accept виден read-only `JOINING`, без отдельного hidden-флага |
| участник удалён | не получает новую эпоху; обычный pending outbox становится `FAILED`, но signed removal notice можно доставить |
| сообщение создано до revoke, но впервые пришло после него | текущий ACL требует participating автора и отклоняет событие |
| attachment block потерян | CID остаётся в manifest; download/repair запрашивает другой seed |
| все seeds блока потеряны | manifest сохраняется, но файл невосстановим без доступной копии |

Отдельной quarantine table нет: rejoin-quarantine реализован временным
переводом stale `group_members` в `LEFT` с нулевыми permissions. Невалидные или
пока неприменимые frames отклоняются и bounded-причина пишется в Android log
без materialization. Надёжность повторной попытки в этом случае обеспечивают
sender outbox и anti-entropy.

## 19. Лимиты

| Ограничение | Значение |
| --- | ---: |
| общий JSON frame | 768 KiB |
| Base64 ciphertext event | 512 KiB символов |
| plaintext event | 256 KiB |
| текст сообщения | 64 000 символов |
| название / описание группы | 160 / 2000 символов |
| manifest вложения в event | 220 KiB |
| sync events в batch | 100 |
| author cursors в sync request chunk | 256 |
| roster members в page | 256 |
| roster members всего | 10 000 |
| окно допустимого скачка author sequence | 4096 |
| HLC logical counter | 0–1 000 000; затем physical `+1 ms`, logical `0` |
| owner lineage transitions | 128 |
| полный direct fan-out обычного event | 32 recipients |
| serialized control fan-out | весь активный roster, максимум 10 000 members |
| HRW replicas большой группы | 3 |
| periodic anti-entropy peers на группу | 3 |
| due outbox batch runtime | 200 |
| SQL query page | 1000 |
| UI timeline window | 100 000 |
| допустимое будущее время | 5 минут |
| invite lifetime | 7 суток |
| attachment request | 4 CID |
| attachment serve quota | 32 MiB / 60 секунд на `(group, requester)` |
| attachment chunk | 256–512 KiB; runtime создаёт 512 KiB |
| attachment blocks | 1024 |
| plaintext attachment | 512 MiB |

## 20. Threat model

### 20.1. Что защищено

- чтение group plaintext пассивным сетевым наблюдателем;
- изменение signed event envelope;
- изменение ciphertext, AAD, attachment block или manifest;
- подмена автора без его Ed25519 key;
- повтор одного event;
- обычная потеря, duplicate, reorder, delay и временный partition;
- превышение роли при обязательном ACL reducer;
- недоверенная реплика ciphertext blocks;
- crash между local event и fan-out.

### 20.2. Что видно снаружи group ciphertext

Envelope раскрывает group ID, epoch, kind, автора, sequence, HLC,
control/target links, expiry и размеры. Pairwise transport скрывает содержимое,
но не устраняет traffic analysis на endpoint.

### 20.3. Что не защищено или защищено частично

- компрометация активного endpoint, Ed25519 key или epoch secret;
- forward secrecy и PCS уровня RFC 9420;
- злонамеренный участник, сохраняющий plaintext своей эпохи;
- `INVITED` endpoint знает raw secret эпохи `E` до UI Accept и может
  расшифровать полученный вне штатного fan-out ciphertext этой эпохи;
- публикация raw invite/key package вне pairwise encrypted transport;
- malicious owner control/successor-lineage fork: сертификаты доказывают
  авторизованную преемственность, но не единственность выбранной ветви;
- потеря единственного владельца: governance SPOF без election/recovery;
- Sybil/DoS discovery, exhaustion storage/bandwidth и spam от легального
  участника;
- rollback SQLCipher-файла из старой backup;
- secure deletion старых secrets;
- гарантированное принятие честного delayed event, впервые пришедшего уже после
  revoke автора;
- best-effort attachment blocks без durable quorum, retention и GC;
- потеря всех attachment seeds;
- серверный уровень availability для очень больших групп.

Перед materialization runtime проверяет bounded frame, type/version, event ID,
pinned transport identity, roster signing key, Ed25519 signature, author
sequence conflict, clock/expiry, canonical control context, membership, ACL,
suite, epoch key и AES-GCM. Ошибка не должна частично менять timeline, unread,
roster или control head.

## 21. Тестирование

В репозитории есть два уровня тестов.

JVM unit/simulation:

- permission matrix, role ceiling и ownership invariant;
- HLC, wire maximum, rollover logical `1 000 000 -> physical + 1` и полный
  event order;
- retry saturation/jitter;
- HRW placement и replica acknowledgement aggregation;
- переходы `INVITED`/`JOINING`/`ACTIVE`, безопасный refresh/rejoin
  `LEFT`/`BANNED`, non-rollback epoch и блокировка ownership transfer при
  pending invite/roster delivery;
- canonical owner-transition certificate, root/next anchors и подпись invite,
  покрывающая порядок lineage;
- детерминированная симуляция 20 участников с offline, loss, duplicate,
  reorder и delay;
- восстановление события, созданного офлайн;
- детерминированный HRW-план для 10 000 участников.

Android instrumented:

- AES-GCM round trip, nonce uniqueness, AAD/key boundary и tamper rejection;
- canonical event/invite/control/roster codecs и hostile bounds;
- attachment request/block frames;
- SQLCipher schema/migration (v7 Go / v6 Chaquopy), restart persistence, dedup, cursor paging и
  unread;
- durable owner lineage и multipage roster pages после reopen, CAS control
  head, атомарные control/invite/snapshot/outbox операции;
- coordinator-сценарий refresh `JOINING`, rekey на canonical acceptance,
  сохранение `JOINING` до signed roster snapshot, блокировка composer до
  snapshot+key package и membership-derived rejoin visibility при offline
  transport;
- delayed pin/read/reaction projections;
- encrypted multi-block attachments, resume, CID/GCM/full-file verification;
- Compose ACL, роли, ban confirmation, delivery state и attachment download UI.

Основные команды:

```powershell
cd "2PChat android/android"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Детерминированная симуляция проверяет свойства модели, а instrumented tests —
компоненты на Android. Они не заменяют отдельный длительный end-to-end soak
нескольких реальных устройств с фактическим relay, сменой владельца,
многодневным partition и потерей всех назначенных реплик. Результаты конкретного
прогона на эмуляторах должны фиксироваться в отчёте сборки, а не
зашиваться в этот документ.

## 22. Вывод

Текущий Android runtime уже реализует устойчивый offline-first групповой чат:
подписанный реплицируемый журнал, роли и ACL, serialized control plane,
durable outbox, reconnect sync, paged roster и торрентоподобную раздачу
зашифрованных блоков.

Он подходит как рабочая основа приватных малых и средних P2P-групп и как
delivery/storage слой для будущего MLS. Его нельзя описывать как RFC 9420 MLS
или как готовый аналог Telegram-канала на сотни тысяч участников: для этого
ещё нужны настоящая MLS state machine, multi-device credentials,
Byzantine/fork recovery и долговременный масштабируемый слой доступных
реплик.

## 23. Остаточные протокольные ограничения и архитектурные компромиссы (Фаза 1)

В рамках завершения Фазы 1 харденинга безопасности (`2pchat-epoch-v1` и перехода к v2)
зафиксированы следующие явные архитектурные свойства и компромиссы:

### 23.1. Зависимость от доступности владельца (Offline Owner Bottleneck)
- **Блокировка изменений состава**: В протоколе v2 владелец группы является единственным
  авторитетным сериализатором control plane. Если владелец офлайн, предложения об
  исключении участников (`MEMBER_REMOVAL_PROPOSED`) и приглашения новых участников не
  могут быть ратифицированы. Запросы на выход остаются в состоянии ожидания до
  возвращения владельца в сеть.
- **Выпуск ключей эпох**: Новые эпохи и ротация ключей инициируются исключительно
  владельцем. При смене эпохи офлайн-участники, не получившие своевременно пакет ключей,
  сохраняют намерения отправки в устойчивом outbox (`awaiting_epoch_key`), запрашивая
  пакеты ключей (`group_key_request_v1`) по экспоненциальному тайм-ауту (не чаще раза в 60 с).
- **Риск постоянной недоступности (G-07)**: При безвозвратной потере устройства владельца
  без предварительной передачи владения (`ownership_transferred`) группа не может
  перейти в новую эпоху. Решение задачи децентрализованного кворумного исключения и
  автоматического перевыбора владельца отложено до интеграции полноценного MLS (RFC 9420).

### 23.2. Наследуемые v1-группы с необновлённым владельцем
- Группы, созданные в v1, продолжают функционировать под suite `2pchat-epoch-aes256gcm-ed25519-v1`
  до тех пор, пока все участники не подтвердят поддержку v2 (`supports_v2 = true`), либо
  пока владелец не применит принудительное обновление (`forceOverride`).
- Действия администраторов по добавлению и удалению участников в v1-эпохах остаются
  дедуплицированными, каноничными и детерминированными (дедупликация и валидация
  сохраняют обратную совместимость для предотвращения рассинхронизации старых журналов).

### 23.3. Границы совершенной прямой секретности (G-05 PFS)
- Модель безопасности эпохи опирается на симметричный общий секрет (`epoch_secret`).
- Компрометация ключа эпохи раскрывает все сообщения внутри данной эпохи. Совершенная
  прямая секретность на уровне отдельных сообщений (per-message ratchet / Double Ratchet
  внутри группы) не реализуется в рамках схемы плоских эпох и появится только при
  переходе на древовидные структуры ключей (TreeKEM / MLS).

### 23.4. Квоты репликации и защита от амплификации (G-08)
- Планировщик реплик HRW (Rendezvous Hashing) ограничивает число реплицирующих узлов
  (фиксированная квота реплик, по умолчанию 3 для крупных групп).
- Запросы синхронизации истории (`group_sync_request_v1`), блоков вложений
  (`group_attachment_request_v1`) и запросы ключей (`group_key_request_v1`) строго
  ограничены по частоте (rate-limiting 2 с на узел) и пагинированы (не более 64 эпох
  на один ответный пакет ключей), что исключает DoS и атаки усиления трафика через реплики.

### 23.5. Причина отклонения после рукопожатия (SEC-03)
- Группы с флагом `tor_only_group = 1` устанавливают абсолютный пол строгости
  транспорта (`TOR_ONLY = 100`).
- Любые входящие clearnet/direct соединения от пиров, состоящих в Tor-only группах,
  принудительно сбрасываются после завершения рукопожатия сессии с явным
  указанием причины: `Group transport policy floor violation (peer policy cannot weaken group Tor-only constraint)`.
  Даунгрейд политики по инициативе отдельного пира или владельца группы блокируется на
  уровне базы данных и сетевого шлюза.

