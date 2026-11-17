package io.github.trysac.android_offline_notes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests covering all acceptance criteria, merge rules, bounds, and edge cases.
 */
class OfflineNotesAcceptanceTest {
    private class DeviceRig(
        val deviceId: String,
        val server: FakeServer,
        initialTime: Long = 1000L,
        pageSize: Int = 100,
    ) {
        val clock = AtomicLong(initialTime)
        private val idSeq = AtomicInteger(0)
        val store = InMemoryLocalStore()
        val repository =
            NotesRepository(
                store = store,
                clock = { clock.getAndIncrement() },
                ids = { "$deviceId-${idSeq.incrementAndGet()}" },
                deviceId = deviceId,
            )
        val syncEngine = SyncEngine(repository = repository, remote = server, pageSize = pageSize)
    }

    @Test
    fun offlineCreateEditAndRetryOnReconnectAndLostPushResponse() {
        val server = FakeServer()
        server.offline = true
        val dev = DeviceRig("dev-1", server)

        val n1 = dev.repository.create("Note 1", "Body 1")
        val n2 = dev.repository.create("Note 2", "Body 2")
        val n3 = dev.repository.create("Note 3", "Body 3")
        dev.repository.edit(n1.id, title = "Note 1 edited")

        assertEquals(3, dev.repository.list().size)
        assertEquals(4, dev.repository.outbox.size)

        val offlineSync = dev.syncEngine.sync()
        assertEquals("offline", offlineSync.status)
        assertEquals(0, offlineSync.pushed)
        assertEquals(4, dev.repository.outbox.size)

        // Server applies push, but the response is lost
        server.offline = false
        server.dropNextPushResponse = true
        val originalOpIds = dev.repository.outbox.map { it.opId }

        val droppedSync = dev.syncEngine.sync()
        assertEquals("offline", droppedSync.status)
        assertEquals(4, dev.repository.outbox.size)
        assertEquals(originalOpIds, dev.repository.outbox.map { it.opId })
        assertEquals(3, server.notes().size)

        // Retried sync sends the same operation ids; server applies none twice
        val firstOnlineSync = dev.syncEngine.sync()
        assertEquals("ok", firstOnlineSync.status)
        assertEquals(4, firstOnlineSync.pushed)
        assertEquals(0, dev.repository.outbox.size)
        assertEquals(3, server.notes().size)
        assertEquals(
            3,
            server
                .notes()
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(4, server.appliedOperationsCount)

        // Second sync pushes 0 operations and server still holds 3 notes
        val secondOnlineSync = dev.syncEngine.sync()
        assertEquals("ok", secondOnlineSync.status)
        assertEquals(0, secondOnlineSync.pushed)
        assertEquals(3, server.notes().size)
        assertEquals(listOf(n1.id, n2.id, n3.id).sorted(), server.notes().map { it.id }.sorted())
    }

    @Test
    fun networkReturnsStandardFlowPushesFourThenZero() {
        val server = FakeServer()
        server.offline = true
        val dev = DeviceRig("dev-main", server)

        val n1 = dev.repository.create("First", "1")
        dev.repository.create("Second", "2")
        dev.repository.create("Third", "3")
        dev.repository.edit(n1.id, body = "1-updated")

        assertEquals(3, dev.repository.list().size)
        assertEquals(4, dev.repository.outbox.size)
        assertEquals("offline", dev.syncEngine.sync().status)
        assertEquals(4, dev.repository.outbox.size)

        server.offline = false
        val sync1 = dev.syncEngine.sync()
        assertEquals("ok", sync1.status)
        assertEquals(4, sync1.pushed)
        assertEquals(3, server.notes().size)

        val sync2 = dev.syncEngine.sync()
        assertEquals("ok", sync2.status)
        assertEquals(0, sync2.pushed)
        assertEquals(3, server.notes().size)
    }

    @Test
    fun perFieldMergePreservesConcurrentTitleAndBodyAndBreaksTiesByDeviceId() {
        val server = FakeServer()
        val devA = DeviceRig("dev-a", server, initialTime = 500L)
        val devB = DeviceRig("dev-b", server, initialTime = 500L)

        // Create shared note on A and sync to B
        val created = devA.repository.create("Initial", "Initial body")
        devA.syncEngine.sync()
        devB.syncEngine.sync()

        // Device A sets title 'Groceries' at timestamp 1000
        devA.clock.set(1000L)
        devA.repository.edit(created.id, title = "Groceries")

        // Device B sets body 'milk' at 1001
        devB.clock.set(1001L)
        devB.repository.edit(created.id, body = "milk")

        // Sync both
        devA.syncEngine.sync()
        devB.syncEngine.sync()
        devA.syncEngine.sync()

        val noteOnA = devA.repository.get(created.id)!!
        val noteOnB = devB.repository.get(created.id)!!
        assertEquals("Groceries", noteOnA.title)
        assertEquals("milk", noteOnA.body)
        assertEquals("Groceries", noteOnB.title)
        assertEquals("milk", noteOnB.body)

        // Concurrent title edits 'A' at (1000, "dev-a") and 'B' at (1000, "dev-b")
        devA.clock.set(500L)
        val tieNote = devA.repository.create("Start", "")
        devA.syncEngine.sync()
        devB.syncEngine.sync()

        devA.clock.set(1000L)
        devB.clock.set(1000L)
        devA.repository.edit(tieNote.id, title = "A")
        devB.repository.edit(tieNote.id, title = "B")

        // Sync B first then A so A arrives after B on server and we verify LWW order rather than arrival order
        devB.syncEngine.sync()
        devA.syncEngine.sync()
        devB.syncEngine.sync()
        devA.syncEngine.sync()

        assertEquals("B", devA.repository.get(tieNote.id)!!.title)
        assertEquals("B", devB.repository.get(tieNote.id)!!.title)
    }

    @Test
    fun deleteIsPermanentTombstoneEvenAgainstLaterEdit() {
        val server = FakeServer()
        val devA = DeviceRig("dev-a", server, initialTime = 100L)
        val devB = DeviceRig("dev-b", server, initialTime = 100L)

        val note = devA.repository.create("Temp", "To be deleted")
        devA.syncEngine.sync()
        devB.syncEngine.sync()

        // Device B goes offline and edits the note at a later timestamp (5000)
        devB.clock.set(5000L)
        devB.repository.edit(note.id, title = "Resurrected?", body = "Later edit")

        // Device A deletes the note at timestamp 200 and syncs
        devA.clock.set(200L)
        devA.repository.delete(note.id)
        devA.syncEngine.sync()

        // Now Device B syncs its later edit (timestamp 5000) and pulls A's delete
        devB.syncEngine.sync()
        devA.syncEngine.sync()

        assertTrue(devA.repository.get(note.id)!!.deleted)
        assertTrue(devB.repository.get(note.id)!!.deleted)
        assertEquals(0, devA.repository.list().size)
        assertEquals(0, devB.repository.list().size)
    }

    @Test
    fun enforcesTitleBodyAndOutboxBoundsWithTypedCodes() {
        val server = FakeServer()
        val dev = DeviceRig("dev-bounds", server)

        val longTitle = "a".repeat(201)
        val titleErr = assertThrows<NotesException> { dev.repository.create(longTitle, "ok") }
        assertEquals("too_long", titleErr.code)

        val maxTitle = "a".repeat(200)
        val validNote = dev.repository.create(maxTitle, "b".repeat(100_000))
        assertEquals(200, validNote.title.length)

        val longBody = "b".repeat(100_001)
        val bodyErr = assertThrows<NotesException> { dev.repository.create("ok", longBody) }
        assertEquals("too_long", bodyErr.code)

        val editTitleErr = assertThrows<NotesException> { dev.repository.edit(validNote.id, title = longTitle) }
        assertEquals("too_long", editTitleErr.code)

        val editBodyErr = assertThrows<NotesException> { dev.repository.edit(validNote.id, body = longBody) }
        assertEquals("too_long", editBodyErr.code)

        // We already have 1 operation in outbox from validNote; add 9,999 more to reach 10,000
        repeat(9_999) {
            dev.repository.edit(validNote.id, title = "title-$it")
        }
        assertEquals(10_000, dev.repository.outbox.size)

        // The 10,001st pending operation gives 'outbox_full'
        val outboxErr = assertThrows<NotesException> { dev.repository.edit(validNote.id, title = "overflow") }
        assertEquals("outbox_full", outboxErr.code)
    }

    @Test
    fun edgeCasesOfflineEditBeforePushUnseenNotePullLimitOneAndEmptySync() {
        val server = FakeServer()
        val devA = DeviceRig("dev-a", server, initialTime = 10L, pageSize = 1)
        val devB = DeviceRig("dev-b", server, initialTime = 10L, pageSize = 1)

        // Empty outbox sync
        val emptyRes = devA.syncEngine.sync()
        assertEquals("ok", emptyRes.status)
        assertEquals(0, emptyRes.pushed)
        assertEquals(0, emptyRes.pulled)

        // Editing a note created offline before its first push (including empty body create)
        val n1 = devA.repository.create("Draft", "")
        devA.repository.edit(n1.id, title = "Final Title", body = "Added Body")
        devA.repository.edit(n1.id) // no-op edit
        assertEquals("Final Title", devA.repository.get(n1.id)!!.title)
        assertEquals("Added Body", devA.repository.get(n1.id)!!.body)

        // Push from A and pull on B with pageSize = 1 (note B has never seen n1)
        val syncA = devA.syncEngine.sync()
        assertEquals("ok", syncA.status)
        assertEquals(3, syncA.pushed)

        val syncB = devB.syncEngine.sync()
        assertEquals("ok", syncB.status)
        assertEquals(0, syncB.pushed)
        assertEquals(3, syncB.pulled)

        val pulledNote = devB.repository.get(n1.id)
        assertNotNull(pulledNote)
        assertEquals("Final Title", pulledNote!!.title)
        assertEquals("Added Body", pulledNote.body)

        // Pulling a delete operation for a note a third device has never seen
        val devC = DeviceRig("dev-c", server, initialTime = 50L, pageSize = 1)
        val unseenDeleteOp =
            Operation(
                opId = "op-del-unseen",
                noteId = "unseen-note",
                field = Operation.FIELD_DELETED,
                value = "true",
                timestamp = 99L,
                deviceId = "dev-z",
            )
        devC.repository.applyOperation(unseenDeleteOp)
        assertTrue(devC.repository.get("unseen-note")!!.deleted)
        assertNull(devC.repository.get("never-existed"))
    }

    @Test
    fun errorCasesForNotFoundInvalidPageAndStoreEviction() {
        val server = FakeServer()
        val smallStore = InMemoryLocalStore(maxNotes = 2)
        val repo =
            NotesRepository(
                store = smallStore,
                clock = { 1L },
                ids = {
                    java.util.UUID
                        .randomUUID()
                        .toString()
                },
                deviceId = "dev-err",
            )

        val n1 = repo.create("1", "1")
        val n2 = repo.create("2", "2")
        val n3 = repo.create("3", "3")
        assertNull(smallStore.getNote(n1.id))
        assertNotNull(smallStore.getNote(n2.id))
        assertNotNull(smallStore.getNote(n3.id))
        assertEquals(3, smallStore.outboxOperations.size)

        val notFoundEdit = assertThrows<NotesException> { repo.edit("missing", title = "x") }
        assertEquals("not_found", notFoundEdit.code)

        val notFoundDel = assertThrows<NotesException> { repo.delete("missing") }
        assertEquals("not_found", notFoundDel.code)

        repo.delete(n2.id)
        val deletedEdit = assertThrows<NotesException> { repo.edit(n2.id, title = "x") }
        assertEquals("not_found", deletedEdit.code)

        val badPageEngine = assertThrows<NotesException> { SyncEngine(repo, server, pageSize = 0) }
        assertEquals("invalid_page", badPageEngine.code)

        val hugePageEngine = assertThrows<NotesException> { SyncEngine(repo, server, pageSize = 20_000) }
        assertEquals("invalid_page", hugePageEngine.code)

        val blankDevice =
            assertThrows<NotesException> {
                NotesRepository(smallStore, { 1L }, { "id" }, "   ")
            }
        assertEquals("too_long", blankDevice.code)

        val badFieldOp =
            Operation(
                opId = "op-bad",
                noteId = "n-bad",
                field = "unknown_field",
                value = "v",
                timestamp = 1L,
                deviceId = "dev-err",
            )
        val badFieldErr = assertThrows<NotesException> { repo.applyOperation(badFieldOp) }
        assertEquals("too_long", badFieldErr.code)
    }

    @Test
    fun batchesMoreThanOneHundredOutboxOperationsAndHandlesBadRemotePage() {
        val server = FakeServer()
        val dev = DeviceRig("dev-batch", server)
        val note = dev.repository.create("Batch", "0")
        repeat(150) {
            dev.repository.edit(note.id, body = "body-$it")
        }
        assertEquals(151, dev.repository.outbox.size)
        val res = dev.syncEngine.sync()
        assertEquals("ok", res.status)
        assertEquals(151, res.pushed)
        assertEquals(0, dev.repository.outbox.size)

        val brokenRemote =
            object : RemoteApi {
                override fun push(ops: List<Operation>): PushResult = PushResult(emptySet())

                override fun pull(
                    cursor: String?,
                    limit: Int,
                ): ChangePage =
                    ChangePage(
                        operations =
                            List(limit + 1) {
                                Operation("op-$it", "n", "title", "t", 1L, "d")
                            },
                        nextCursor = null,
                    )
            }
        val brokenEngine = SyncEngine(dev.repository, brokenRemote, pageSize = 2)
        val pageErr = assertThrows<NotesException> { brokenEngine.sync() }
        assertEquals("invalid_page", pageErr.code)
    }
}
