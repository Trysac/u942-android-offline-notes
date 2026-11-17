# Security notes

## Threat model

`io.github.trysac:android-offline-notes` is a pure library without I/O. 

### Defended
- **Supply chain attacks**: Dependencies and plugins are pinned to exact versions, resolved via `gradle.lockfile`, and distributions are verified by SHA-256.
- **Secrets leakage**: Secret scanning is enforced locally and in CI; `.env` files are ignored.
- **Insecure code**: Compiler warnings are treated as errors; static analysis via `detekt` and the factory's Semgrep rules are enforced.
- **Untrusted input**: Input validation is performed for titles, bodies, and IDs; explicit `NotesException` codes are thrown for validation failures.

### Not defended
- **Remote server security**: The `RemoteApi` implementation is the caller's responsibility. This library does not validate the transport layer security of the server implementation.
- **Data storage security**: The `LocalStore` implementation is the caller's responsibility. The library does not encrypt local data by default.
