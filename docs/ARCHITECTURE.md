# Architecture

## Overview

`android-offline-notes` separates the synchronization and conflict-resolution model from Android framework concerns (Room, WorkManager, Compose, HTTP clients).

## Core Components

1. **`LocalStore` Port & `InMemoryLocalStore` Adapter**
   Stores materialized `Note` records, per-field `(timestamp, deviceId)` `FieldVersion` metadata, an ordered outbox of unacknowledged `Operation` entries, and the latest `pullCursor`.

2. **`NotesRepository`**
   Validates input bounds (`title <= 200`, `body <= 100_000`, `outbox <= 10_000`), generates `Operation` records stamped with the injected `clock`, `ids`, and `deviceId`, applies them locally via `applyOperation`, and appends them to the outbox.

3. **Per-Field LWW Merge with Permanent Tombstones**
   - Each mutable property (`title`, `body`) tracks its highest `FieldVersion(timestamp, deviceId)`.
   - An incoming operation updates a field if and only if its `(timestamp, deviceId)` is greater than or equal to the current version.
   - `deleted` transitions monotonically from `false` to `true`. Once `deleted == true`, the note remains deleted even if a later edit for `title` or `body` arrives from another device.

4. **`SyncEngine` & `RemoteApi` Port**
   - **Push Phase**: Reads up to 100 operations from the outbox, sends them via `RemoteApi.push`, and removes only the `ackedOpIds` returned by the server. Lost responses leave operations in the outbox so retries resend the exact same `opId`s.
   - **Pull Phase**: Repeatedly invokes `RemoteApi.pull(cursor, pageSize)` until exhausted, feeding each remote operation into `NotesRepository.applyOperation` and advancing the persisted pull cursor.
   - **Offline Handling**: Catching `NetworkUnavailableException` immediately halts the cycle and returns `SyncResult(status = "offline", pushed, pulled)` without losing queued mutations.
