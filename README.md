# android-offline-notes

Offline-first notes core for Kotlin with an outbox of idempotent operations, per-field last-writer-wins merge and duplicate-free sync against any backend port.

## Overview

Note-taking apps on phones must keep working without a network and later sync without duplicating notes on retried requests, losing concurrent edits across fields, or resurrecting deleted notes. `android-offline-notes` provides the platform-free domain core:

- **`NotesRepository`**: Creates, edits, deletes, and queries notes against a `LocalStore` while recording idempotent per-field `Operation` items in a bounded outbox.
- **Per-field Last-Writer-Wins (LWW) Merge**: Each field (`title`, `body`, `deleted`) is an independent LWW register ordered by `(timestamp, deviceId)`. Concurrent edits to `title` on one device and `body` on another both survive. Deletion (`deleted = true`) is a permanent tombstone.
- **`SyncEngine`**: Pushes outbox operations in batches of 100, removes operations only after the server acknowledges their `opId`, and pulls paginated remote changes through the same merge function.

## Installation

Add the library dependency to your `build.gradle.kts`:

```kotlin
implementation("io.github.trysac:android-offline-notes:1.0.0")
```

## Usage

```kotlin
import io.github.trysac.android_offline_notes.InMemoryLocalStore
import io.github.trysac.android_offline_notes.NotesRepository

val store = InMemoryLocalStore()
var now = 1000L
var seq = 0
val repo = NotesRepository(store, clock = { now++ }, ids = { "id-${++seq}" }, deviceId = "dev-a")
val note = repo.create(title = "Groceries", body = "milk")
val updated = repo.edit(note.id, body = "milk, eggs")
println("${updated.title}: ${updated.body} (outbox=${repo.outbox.size})")
```

## Public API

| Type | Description |
|---|---|
| `Note` | Immutable note snapshot (`id`, `title`, `body`, `updatedAt`, `deleted`). |
| `Operation` | Idempotent field mutation (`opId`, `noteId`, `field`, `value`, `timestamp`, `deviceId`). |
| `LocalStore` / `InMemoryLocalStore` | Storage port for notes, field versions, outbox, and pull cursor. |
| `NotesRepository` | Local CRUD (`create`, `edit`, `delete`, `list`, `get`) and LWW merge. |
| `RemoteApi` | Network port with `push(ops)` and `pull(cursor, limit)`. |
| `SyncEngine` / `SyncResult` | Batched outbox push + paginated pull coordinator returning `ok` or `offline`. |
| `NotesException` / `NetworkUnavailableException` | Typed domain exceptions with codes `too_long`, `outbox_full`, `not_found`, `invalid_page`. |

## Why not just sync whole notes?

Syncing whole notes with `updatedAt` timestamps creates conflicts when different fields (title vs body) change concurrently. This library's per-field merge preserves both edits, while `opId` tracking makes retries safe against network failures.

See [docs/SECURITY.md](docs/SECURITY.md) for security details.
