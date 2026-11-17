package io.github.trysac.android_offline_notes

/**
 * Platform-free local repository managing notes, outbox enqueueing, and per-field LWW merging.
 *
 * @property store Underlying [LocalStore] persistence port.
 * @param clock Monotonic or wall-clock millisecond provider.
 * @param ids Unique ID generator used for note IDs and operation IDs.
 * @property deviceId Unique identifier for this client device.
 */
@Suppress("TooManyFunctions")
public class NotesRepository(
    public val store: LocalStore,
    private val clock: () -> Long,
    private val ids: () -> String,
    public val deviceId: String,
) {
    init {
        if (deviceId.isBlank() || deviceId.length > MAX_ID_LENGTH) {
            throw NotesException(
                code = NotesException.CODE_TOO_LONG,
                message = "deviceId must be non-blank and at most $MAX_ID_LENGTH characters",
            )
        }
    }

    /**
     * Current pending operations in the underlying store's outbox.
     */
    public val outbox: List<Operation>
        get() = store.getOutbox()

    /**
     * Creates a new note with [title] and [body], persisting it locally and recording creation
     * operations in the outbox.
     *
     * @throws NotesException with code `too_long` if [title] or [body] exceeds length limit.
     * @throws NotesException with code `outbox_full` if outbox has reached [MAX_OUTBOX_SIZE].
     */
    public fun create(
        title: String,
        body: String,
    ): Note {
        validateTitleAndBody(title, body)
        val noteId = nextValidatedId()
        val now = clock()

        ensureOutboxCapacity(1)
        val createOp =
            Operation(
                opId = nextValidatedId(),
                noteId = noteId,
                field = "$FIELD_CREATE_PREFIX${title.length}",
                value = title + body,
                timestamp = now,
                deviceId = deviceId,
            )
        applyOperation(createOp)
        store.appendOutbox(createOp)
        return store.getNote(noteId)!!
    }

    /**
     * Edits the [title] and/or [body] of an existing active note with [id].
     *
     * @param id Target note identifier.
     * @param title New title, or `null` to leave the title unchanged.
     * @param body New body, or `null` to leave the body unchanged.
     * @return The updated [Note].
     * @throws NotesException with code `not_found` if [id] does not exist or has been deleted.
     * @throws NotesException with code `too_long` if [title] or [body] exceeds maximum length.
     * @throws NotesException with code `outbox_full` if the outbox cannot fit the new operation(s).
     */
    public fun edit(
        id: String,
        title: String? = null,
        body: String? = null,
    ): Note {
        validateId(id)
        val existing = store.getNote(id)
        if (existing == null || existing.deleted) {
            throw NotesException(
                code = NotesException.CODE_NOT_FOUND,
                message = "Note not found or deleted",
            )
        }
        validateTitleAndBody(title ?: "", body ?: "")

        val opsToCreate = (if (title != null) 1 else 0) + (if (body != null) 1 else 0)
        if (opsToCreate == 0) {
            return existing
        }
        ensureOutboxCapacity(opsToCreate)
        val now = clock()

        if (title != null) {
            val op =
                Operation(
                    opId = nextValidatedId(),
                    noteId = id,
                    field = Operation.FIELD_TITLE,
                    value = title,
                    timestamp = now,
                    deviceId = deviceId,
                )
            applyOperation(op)
            store.appendOutbox(op)
        }
        if (body != null) {
            val op =
                Operation(
                    opId = nextValidatedId(),
                    noteId = id,
                    field = Operation.FIELD_BODY,
                    value = body,
                    timestamp = now,
                    deviceId = deviceId,
                )
            applyOperation(op)
            store.appendOutbox(op)
        }
        return store.getNote(id)!!
    }

    /**
     * Permanently marks the note with [id] as deleted (tombstone) and enqueues a delete operation.
     *
     * @param id Target note identifier.
     * @throws NotesException with code `not_found` if [id] does not exist.
     * @throws NotesException with code `outbox_full` if the outbox is at capacity.
     */
    public fun delete(id: String) {
        validateId(id)
        val existing = store.getNote(id)
        if (existing == null) {
            throw NotesException(
                code = NotesException.CODE_NOT_FOUND,
                message = "Note not found",
            )
        }
        ensureOutboxCapacity(1)
        val now = clock()
        val op =
            Operation(
                opId = nextValidatedId(),
                noteId = id,
                field = Operation.FIELD_DELETED,
                value = "true",
                timestamp = now,
                deviceId = deviceId,
            )
        applyOperation(op)
        store.appendOutbox(op)
    }

    /**
     * Returns all non-deleted notes sorted by [Note.updatedAt] descending, breaking ties by [Note.id] ascending.
     */
    public fun list(): List<Note> =
        store
            .getAllNotes()
            .asSequence()
            .filter { !it.deleted }
            .sortedWith(compareByDescending<Note> { it.updatedAt }.thenBy { it.id })
            .toList()

    /**
     * Returns the note with [id] (including deleted tombstones), or `null` if not present.
     */
    public fun get(id: String): Note? {
        validateId(id)
        return store.getNote(id)
    }

    /**
     * Applies an [Operation] (local or remote) to the store using per-field Last-Writer-Wins
     * ordered by `(timestamp, deviceId)` and permanent deletion tombstones.
     */
    public fun applyOperation(op: Operation) {
        validateId(op.opId)
        validateId(op.noteId)
        validateId(op.deviceId)
        val incomingVersion = FieldVersion(op.timestamp, op.deviceId)
        if (op.field.startsWith(FIELD_CREATE_PREFIX)) {
            applyCreateOperation(op, incomingVersion)
            return
        }
        when (op.field) {
            Operation.FIELD_TITLE -> {
                validateTitleAndBody(op.value, "")
                mergeField(op.noteId, Operation.FIELD_TITLE, op.value, incomingVersion)
            }

            Operation.FIELD_BODY -> {
                validateTitleAndBody("", op.value)
                mergeField(op.noteId, Operation.FIELD_BODY, op.value, incomingVersion)
            }

            Operation.FIELD_DELETED -> {
                applyDeleteOperation(op, incomingVersion)
            }

            else -> {
                throw NotesException(NotesException.CODE_TOO_LONG, "Unsupported operation field")
            }
        }
    }

    private fun applyCreateOperation(
        op: Operation,
        incomingVersion: FieldVersion,
    ) {
        val titleLen =
            op.field
                .removePrefix(FIELD_CREATE_PREFIX)
                .toIntOrNull()
                ?.takeIf { it >= 0 && it <= op.value.length }
                ?: throw NotesException(NotesException.CODE_TOO_LONG, "Malformed create operation field")
        val newTitle = op.value.substring(0, titleLen)
        val newBody = op.value.substring(titleLen)
        validateTitleAndBody(newTitle, newBody)
        mergeField(op.noteId, Operation.FIELD_TITLE, newTitle, incomingVersion)
        mergeField(op.noteId, Operation.FIELD_BODY, newBody, incomingVersion)
    }

    private fun applyDeleteOperation(
        op: Operation,
        incomingVersion: FieldVersion,
    ) {
        if (op.value != "true") return
        val currentDelVer = store.getFieldVersion(op.noteId, Operation.FIELD_DELETED)
        if (currentDelVer == null || incomingVersion > currentDelVer) {
            store.putFieldVersion(op.noteId, Operation.FIELD_DELETED, incomingVersion)
        }
        val base =
            store.getNote(op.noteId) ?: Note(
                id = op.noteId,
                title = "",
                body = "",
                updatedAt = op.timestamp,
                deleted = true,
            )
        val updatedTime = maxOf(base.updatedAt, op.timestamp)
        store.putNote(base.copy(updatedAt = updatedTime, deleted = true))
    }

    private fun mergeField(
        noteId: String,
        field: String,
        value: String,
        incomingVersion: FieldVersion,
    ) {
        val currentVersion = store.getFieldVersion(noteId, field)
        val wins = currentVersion == null || incomingVersion >= currentVersion
        if (wins) {
            store.putFieldVersion(noteId, field, incomingVersion)
        }
        val base =
            store.getNote(noteId) ?: Note(
                id = noteId,
                title = "",
                body = "",
                updatedAt = incomingVersion.timestamp,
                deleted = false,
            )
        val updatedTitle = if (field == Operation.FIELD_TITLE && wins) value else base.title
        val updatedBody = if (field == Operation.FIELD_BODY && wins) value else base.body
        val updatedTime = if (wins) maxOf(base.updatedAt, incomingVersion.timestamp) else base.updatedAt

        store.putNote(
            base.copy(
                title = updatedTitle,
                body = updatedBody,
                updatedAt = updatedTime,
                deleted = base.deleted,
            ),
        )
    }

    private fun ensureOutboxCapacity(additional: Int) {
        if (store.getOutboxSize() + additional > MAX_OUTBOX_SIZE) {
            throw NotesException(
                code = NotesException.CODE_OUTBOX_FULL,
                message = "Outbox limit of $MAX_OUTBOX_SIZE pending operations exceeded",
            )
        }
    }

    private fun nextValidatedId(): String = ids().also { validateId(it) }

    private fun validateId(id: String) {
        if (id.isEmpty() || id.length > MAX_ID_LENGTH) {
            throw NotesException(
                code = NotesException.CODE_TOO_LONG,
                message = "Identifier must be between 1 and $MAX_ID_LENGTH characters",
            )
        }
    }

    private fun validateTitleAndBody(
        title: String,
        body: String,
    ) {
        if (title.length > MAX_TITLE_LENGTH) {
            throw NotesException(
                code = NotesException.CODE_TOO_LONG,
                message = "Title length ${title.length} exceeds limit of $MAX_TITLE_LENGTH",
            )
        }
        if (body.length > MAX_BODY_LENGTH) {
            throw NotesException(
                code = NotesException.CODE_TOO_LONG,
                message = "Body length ${body.length} exceeds limit of $MAX_BODY_LENGTH",
            )
        }
    }

    /**
     * Constants and validation limits for [NotesRepository].
     */
    public companion object {
        /** Maximum allowed length of a note title in characters. */
        public const val MAX_TITLE_LENGTH: Int = 200

        /** Maximum allowed length of a note body in characters. */
        public const val MAX_BODY_LENGTH: Int = 100_000

        /** Maximum number of pending operations allowed in the outbox. */
        public const val MAX_OUTBOX_SIZE: Int = 10_000

        /** Maximum allowed length of note, operation, or device identifiers. */
        public const val MAX_ID_LENGTH: Int = 256

        private const val FIELD_CREATE_PREFIX: String = "create:"
    }
}
