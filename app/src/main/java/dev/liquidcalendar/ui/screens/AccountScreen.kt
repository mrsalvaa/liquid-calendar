package dev.liquidcalendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.R
import dev.liquidcalendar.core.dav.Discovery
import dev.liquidcalendar.data.AccountState
import dev.liquidcalendar.data.Repo
import dev.liquidcalendar.sync.SyncEngine
import dev.liquidcalendar.sync.TestResult
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.GlassCard
import dev.liquidcalendar.ui.glass.GlassTextField
import dev.liquidcalendar.ui.glass.LocalActionSheet
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SheetAction
import dev.liquidcalendar.ui.glass.SheetSpec
import dev.liquidcalendar.util.plural
import dev.liquidcalendar.util.str
import kotlinx.coroutines.launch

/** Estado del último "Probar conexión" (o "Guardar"). */
private sealed interface Status {
    data object Idle : Status
    data object Testing : Status
    data class Ok(val calendars: Int) : Status
    data class Error(val message: String) : Status
}

/**
 * Alta o edición de la cuenta CalDAV. Para no gastar intentos contra el servidor: tras un 401,
 * "Probar" y "Guardar" quedan desactivados hasta que cambie el usuario o la contraseña, y después
 * de 3 rechazos en 10 minutos se bloquea localmente (ver LoginGuard).
 */
@Composable
fun AccountScreen(padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()
    val sheet = LocalActionSheet.current
    val existing = remember { Repo.caldavAccount() }
    val hasPassword = remember { existing?.let { SyncEngine.credentialStore.has(it.id) } ?: false }

    var url by rememberSaveable { mutableStateOf(existing?.serverUrl.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    // La contraseña nunca se carga en pantalla: vacía = conservar la guardada.
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Status>(Status.Idle) }
    // Usuario y contraseña que el servidor rechazó: no se vuelven a probar iguales.
    var rejected by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Resultado de la última prueba correcta, para guardar sin volver a consultar al servidor.
    var tested by remember { mutableStateOf<Pair<Triple<String, String, String>, Discovery>?>(null) }

    val sameAsRejected = rejected == (user to password)
    val busy = status == Status.Testing
    val canTry = !busy && !sameAsRejected

    fun passwordForTest(): String? = when {
        password.isNotEmpty() -> password
        existing != null && hasPassword -> SyncEngine.credentialStore.load(existing.id)
        else -> null
    }

    suspend fun runTest(): Discovery? {
        if (user.isBlank()) {
            status = Status.Error(str(R.string.fields_required))
            return null
        }
        val pass = passwordForTest()
        if (pass.isNullOrEmpty()) {
            status = Status.Error(str(R.string.fields_required))
            return null
        }
        status = Status.Testing
        return when (val r = SyncEngine.test(url, user.trim(), pass)) {
            is TestResult.Ok -> {
                status = Status.Ok(r.discovery.calendars.size)
                tested = Triple(url, user, password) to r.discovery
                r.discovery
            }
            is TestResult.Invalid -> {
                status = Status.Error(urlMessage(r.reason))
                null
            }
            is TestResult.Blocked -> {
                val minutes = ((r.wait.seconds + 59) / 60).toInt()
                status = Status.Error(plural(R.plurals.blocked, minutes))
                null
            }
            is TestResult.Failed -> {
                if (r.error is dev.liquidcalendar.core.dav.DavException.Unauthorized) rejected = user to password
                status = Status.Error(errorMessage(r.error))
                null
            }
        }
    }

    // Cambiar cualquier campo invalida la prueba anterior.
    LaunchedEffect(url, user, password) {
        if (tested?.first != Triple(url, user, password)) tested = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        BackTitle(str(R.string.caldav_server), onBack)

        if (existing?.state == AccountState.NEEDS_PASSWORD) {
            GlassCard(Modifier.padding(bottom = 12.dp)) {
                Text(str(R.string.needs_password_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.danger)
                Text(str(R.string.needs_password_text), fontSize = 13.sp, color = p.contentSecondary)
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    url, { url = it }, str(R.string.server_address),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    enabled = !busy,
                )
                GlassTextField(
                    user, { user = it }, str(R.string.username),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    enabled = !busy,
                )
                GlassTextField(
                    password, { password = it },
                    str(if (hasPassword) R.string.password_unchanged else R.string.password),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy,
                )
                Text(str(R.string.account_hint), fontSize = 12.sp, color = p.contentSecondary)
            }
        }

        val message = when (val s = status) {
            Status.Idle -> null
            Status.Testing -> str(R.string.testing)
            is Status.Ok -> plural(R.plurals.test_ok, s.calendars)
            is Status.Error -> s.message
        }
        if (message != null) {
            Text(
                message,
                Modifier.padding(horizontal = 8.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 14.sp,
                color = when (status) {
                    is Status.Error -> p.danger
                    is Status.Ok -> p.success
                    else -> p.contentSecondary
                },
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                str(R.string.test_connection),
                { scope.launch { runTest() } },
                Modifier.weight(1f),
                enabled = canTry,
            )
            GlassButton(
                str(R.string.save),
                {
                    scope.launch {
                        val discovery = tested?.takeIf { it.first == Triple(url, user, password) }?.second ?: runTest() ?: return@launch
                        // Guardar y sincronizar sigue aunque se cierre esta pantalla.
                        CalendarApp.scope.launch {
                            // Contraseña vacía = conservar la guardada.
                            SyncEngine.saveAccount(existing?.id, url, user.trim(), password.ifEmpty { null }, discovery)
                            SyncEngine.sync()
                        }
                        onBack()
                    }
                },
                Modifier.weight(1f),
                prominent = true,
                enabled = canTry,
            )
        }

        if (existing != null) {
            GlassButton(
                str(R.string.remove_account),
                {
                    sheet(
                        SheetSpec(
                            title = str(R.string.remove_account_title),
                            subtitle = str(R.string.remove_account_text),
                            actions = listOf(
                                SheetAction(str(R.string.remove_account_confirm), Icons.Rounded.DeleteOutline, danger = true) {
                                    CalendarApp.scope.launch { SyncEngine.deleteAccount(existing.id) }
                                    onBack()
                                },
                            ),
                        ),
                    )
                },
                Modifier.fillMaxWidth().padding(top = 24.dp),
                danger = true,
                enabled = !busy,
            )
        }
    }
}
