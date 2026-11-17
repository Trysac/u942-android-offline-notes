package io.github.trysac.android_offline_notes

/**
 * Deterministic in-memory test double for [RemoteApi] that deduplicates operations by `opId`,
 * computes server-side merged notes, and supports simulating offline failures or dropped push responses.
 */
class FakeServer : RemoteApi {
    var offline: Boolean = false
    var dropNextPushResponse: Boolean = false
    var dropNextPullResponse: Boolean = false

    private val appliedOpIds = LinkedHashSet<String>()
    private val log = ArrayList<Operation>()
    private val serverStore = InMemoryLocalStore()
    private val serverRepo =
        NotesRepository(
            store = serverStore,
            clock = { 0L },
            ids = { "srv" },
            deviceId = "server",
        )

    /** Total unique operations recorded on the server. */
    val appliedOperationsCount: Int
        get() = log.size

    /** Active (non-deleted) notes materialized on the server. */
    fun notes(): List<Note> = serverRepo.list()

    /** Returns a note on the server by id (including deleted tombstones). */
    fun getNote(id: String): Note? = serverRepo.get(id)

    override fun push(ops: List<Operation>): PushResult {
        if (offline) {
            throw NetworkUnavailableException("Server is offline")
        }
        val acked = LinkedHashSet<String>()
        for (op in ops) {
            if (appliedOpIds.add(op.opId)) {
                log.add(op)
                serverRepo.applyOperation(op)
            }
            acked.add(op.opId)
        }
        if (dropNextPushResponse) {
            dropNextPushResponse = false
            throw NetworkUnavailableException("Push response lost in transit")
        }
        return PushResult(ackedOpIds = acked)
    }

    override fun pull(
        cursor: String?,
        limit: Int,
    ): ChangePage {
        if (offline) {
            throw NetworkUnavailableException("Server is offline")
        }
        if (dropNextPullResponse) {
            dropNextPullResponse = false
            throw NetworkUnavailableException("Pull response lost in transit")
        }
        val startIndex = cursor?.toIntOrNull() ?: 0
        if (startIndex >= log.size) {
            return ChangePage(operations = emptyList(), nextCursor = null)
        }
        val endIndex = minOf(startIndex + limit, log.size)
        val slice = log.subList(startIndex, endIndex).toList()
        val nextCursor = if (endIndex < log.size) endIndex.toString() else endIndex.toString()
        val hasMore = endIndex < log.size
        return ChangePage(
            operations = slice,
            nextCursor = if (hasMore) nextCursor else endIndex.toString(),
        )
    }
}
