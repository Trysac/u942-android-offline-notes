# Per-field Last-Writer-Wins registers and idempotent operation outbox

Status: accepted (generated, pending human review)

## Context

Offline-first mobile note applications must support creating, editing, and deleting notes without network connectivity, then synchronizing across multiple devices without duplicating notes on dropped responses, losing concurrent edits to different fields, or resurrecting deleted notes.

## Decision

Model every mutation as an idempotent Operation identified by opId and stamped with (timestamp, deviceId). Merge title and body as independent Last-Writer-Wins registers ordered lexicographically by (timestamp, deviceId), treat deleted as a permanent monotonic tombstone, and synchronize by pushing outbox batches of 100 before pulling paginated remote operations.

## Consequences

Retried pushes after lost responses are deduplicated by opId, concurrent edits to title and body on different devices converge without conflict UI, and all devices deterministically converge to identical note sets.
