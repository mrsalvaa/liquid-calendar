package dev.liquidcalendar.core.sync

import dev.liquidcalendar.core.ServerUrl
import dev.liquidcalendar.core.dav.Credentials
import dev.liquidcalendar.core.dav.DavClient
import dev.liquidcalendar.core.dav.DavException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Cola en memoria, para las pruebas. */
class MemoryStore(changes: List<PendingChange>) : ChangeStore {
    val all = changes.toMutableList()
    val done = mutableListOf<Pair<Long, String?>>()

    override fun queued() = all.filter { it.state == ChangeState.QUEUED }.sortedBy { it.id }

    override fun done(change: PendingChange, etag: String?) {
        all.removeAll { it.id == change.id }
        done += change.id to etag
    }

    override fun conflict(change: PendingChange, serverIcs: String?, serverEtag: String?) {
        replace(change.copy(state = ChangeState.CONFLICT, serverIcs = serverIcs, serverEtag = serverEtag))
    }

    override fun failed(change: PendingChange, error: DavException) {
        replace(change.copy(state = ChangeState.FAILED, error = error.code))
    }

    private fun replace(c: PendingChange) {
        all[all.indexOfFirst { it.id == c.id }] = c
    }
}

class PusherTest {
    private val server = MockWebServer()

    @BeforeTest fun start() = server.start()

    @AfterTest fun stop() = server.close()

    private fun pusher() = Pusher(DavClient((ServerUrl.parse(server.url("/").toString(), allowHttp = true) as ServerUrl.Result.Ok).url, Credentials("u", "p")))

    private fun change(id: Long, kind: ChangeKind, etag: String? = "\"e$id\"") = PendingChange(
        id, server.url("/cal/").toString(), "uid$id", kind, server.url("/cal/uid$id.ics").toString(),
        if (kind == ChangeKind.DELETE) null else "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", if (kind == ChangeKind.CREATE) null else etag,
    )

    private fun ok(etag: String? = null) = MockResponse.Builder().code(201).apply { if (etag != null) addHeader("ETag", etag) }.build()

    @Test
    fun uploadsInOrderWithTheRightPreconditions() {
        server.enqueue(ok("\"n1\""))
        server.enqueue(ok("\"n2\""))
        server.enqueue(MockResponse.Builder().code(204).build())
        val store = MemoryStore(listOf(change(3, ChangeKind.DELETE), change(1, ChangeKind.CREATE), change(2, ChangeKind.UPDATE)))
        assertEquals(3, pusher().push(store))
        val r1 = server.takeRequest()
        assertEquals("PUT", r1.method)
        assertEquals("*", r1.headers["If-None-Match"])
        val r2 = server.takeRequest()
        assertEquals("\"e2\"", r2.headers["If-Match"])
        val r3 = server.takeRequest()
        assertEquals("DELETE", r3.method)
        assertEquals("\"e3\"", r3.headers["If-Match"])
        assertEquals(listOf(1L to "\"n1\"", 2L to "\"n2\"", 3L to null), store.done)
    }

    @Test
    fun unauthorizedStopsEverythingAfterOneRequest() {
        server.enqueue(MockResponse.Builder().code(401).build())
        val store = MemoryStore(listOf(change(1, ChangeKind.CREATE), change(2, ChangeKind.UPDATE)))
        assertFailsWith<DavException.Unauthorized> { pusher().push(store) }
        assertEquals(1, server.requestCount)
        assertEquals(2, store.queued().size) // nada se perdió ni se marcó
    }

    @Test
    fun busyServerStopsAndKeepsTheQueue() {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "60").build())
        val store = MemoryStore(listOf(change(1, ChangeKind.CREATE), change(2, ChangeKind.CREATE)))
        val e = assertFailsWith<DavException.RetryLater> { pusher().push(store) }
        assertEquals(60, e.retryAfter!!.seconds)
        assertEquals(2, store.queued().size)
    }

    @Test
    fun conflictKeepsBothVersionsAndContinues() {
        server.enqueue(MockResponse.Builder().code(412).build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"srv\"").body("SERVIDOR").build())
        server.enqueue(ok("\"n2\""))
        val store = MemoryStore(listOf(change(1, ChangeKind.UPDATE), change(2, ChangeKind.CREATE)))
        assertEquals(1, pusher().push(store))
        val c = store.all.single { it.id == 1L }
        assertEquals(ChangeState.CONFLICT, c.state)
        assertEquals("SERVIDOR", c.serverIcs)
        assertEquals("\"srv\"", c.serverEtag)
        assertEquals("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", c.ics) // la mía, intacta
    }

    @Test
    fun conflictWhenTheServerAlreadyDeletedIt() {
        server.enqueue(MockResponse.Builder().code(412).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        val store = MemoryStore(listOf(change(1, ChangeKind.UPDATE)))
        pusher().push(store)
        val c = store.all.single()
        assertEquals(ChangeState.CONFLICT, c.state)
        assertNull(c.serverIcs)
    }

    @Test
    fun permanentErrorsAreMarkedOnceAndNotRetried() {
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(ok())
        val store = MemoryStore(listOf(change(1, ChangeKind.CREATE), change(2, ChangeKind.CREATE)))
        assertEquals(1, pusher().push(store))
        assertEquals(ChangeState.FAILED, store.all.single().state)
        assertEquals("Forbidden", store.all.single().error)
        // Una segunda pasada no lo vuelve a intentar.
        assertEquals(0, pusher().push(store))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun deletingSomethingAlreadyGoneIsDone() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val store = MemoryStore(listOf(change(1, ChangeKind.DELETE)))
        assertEquals(1, pusher().push(store))
    }

    @Test
    fun mergingQueuedChanges() {
        val create = change(1, ChangeKind.CREATE).copy(uid = "x")
        val update = change(2, ChangeKind.UPDATE).copy(uid = "x", ics = "NUEVO")
        val delete = change(3, ChangeKind.DELETE).copy(uid = "x")
        assertEquals(create.copy(ics = "NUEVO"), ChangeQueue.merge(create, update).result)
        assertNull(ChangeQueue.merge(create, delete).result)
        val firstUpdate = change(4, ChangeKind.UPDATE, etag = "\"base\"").copy(uid = "x")
        assertEquals("\"base\"", ChangeQueue.merge(firstUpdate, update).result!!.baseEtag)
        val del = ChangeQueue.merge(firstUpdate, delete).result!!
        assertEquals(ChangeKind.DELETE to "\"base\"", del.kind to del.baseEtag)
    }
}
