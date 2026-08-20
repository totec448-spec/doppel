# Native persistence layer

This package is the complete low-level persistence boundary for the Android bot. It deliberately
uses `SQLiteOpenHelper` instead of Room/KSP so startup, generated-code size and idle work remain
minimal. Repository calls are blocking and must run on the application's existing IO dispatcher.
Keeping `BotRepository` open starts no thread, timer, observer or polling loop.

## Files

- `BotDatabase.kt`: WAL/foreign-key configuration and non-destructive schema lifecycle.
- `BotDbSchema.kt`: idempotent versioned SQL, indexes and hard retention limits.
- `DbModels.kt`: typed records shared with runtime and UI layers.
- `BotRepository.kt`: transactional read/write, dedupe, lease and maintenance operations.

## Schema ownership

| Table | Purpose | Ownership/retention |
| --- | --- | --- |
| `chats` | Lightweight chat metadata and UI list state | Explicitly deleted |
| `messages` | Normalized inbound/outbound history | 5,000/chat, 100,000 total |
| `processed_events` | Inbound event idempotency | Expiry plus 50,000 newest |
| `chat_memory` | Per-chat summary/facts | Cascades with chat |
| `chat_memory_history` | Bounded prior chat-memory revisions for timeline inspection/edit recovery | Cascades with chat/conversation |
| `personas` | Persona definition and prompt | Explicitly deleted |
| `persona_memory` | Persona-global summary/facts | Cascades with persona |
| `persona_assignments` | Chat-to-persona selection | Cascades with chat/persona |
| `access_entries` | Allow, group-allow, block and admin lists | Explicitly deleted |
| `scoped_settings` | Global/chat/group/persona runtime preferences | Explicitly deleted |
| `proactive_state` | Due time, counters, cooldown and expiring lease | Cascades with chat |
| `media_analysis_cache` | Analysis JSON keyed by content hash/model version | Expiry/LRU, 2,000 |
| `outbound_safety_ledger` | Shared final-send dedupe and decision record | Expiry, 50,000 |
| `activity_log` | Structured diagnostics for UI/export | 20,000 newest |
| `bridge_outbox` | Durable bridge operations with retry leases | 50,000 active + 10,000 history |
| `schema_migrations`, `db_meta` | Schema/maintenance bookkeeping | Internal |

Text and JSON columns have SQL size checks to prevent a corrupt bridge payload from growing the
database without bound. High-volume list queries use keyset ordering and hard query limits.

## Consistency contracts

1. `storeInboundBatch` claims webhook/bridge event IDs and stores normalized messages in one
   non-exclusive WAL transaction. Replayed event and message IDs use `INSERT OR IGNORE`.
2. Every send path calls `reserveOutbound` before transmitting. The unique dedupe key makes the
   controller-level safety decision authoritative across replies, commands, retries and proactive
   sends.
3. `claimDueProactive` and `claimBridgeOperations` use conditional updates plus expiring leases.
   Process death cannot permanently strand work.
4. Outbox acknowledgement/retry/dead-letter operations require the same lease owner, preventing a
   stale worker from completing another worker's claim.
5. Maintenance runs only after writes cross a threshold and at most once per interval. There is no
   idle cleanup job.
6. Cache reads touch LRU time no more than once every six hours.
7. `loadSettingsSnapshot` returns all scoped settings with `settings_revision`.
   `compareAndSwapSettings` applies a batch only when that revision still matches; deletes,
   upserts, the increment and returned full snapshot belong to the same transaction. Ordinary
   `putSetting(s)`/`deleteSetting` calls increment the same revision, so SettingsPersistence needs
   no SharedPreferences mirror.

`SettingsScopes` defines the adapter mapping: global, app and retained-legacy maps use an empty
`scope_id` and their map key as `setting_key`; persona/proactive contact maps use the contact ID as
`scope_id` and the fixed `setting_key = "value"`.

## Secrets

This database is not credential storage. API keys, access/refresh tokens, passwords, Baileys auth
state, WhatsApp session material and encryption keys must live behind an Android Keystore-backed
credential component outside this package. `putSetting` rejects common credential-like key names;
credential controls may persist only a `*_ref` logical Keystore alias with
`StoredSettingValueType.SECRET_REFERENCE`. Outbox and JSON callers must likewise never embed credentials
in payloads.

## Schema changes

Increment `BotDbSchema.VERSION`, add an ordered migration branch in `BotDbSchema.upgrade`, and keep
all DDL idempotent (`IF NOT EXISTS`). Downgrades intentionally fail rather than silently deleting
chat history or safety state.
