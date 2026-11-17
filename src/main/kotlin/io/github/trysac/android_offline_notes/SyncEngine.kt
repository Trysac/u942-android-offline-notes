package io.github.trysac.android_offline_notes

/**
 * Coordinates pushing batched outbox operations to a [RemoteApi] and pulling remote change pages
 * into a [NotesRepository].
 *
 * @property repository Local notes repository and merge engine.
 * @property remote Remote backend transport port.
 * @property pageSize Maximum number of operations per pull page (defaults to [DEFAULT_PAGE_SIZE]).
 */
public class SyncEngine(
    public val repository: NotesRepository,
    public val remote: RemoteApi,
    public val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
            throw NotesException(
                code = NotesException.CODE_INVALID_PAGE,
                message = "pageSize must be between 1 and $MAX_PAGE_SIZE",
            )
        }
    }

    /**
     * Executes one full synchronization cycle:
     * 1. Pushes pending outbox operations in batches of [PUSH_BATCH_SIZE], removing only operations
     *    whose `opId` is acknowledged by the server.
     * 2. Pulls remote change pages of size [pageSize] until `nextCursor` is `null` or the page is empty,
     *    applying each remote operation through [NotesRepository.applyOperation].
     *
     * If [NetworkUnavailableException] is thrown at any point, stops immediately and returns a
     * [SyncResult] with status `"offline"` and the counts accumulated so far.
     */
    public fun sync(): SyncResult {
        var pushedCount = 0
        var pulledCount = 0
        try {
            pushedCount = pushOutboxBatches()
            pulledCount = pullRemoteChanges()
            return SyncResult(status = SyncResult.STATUS_OK, pushed = pushedCount, pulled = pulledCount)
        } catch (_: NetworkUnavailableException) {
            return SyncResult(status = SyncResult.STATUS_OFFLINE, pushed = pushedCount, pulled = pulledCount)
        }
    }

    private fun pushOutboxBatches(): Int {
        var totalPushed = 0
        val store = repository.store
        var moreBatches = true
        while (moreBatches) {
            val batch = store.getOutbox(PUSH_BATCH_SIZE)
            if (batch.isEmpty()) {
                moreBatches = false
            } else {
                val result = remote.push(batch)
                val batchIds = batch.map { it.opId }.toSet()
                val validAcked = result.ackedOpIds.intersect(batchIds)
                if (validAcked.isEmpty()) {
                    moreBatches = false
                } else {
                    store.removeOutbox(validAcked)
                    totalPushed += validAcked.size
                }
            }
        }
        return totalPushed
    }

    private fun pullRemoteChanges(): Int {
        var totalPulled = 0
        val store = repository.store
        var cursor = store.getPullCursor()
        var pagesFetched = 0
        var hasMore = true
        while (hasMore) {
            if (pagesFetched >= MAX_PULL_PAGES) {
                throw NotesException(NotesException.CODE_INVALID_PAGE, "Exceeded pull pages limit")
            }
            val page = remote.pull(cursor, pageSize)
            pagesFetched++
            if (page.operations.size > pageSize) {
                throw NotesException(NotesException.CODE_INVALID_PAGE, "Page exceeded limit $pageSize")
            }
            for (op in page.operations) {
                repository.applyOperation(op)
                totalPulled++
            }
            val next = page.nextCursor
            if (next != null) {
                store.setPullCursor(next)
            }
            if (next == null || next == cursor || page.operations.isEmpty()) {
                hasMore = false
            } else {
                cursor = next
            }
        }
        return totalPulled
    }

    /**
     * Constants and pagination defaults for [SyncEngine].
     */
    public companion object {
        /** Fixed batch size for pushing outbox operations. */
        public const val PUSH_BATCH_SIZE: Int = 100

        /** Default page size for pulling remote operations. */
        public const val DEFAULT_PAGE_SIZE: Int = 100

        /** Maximum allowed page size for pulling remote operations. */
        public const val MAX_PAGE_SIZE: Int = 10_000

        /** Safety bound on the number of pages pulled in a single sync cycle. */
        public const val MAX_PULL_PAGES: Int = 100_000
    }
}
