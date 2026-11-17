package io.github.trysac.android_offline_notes

/**
 * Base exception for all domain and validation errors in the offline notes library.
 *
 * @property code Stable machine-readable error code (`too_long`, `outbox_full`, `not_found`, `invalid_page`).
 * @param message Human-readable explanation of the failure (never containing secrets or >200 chars of raw input).
 * @param cause Optional underlying cause.
 */
public open class NotesException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    /**
     * Standard error codes for [NotesException].
     */
    public companion object {
        /** Error code when a note title, body, or identifier exceeds the maximum length. */
        public const val CODE_TOO_LONG: String = "too_long"

        /** Error code when the pending outbox queue has reached its capacity limit. */
        public const val CODE_OUTBOX_FULL: String = "outbox_full"

        /** Error code when an edit or delete targets a note ID that does not exist or is deleted. */
        public const val CODE_NOT_FOUND: String = "not_found"

        /** Error code when pagination parameters or remote page responses are invalid. */
        public const val CODE_INVALID_PAGE: String = "invalid_page"
    }
}

/**
 * Thrown by a [RemoteApi] implementation when the network or remote server cannot be reached.
 *
 * @param message Description of the connectivity failure.
 * @param cause Optional underlying transport exception.
 */
public class NetworkUnavailableException(
    message: String = "Network is unavailable",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
