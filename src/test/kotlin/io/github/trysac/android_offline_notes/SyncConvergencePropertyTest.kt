package io.github.trysac.android_offline_notes

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Property-based convergence tests across 3 concurrent devices with simulated lost responses.
 */
class SyncConvergencePropertyTest {
    data class Step(
        val deviceIndex: Int,
        val action: Int,
        val noteSlot: Int,
        val textSeed: Int,
        val clockAdvance: Int,
        val dropResponse: Boolean,
    )

    @Provide
    fun actionStreams(): Arbitrary<List<Step>> {
        val stepArb =
            Arbitraries.integers().between(0, 2).flatMap { dev ->
                Arbitraries.integers().between(0, 4).flatMap { act ->
                    Arbitraries.integers().between(0, 5).flatMap { slot ->
                        Arbitraries.integers().between(1, 50).flatMap { text ->
                            Arbitraries.integers().between(0, 3).flatMap { tick ->
                                Arbitraries.of(true, false).map { drop ->
                                    Step(dev, act, slot, text, tick, drop)
                                }
                            }
                        }
                    }
                }
            }
        return stepArb.list().ofMinSize(5).ofMaxSize(35)
    }

    @Property(seed = "942", tries = 1000)
    fun threeDevicesConvergeWithRandomInterleavingsAndLostResponses(
        @ForAll("actionStreams") steps: List<Step>,
    ) {
        val server = FakeServer()
        val sharedClock = AtomicLong(1000L)
        val repos =
            listOf("dev-a", "dev-b", "dev-c").map { devId ->
                val seq = AtomicInteger(0)
                NotesRepository(
                    InMemoryLocalStore(),
                    { sharedClock.get() },
                    { "$devId-${seq.incrementAndGet()}" },
                    devId,
                )
            }
        val engines = repos.map { SyncEngine(it, server, pageSize = 7) }
        val knownNoteIds = ArrayList<String>()

        val context = StepContext(sharedClock, repos, engines, server, knownNoteIds)
        executeInterleavedSteps(steps, context)
        assertFinalConvergence(repos, engines, server, knownNoteIds)
    }

    private data class StepContext(
        val clock: AtomicLong,
        val repos: List<NotesRepository>,
        val engines: List<SyncEngine>,
        val server: FakeServer,
        val knownNoteIds: MutableList<String>,
    )

    private fun executeInterleavedSteps(
        steps: List<Step>,
        ctx: StepContext,
    ) {
        for (step in steps) {
            ctx.clock.addAndGet(step.clockAdvance.toLong())
            val repo = ctx.repos[step.deviceIndex]
            val engine = ctx.engines[step.deviceIndex]
            when (step.action) {
                0 -> {
                    ctx.knownNoteIds.add(repo.create("Title-${step.textSeed}", "Body-${step.textSeed}").id)
                }

                1 -> {
                    repo.list().firstOrNull()?.let {
                        val target = repo.list()[step.noteSlot % repo.list().size]
                        repo.edit(target.id, title = "EditedTitle-${step.textSeed}")
                    }
                }

                2 -> {
                    repo.list().firstOrNull()?.let {
                        val target = repo.list()[step.noteSlot % repo.list().size]
                        repo.edit(target.id, body = "EditedBody-${step.textSeed}")
                    }
                }

                3 -> {
                    repo.list().firstOrNull()?.let {
                        val target = repo.list()[step.noteSlot % repo.list().size]
                        repo.delete(target.id)
                    }
                }

                4 -> {
                    if (step.dropResponse) ctx.server.dropNextPushResponse = true
                    engine.sync()
                }
            }
        }
    }

    private fun assertFinalConvergence(
        repos: List<NotesRepository>,
        engines: List<SyncEngine>,
        server: FakeServer,
        knownNoteIds: List<String>,
    ) {
        server.offline = false
        server.dropNextPushResponse = false
        server.dropNextPullResponse = false
        repeat(2) {
            for (engine in engines) assertEquals("ok", engine.sync().status)
        }
        val lists = repos.map { it.list() }
        for (list in lists) assertEquals(list.map { it.id }.distinct().size, list.size)
        assertEquals(lists[0], lists[1])
        assertEquals(lists[1], lists[2])
        assertEquals(server.notes(), lists[0])
        for (noteId in knownNoteIds.distinct()) {
            assertEquals(repos[0].get(noteId), repos[1].get(noteId))
            assertEquals(repos[1].get(noteId), repos[2].get(noteId))
        }
    }
}
