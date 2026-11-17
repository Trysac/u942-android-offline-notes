package io.github.trysac.android_offline_notes

/**
 * Network port for pushing local operations and pulling remote operation pages.
 */
public interface RemoteApi {
    /**
     * Sends a batch of idempotent [ops] to the remote backend.
     *
     * @param ops Operations from the local outbox.
     * @return [PushResult] listing the operation IDs acknowledged by the server.
     * @throws NetworkUnavailableException if the request fails due to connectivity or transport loss.
     */
    public fun push(ops: List<Operation>): PushResult

    /**
     * Fetches a page of remote operations after [cursor].
     *
     * @param cursor Opaque pagination cursor from the previous page, or `null` to start from the beginning.
     * @param limit Maximum number of operations to return in the page.
     * @return [ChangePage] containing operations and the next cursor.
     * @throws NetworkUnavailableException if the request fails due to connectivity or transport loss.
     */
    public fun pull(
        cursor: String?,
        limit: Int,
    ): ChangePage
}
