package dev.liquidcalendar.ui.screens

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcalendar.CalendarApp
import dev.liquidcalendar.R
import dev.liquidcalendar.sync.SyncEngine
import dev.liquidcalendar.sync.SyncResult
import dev.liquidcalendar.ui.glass.GlassSurface
import dev.liquidcalendar.ui.glass.LocalReducedMotion
import dev.liquidcalendar.util.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Un aviso abajo de la pantalla. Con [lasts] en false sigue ahí hasta que lo reemplace otro. */
class Notice(val text: String, val lasts: Boolean = true)

/**
 * La sincronización pedida con el botón. Vive fuera de las pantallas: si se sale de la lista de
 * calendarios o se gira el celular, sigue su curso y el aviso del final llega igual.
 */
object ManualSync {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /** Lo que tarda en cambiar el primer aviso, para que alcance a leerse si el servidor responde enseguida. */
    private const val SHOWN_AT_LEAST_MS = 800L

    fun start() {
        if (!_running.compareAndSet(expect = false, update = true)) return
        _notice.value = Notice(str(R.string.syncing), lasts = false)
        CalendarApp.scope.launch {
            val started = System.currentTimeMillis()
            val result = SyncEngine.sync(now = true)
            delay(SHOWN_AT_LEAST_MS - (System.currentTimeMillis() - started))
            _notice.value = when (result) {
                SyncResult.Ok -> str(R.string.sync_done)
                SyncResult.NoAccount -> null
                SyncResult.NeedsPassword -> str(R.string.needs_password_title)
                is SyncResult.Wait -> str(R.string.wait_until, timeOf(result.until))
                is SyncResult.Failed -> errorMessage(result.error)
            }?.let { Notice(it) }
            _running.value = false
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }
}

/**
 * El giro del ícono mientras [running]. Va en el sentido de sus flechas, antihorario, y al terminar
 * completa la vuelta para no frenar de golpe. Con menos animaciones en el sistema se queda quieto.
 */
@Composable
fun rememberSyncTurn(running: Boolean): Animatable<Float, AnimationVector1D> {
    val turn = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(running, reduced) {
        if (running && !reduced) {
            while (true) {
                turn.animateTo(-360f, tween(((360f + turn.value) / 360f * TURN_MS).toInt(), easing = LinearEasing))
                turn.snapTo(0f)
            }
        } else if (turn.value != 0f) {
            turn.animateTo(-360f, tween(((360f + turn.value) / 360f * TURN_MS).toInt(), easing = FastOutSlowInEasing))
            turn.snapTo(0f)
        }
    }
    return turn
}

private const val TURN_MS = 900

/**
 * La misma píldora de cristal que el aviso de Deshacer de Liquid Contacts, sin la acción: de borde a
 * borde, abajo de la pantalla, y se saca deslizándola para cualquier lado.
 */
@Composable
fun BoxScope.NoticeHost(notice: Notice?, onDone: () -> Unit) {
    val context = LocalContext.current
    var last by remember { mutableStateOf(notice) }
    if (notice != null) last = notice
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(notice) {
        if (notice != null) {
            drag.snapTo(Offset.Zero)
            if (notice.lasts) {
                val manager = context.getSystemService(AccessibilityManager::class.java)
                delay(manager.getRecommendedTimeoutMillis(NOTICE_MS, AccessibilityManager.FLAG_CONTENT_TEXT).toLong())
                done()
            }
        }
    }
    AnimatedVisibility(
        notice != null,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val dismissAt = with(LocalDensity.current) { DISMISS_AT.toPx() }
        val dismissLabel = str(R.string.dismiss)
        GlassSurface(
            Modifier
                .fillMaxWidth()
                // El gesto va afuera del corrimiento: si no, la píldora al moverse cambiaría la lectura del dedo.
                .pointerInput(dismissAt) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (drag.value.getDistance() >= dismissAt) {
                                    // Sigue en la misma dirección hasta irse.
                                    drag.animateTo(drag.value * 3f, tween(140))
                                    done()
                                } else {
                                    drag.animateTo(Offset.Zero, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(Offset.Zero) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { drag.snapTo(drag.value + amount) }
                    }
                }
                .offset { IntOffset(drag.value.x.roundToInt(), drag.value.y.roundToInt()) }
                .graphicsLayer { alpha = (1f - drag.value.getDistance() / (dismissAt * 2.5f)).coerceIn(0f, 1f) }
                // Con TalkBack no se puede deslizar.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(dismissLabel) {
                            done()
                            true
                        },
                    )
                },
            shape = CircleShape,
            strong = true,
            frost = 12.dp,
        ) {
            Text(
                last?.text.orEmpty(),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private const val NOTICE_MS = 4_000

/** Cuánto hay que deslizar la píldora, para cualquier lado, para que se vaya. */
private val DISMISS_AT = 64.dp
