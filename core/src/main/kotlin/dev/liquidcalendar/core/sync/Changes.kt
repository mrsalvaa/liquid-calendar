package dev.liquidcalendar.core.sync

import dev.liquidcalendar.core.dav.DavClient
import dev.liquidcalendar.core.dav.DavException

enum class ChangeKind { CREATE, UPDATE, DELETE }

enum class ChangeState {
    /** Esperando para subirse. */
    QUEUED,

    /** El servidor tiene otra versión (412): el usuario elige cuál queda. Nunca se sobrescribe sola. */
    CONFLICT,

    /** Error permanente (4xx que no es 401, 412 ni 429): no se reintenta hasta que el usuario decida. */
    FAILED,
}

/** Un cambio local pendiente de subir al servidor. [id] da el orden. */
data class PendingChange(
    val id: Long,
    val calendarUrl: String,
    val uid: String,
    val kind: ChangeKind,
    /** URL del recurso en el servidor. */
    val url: String,
    /** Contenido a subir (null en un borrado). */
    val ics: String?,
    /** ETag que se tenía al hacer el cambio: va en If-Match (null al crear). */
    val baseEtag: String?,
    val state: ChangeState = ChangeState.QUEUED,
    /** DavException.code del último fallo. */
    val error: String? = null,
    /** Versión del servidor en un conflicto (null = el servidor ya no lo tiene). */
    val serverIcs: String? = null,
    val serverEtag: String? = null,
)

/**
 * Cómo combinar un cambio nuevo con el que ya estaba en cola para el mismo evento (y todavía no
 * salió). null en [Merge.result] = no queda nada por subir (se creó y se borró sin llegar al servidor).
 */
data class Merge(val result: PendingChange?)

object ChangeQueue {
    fun merge(queued: PendingChange, next: PendingChange): Merge {
        require(queued.uid == next.uid && queued.state == ChangeState.QUEUED)
        return Merge(
            when (queued.kind to next.kind) {
                ChangeKind.CREATE to ChangeKind.UPDATE -> queued.copy(ics = next.ics)
                ChangeKind.CREATE to ChangeKind.DELETE -> null
                ChangeKind.UPDATE to ChangeKind.UPDATE -> queued.copy(ics = next.ics)
                ChangeKind.UPDATE to ChangeKind.DELETE -> queued.copy(kind = ChangeKind.DELETE, ics = null)
                else -> next.copy(id = queued.id, baseEtag = queued.baseEtag ?: next.baseEtag)
            },
        )
    }
}

/** Donde vive la cola (SQLite en la app, memoria en las pruebas). */
interface ChangeStore {
    /** Cambios en cola de esta cuenta, en orden. */
    fun queued(): List<PendingChange>

    /** Subido: [etag] es el nuevo (null si el servidor no lo informó, o si fue un borrado). */
    fun done(change: PendingChange, etag: String?)

    fun conflict(change: PendingChange, serverIcs: String?, serverEtag: String?)

    fun failed(change: PendingChange, error: DavException)
}

/**
 * Sube la cola en orden. Lanza (y deja todo lo pendiente como estaba) ante los errores que deben
 * cortar la sincronización entera: 401, 429/503, 5xx y errores de red. Un 412 deja el cambio en
 * conflicto con la versión del servidor; otro 4xx lo marca como fallido. En ambos casos sigue
 * con los demás cambios.
 */
class Pusher(private val dav: DavClient) {
    fun push(store: ChangeStore): Int {
        var sent = 0
        for (c in store.queued()) {
            val url = dav.url(c.url)
            try {
                val etag = when (c.kind) {
                    ChangeKind.CREATE -> dav.put(url, c.ics!!, ifNoneMatch = true)
                    ChangeKind.UPDATE -> dav.put(url, c.ics!!, ifMatch = c.baseEtag ?: throw DavException.PreconditionFailed())
                    ChangeKind.DELETE -> {
                        try {
                            dav.delete(url, ifMatch = c.baseEtag ?: throw DavException.PreconditionFailed())
                        } catch (e: DavException.NotFound) {
                            // Ya no estaba: lo que se quería, se cumplió.
                        }
                        null
                    }
                }
                store.done(c, etag)
                sent++
            } catch (e: DavException) {
                when (e) {
                    is DavException.Unauthorized, is DavException.RetryLater, is DavException.ServerError,
                    is DavException.Network, is DavException.Tls,
                    -> throw e
                    is DavException.PreconditionFailed -> {
                        val server = try {
                            dav.get(url)
                        } catch (notFound: DavException.NotFound) {
                            null
                        }
                        store.conflict(c, server?.body?.toString(Charsets.UTF_8), server?.etag)
                    }
                    else -> store.failed(c, e)
                }
            }
        }
        return sent
    }
}
