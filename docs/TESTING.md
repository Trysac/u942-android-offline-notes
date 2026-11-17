# Testing

## Structure

The project uses JUnit 5 and jqwik for unit and property-based testing. Tests are located in `src/test/kotlin/io/github/trysac/android_offline_notes/`.

## Test Files

- `OfflineNotesAcceptanceTest.kt`: Covers core acceptance criteria, including synchronization states, network failures (`NetworkUnavailableException`), merge rules, tombstone persistence, and validation limits.
- `SyncConvergencePropertyTest.kt`: Property-based tests exercising random interleavings of create, edit, delete, and sync actions across 3 concurrent devices with simulated lost push/pull responses.
- `TextPropertiesTest.kt`: Property-based idempotency tests for the `Text` helper.
- `TextTest.kt`: Unit tests for `Text.normalize` trimming and blank input handling.
