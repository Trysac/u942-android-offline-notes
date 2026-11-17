# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added offline-first notes core (`Note`, `Operation`, `LocalStore`, `InMemoryLocalStore`, `NotesRepository`, `RemoteApi`, `SyncEngine`, `SyncResult`, `NotesException`, `NetworkUnavailableException`) with per-field LWW merge and idempotent outbox sync.
- Initial project structure from the factory utility template.
