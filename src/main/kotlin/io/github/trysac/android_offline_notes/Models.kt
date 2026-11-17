package io.github.trysac.android_offline_notes

/**
 * Immutable snapshot of a note stored locally.
 *
 * @property id Unique note identifier.
 * @property title Note title (at most [NotesRepository.MAX_TITLE_LENGTH] characters).
 * @property body Note body text (at most [NotesRepository.MAX_BODY_LENGTH] characters).
 * @property updatedAt Highest timestamp across all applied operations on this note.
 * @property deleted Whether this note has been permanently deleted (tombstone).
 */
public data class Note(
    public val id: String,
    public val title: String,
    public val body: String,
    public val updatedAt: Long,
    public val deleted: Boolean,
)

/**
 * An idempotent per-field state mutation operation targeting a single [Note].
 *
 * @property opId Globally unique identifier for this operation.
 * @property noteId Identifier of the target note.
 * @property field Name of the mutated field: `title`, `body`, or `deleted`.
 * @property value Serialized field value (`"true"` for `deleted`).
 * @property timestamp Logical or wall-clock timestamp when the mutation occurred.
 * @property deviceId Unique identifier of the originating device, used to break timestamp ties.
 */
public data class Operation(
    public val opId: String,
    public val noteId: String,
    public val field: String,
    public val value: String,
    public val timestamp: Long,
    public val deviceId: String,
) {
    /**
     * Constants and field names for [Operation].
     */
    public companion object {
        /** Field name for the note's title register. */
        public const val FIELD_TITLE: String = "title"

        /** Field name for the note's body register. */
        public const val FIELD_BODY: String = "body"

        /** Field name for the note's permanent deletion tombstone register. */
        public const val FIELD_DELETED: String = "deleted"
    }
}

/**
 * Ordering metadata for a single Last-Writer-Wins (LWW) field register.
 *
 * @property timestamp Timestamp of the winning write.
 * @property deviceId Device ID of the winning write.
 */
public data class FieldVersion(
    public val timestamp: Long,
    public val deviceId: String,
) : Comparable<FieldVersion> {
    override fun compareTo(other: FieldVersion): Int {
        val timeCmp = this.timestamp.compareTo(other.timestamp)
        return if (timeCmp != 0) timeCmp else this.deviceId.compareTo(other.deviceId)
    }
}

/**
 * Result of executing a synchronization cycle via [SyncEngine.sync].
 *
 * @property status Either `"ok"` when complete or `"offline"` when interrupted by [NetworkUnavailableException].
 * @property pushed Total number of outbox operations acknowledged by the remote server during this run.
 * @property pulled Total number of remote operations fetched and processed during this run.
 */
public data class SyncResult(
    public val status: String,
    public val pushed: Int,
    public val pulled: Int,
) {
    /**
     * Constants and status codes for [SyncResult].
     */
    public companion object {
        /** Status returned when both push and pull phases complete without network errors. */
        public const val STATUS_OK: String = "ok"

        /** Status returned when a [NetworkUnavailableException] aborts synchronization. */
        public const val STATUS_OFFLINE: String = "offline"
    }
}

/**
 * Server acknowledgement returned by [RemoteApi.push].
 *
 * @property ackedOpIds Identifiers of the pushed operations that the server has durably recorded.
 */
public data class PushResult(
    public val ackedOpIds: Set<String>,
)

/**
 * A page of operations returned by [RemoteApi.pull].
 *
 * @property operations Ordered list of operations in this page.
 * @property nextCursor Opaque cursor for fetching the next page, or `null` when caught up.
 */
public data class ChangePage(
    public val operations: List<Operation>,
    public val nextCursor: String?,
)
