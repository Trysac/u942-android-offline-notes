package io.github.trysac.android_offline_notes

/**
 * Storage port holding notes, per-field LWW versions, the pending outbox queue, and the pull cursor.
 */
@Suppress("TooManyFunctions")
public interface LocalStore {
    /**
     * Returns the note with the given [id], including deleted tombstones, or `null` if unknown.
     */
    public fun getNote(id: String): Note?

    /**
     * Persists or updates a [note].
     */
    public fun putNote(note: Note)

    /**
     * Returns all stored notes (both active and deleted).
     */
    public fun getAllNotes(): List<Note>

    /**
     * Returns the last applied [FieldVersion] for [noteId] and [field], or `null` if never written.
     */
    public fun getFieldVersion(
        noteId: String,
        field: String,
    ): FieldVersion?

    /**
     * Records the winning [version] for [noteId] and [field].
     */
    public fun putFieldVersion(
        noteId: String,
        field: String,
        version: FieldVersion,
    )

    /**
     * Appends an [operation] to the local outbox queue.
     */
    public fun appendOutbox(operation: Operation)

    /**
     * Returns pending operations from the outbox in insertion order, up to [limit] if specified.
     */
    public fun getOutbox(limit: Int = Int.MAX_VALUE): List<Operation>

    /**
     * Returns the current number of pending operations in the outbox.
     */
    public fun getOutboxSize(): Int

    /**
     * Removes all operations from the outbox whose `opId` is in [opIds].
     */
    public fun removeOutbox(opIds: Set<String>)

    /**
     * Returns the opaque cursor from the last completed pull page, or `null` if none.
     */
    public fun getPullCursor(): String?

    /**
     * Saves the opaque [cursor] for the next pull request.
     */
    public fun setPullCursor(cursor: String?)
}

/**
 * Thread-safe in-memory reference implementation of [LocalStore] with bounded capacity.
 *
 * @param maxNotes Maximum number of note records retained in memory.
 */
@Suppress("TooManyFunctions")
public class InMemoryLocalStore(
    private val maxNotes: Int = DEFAULT_MAX_NOTES,
) : LocalStore {
    private val lock = Any()
    private val notes = LinkedHashMap<String, Note>()
    private val fieldVersions = HashMap<String, FieldVersion>()
    private val outbox = LinkedHashMap<String, Operation>()
    private var pullCursor: String? = null

    /**
     * Snapshot of all pending operations currently in the outbox.
     */
    public val outboxOperations: List<Operation>
        get() = getOutbox()

    override fun getNote(id: String): Note? =
        synchronized(lock) {
            notes[id]
        }

    override fun putNote(note: Note): Unit =
        synchronized(lock) {
            if (!notes.containsKey(note.id) && notes.size >= maxNotes) {
                val oldestKey = notes.keys.firstOrNull()
                if (oldestKey != null) {
                    notes.remove(oldestKey)
                }
            }
            notes[note.id] = note
        }

    override fun getAllNotes(): List<Note> =
        synchronized(lock) {
            notes.values.toList()
        }

    override fun getFieldVersion(
        noteId: String,
        field: String,
    ): FieldVersion? =
        synchronized(lock) {
            fieldVersions["$noteId:$field"]
        }

    override fun putFieldVersion(
        noteId: String,
        field: String,
        version: FieldVersion,
    ): Unit =
        synchronized(lock) {
            fieldVersions["$noteId:$field"] = version
        }

    override fun appendOutbox(operation: Operation): Unit =
        synchronized(lock) {
            outbox[operation.opId] = operation
        }

    override fun getOutbox(limit: Int): List<Operation> =
        synchronized(lock) {
            if (limit == Int.MAX_VALUE) outbox.values.toList() else outbox.values.take(limit)
        }

    override fun getOutboxSize(): Int =
        synchronized(lock) {
            outbox.size
        }

    override fun removeOutbox(opIds: Set<String>): Unit =
        synchronized(lock) {
            for (id in opIds) {
                outbox.remove(id)
            }
        }

    override fun getPullCursor(): String? =
        synchronized(lock) {
            pullCursor
        }

    override fun setPullCursor(cursor: String?): Unit =
        synchronized(lock) {
            pullCursor = cursor
        }

    /**
     * Constants for [InMemoryLocalStore].
     */
    public companion object {
        /** Default upper bound on in-memory stored notes. */
        public const val DEFAULT_MAX_NOTES: Int = 50_000
    }
}
