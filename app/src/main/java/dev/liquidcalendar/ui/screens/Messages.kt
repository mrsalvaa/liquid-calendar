package dev.liquidcalendar.ui.screens

import dev.liquidcalendar.R
import dev.liquidcalendar.core.ServerUrl
import dev.liquidcalendar.core.dav.DavException
import dev.liquidcalendar.util.str

/** Mensaje para el usuario según el error (sin URLs ni detalles técnicos). */
fun errorMessage(e: DavException): String = str(
    when (e) {
        is DavException.Unauthorized -> R.string.err_auth
        is DavException.Forbidden -> R.string.err_forbidden
        is DavException.NotFound, is DavException.BadResponse -> R.string.err_not_caldav
        is DavException.RedirectRefused, is DavException.TooManyRedirects -> R.string.err_redirect
        is DavException.Tls -> R.string.err_tls
        is DavException.Network -> if (e.kind == "UnknownHost") R.string.err_host else R.string.err_network
        is DavException.RetryLater -> R.string.err_busy
        is DavException.ServerError -> R.string.err_server
        is DavException.TooLarge -> R.string.err_too_large
        else -> R.string.err_generic
    },
)

/** Mismo mensaje a partir del nombre guardado en la base (Account.lastError). */
fun errorMessage(kind: String?): String = str(
    when (kind) {
        "Unauthorized", "MissingCredentials" -> R.string.err_auth
        "Forbidden" -> R.string.err_forbidden
        "NotFound", "BadResponse" -> R.string.err_not_caldav
        "RedirectRefused", "TooManyRedirects" -> R.string.err_redirect
        "Tls" -> R.string.err_tls
        "Network" -> R.string.err_network
        "RetryLater" -> R.string.err_busy
        "ServerError" -> R.string.err_server
        "TooLarge" -> R.string.err_too_large
        else -> R.string.err_generic
    },
)

fun urlMessage(reason: ServerUrl.Reason): String = str(
    when (reason) {
        ServerUrl.Reason.EMPTY -> R.string.url_empty
        ServerUrl.Reason.HTTP_NOT_ALLOWED, ServerUrl.Reason.UNSUPPORTED_SCHEME -> R.string.url_http
        ServerUrl.Reason.CREDENTIALS_IN_URL -> R.string.url_credentials
        ServerUrl.Reason.QUERY_OR_FRAGMENT -> R.string.url_query
        ServerUrl.Reason.BAD_PORT -> R.string.url_port
        ServerUrl.Reason.MALFORMED, ServerUrl.Reason.MISSING_HOST -> R.string.url_invalid
    },
)
