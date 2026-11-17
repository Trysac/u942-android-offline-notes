# Project record

## Objective

Offline-first notes core for Kotlin with an outbox of idempotent operations, per-field last-writer-wins merge and duplicate-free sync against any backend port.

## Scope

- The library core: local note repository, `LocalStore` interface, `SyncEngine` for batched push/pull, and `NotesRepository` for LWW merging.
- Reference implementation: `InMemoryLocalStore`.
- Compliance with the public API and domain error codes.

## Out-of-scope

- Android-specific components: Compose UI, Room DAO implementations, WorkManager scheduling, or content provider logic.
- Rich text, attachments, or authentication mechanisms.

## Effort metrics

| Metric | Value |
|---|---|
| Tokens (input / output) | 4730396 / 57500 |
| Hours (human) | pending measurement |
| Cost (USD) | 1.7134 |

## Factory run u942 (measured from API usage)

| Date (UTC) | Phase | Model | Batch | Input | Output | Cache write | Cache read | Cost (USD) |
|---|---|---|---|---|---|---|---|---|
| 2026-11-17 | generation | google/gemini-4-argon | yes | 6403 | 18049 | 0 | 0 | 0.3866 |
| 2026-11-17 | generation | google/gemini-4-argon | yes | 6403 | 17128 | 0 | 0 | 0.3682 |
| 2026-11-17 | fix-1 | google/gemini-3.8-flash | no | 135236 | 8973 | 0 | 1280666 | 0.2311 |
| 2026-11-17 | fix-2 | google/gemini-3.8-flash | no | 202476 | 7190 | 0 | 1335079 | 0.2790 |
| 2026-11-17 | fix-3 | google/gemini-3.8-flash | no | 121770 | 1992 | 0 | 557107 | 0.1406 |
| 2026-11-17 | audit | google/gemini-3.8-flash | no | 203162 | 1888 | 0 | 685675 | 0.2109 |
| 2026-11-17 | threat-model | google/gemini-3.8-flash | no | 109473 | 716 | 0 | 67011 | 0.0898 |
| 2026-11-17 | docs | google/gemini-3.1-flash-lite | no | 19935 | 1564 | 0 | 0 | 0.0073 |

Total: $1.7134. Prices: vertex line, as measured by its adapter. Providers serve the current version of each model id, so the date tells which version answered; versions, launch stages and price sources per line are in the factory's model register (docs/MODELS.md).
