package dev.liquidcalendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CalendarViewDay
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcalendar.data.Stores
import dev.liquidcalendar.ui.glass.ActionSheetHost
import dev.liquidcalendar.ui.glass.GlassNavBar
import dev.liquidcalendar.ui.glass.GlassRoot
import dev.liquidcalendar.ui.glass.GlassTheme
import dev.liquidcalendar.ui.glass.LocalActionSheet
import dev.liquidcalendar.ui.glass.LocalPrompt
import dev.liquidcalendar.ui.glass.LocalReducedMotion
import dev.liquidcalendar.ui.glass.LocalTabActive
import dev.liquidcalendar.ui.glass.NavItem
import dev.liquidcalendar.ui.glass.PromptHost
import dev.liquidcalendar.ui.glass.PromptSpec
import dev.liquidcalendar.ui.glass.SheetSpec
import dev.liquidcalendar.ui.glass.isDarkTheme
import dev.liquidcalendar.ui.screens.AccountScreen
import dev.liquidcalendar.ui.screens.CalendarTab
import dev.liquidcalendar.ui.screens.CalendarsScreen
import dev.liquidcalendar.ui.screens.LocalToast
import dev.liquidcalendar.ui.screens.SettingsScreen
import dev.liquidcalendar.ui.screens.TAB_COUNT
import dev.liquidcalendar.ui.screens.TAB_MONTH
import dev.liquidcalendar.ui.screens.ToastHost
import dev.liquidcalendar.util.preferHighestRefreshRate
import dev.liquidcalendar.util.str

/** Pantallas que se abren encima de las pestañas, apiladas. */
sealed interface Overlay {
    data object Settings : Overlay
    data object Account : Overlay
    data object Calendars : Overlay
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) {
                App(settings.animatedBackground)
            }
        }
    }
}

@Composable
private fun App(animated: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_MONTH) }
    // Posición de la barra mientras se desliza entre pestañas (p. ej. 1,4), o null.
    var tabDrag by remember { mutableStateOf<Float?>(null) }
    val stack = remember { mutableStateListOf<Overlay>() }
    val holder = rememberSaveableStateHolder()
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var prompt by remember { mutableStateOf<PromptSpec?>(null) }
    var toast by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val reduced = LocalReducedMotion.current

    fun push(o: Overlay) {
        stack += o
    }
    fun pop() {
        if (stack.isEmpty()) return
        val key = "${stack.lastIndex}:${stack.last()}"
        stack.removeAt(stack.lastIndex)
        holder.removeState(key)
    }
    BackHandler(enabled = stack.isNotEmpty() && sheet == null && prompt == null) { pop() }
    BackHandler(enabled = stack.isEmpty() && tab != TAB_MONTH) { tab = TAB_MONTH }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    val navHeight = 70.dp + 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabPadding = PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = navHeight + 16.dp,
        start = insets.calculateStartPadding(dir),
        end = insets.calculateEndPadding(dir),
    )
    val overlayPadding = PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = insets.calculateBottomPadding() + 16.dp,
        start = insets.calculateStartPadding(dir),
        end = insets.calculateEndPadding(dir),
    )
    val openSettings = { push(Overlay.Settings) }

    CompositionLocalProvider(
        LocalActionSheet provides { sheet = it },
        LocalPrompt provides { prompt = it },
        LocalToast provides { toast = it to System.nanoTime() },
    ) {
        GlassRoot(
            animated = animated,
            floating = {
                AnimatedVisibility(
                    stack.isEmpty(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + if (reduced) fadeIn() else slideInVertically { it / 2 },
                    exit = fadeOut() + if (reduced) fadeOut() else slideOutVertically { it / 2 },
                ) {
                    GlassNavBar(
                        items = listOf(
                            NavItem(str(R.string.tab_month), Icons.Rounded.CalendarMonth),
                            NavItem(str(R.string.tab_week), Icons.Rounded.CalendarViewWeek),
                            NavItem(str(R.string.tab_day), Icons.Rounded.CalendarViewDay),
                            NavItem(str(R.string.tab_agenda), Icons.Rounded.ViewAgenda),
                        ),
                        selected = tab,
                        dragPosition = { tabDrag },
                        onSelect = { tab = it },
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                ToastHost(toast) { toast = null }
                ActionSheetHost(sheet) { sheet = null }
                PromptHost(prompt) { prompt = null }
            },
        ) {
            val top = stack.lastOrNull()
            // Las pestañas siguen creadas debajo de lo que se abra encima: al volver están igual.
            val tabsAlpha by animateFloatAsState(
                if (top == null) 1f else 0f,
                tween(if (reduced) 0 else if (top == null) 220 else 120),
                label = "tabs",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = tabsAlpha }
                    .drawWithContent { if (tabsAlpha > 0.01f) drawContent() }
                    .then(if (top != null) Modifier.clearAndSetSemantics { }.pointerInput(Unit) { swallowTouches() } else Modifier),
            ) {
                SwipeTabs(tab, onScreen = top == null, onTab = { tab = it }, onDrag = { tabDrag = it }) { t ->
                    CalendarTab(t, tabPadding, openSettings)
                }
            }

            val topKey = if (top == null) "none" else "${stack.lastIndex}:$top"
            AnimatedContent(
                targetState = top to topKey,
                transitionSpec = {
                    if (reduced) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(120))
                    }
                },
                contentKey = { it.second },
                label = "overlay",
            ) { (current, key) ->
                if (current == null) {
                    Box(Modifier.fillMaxSize())
                    return@AnimatedContent
                }
                holder.SaveableStateProvider(key) {
                    when (current) {
                        Overlay.Settings -> SettingsScreen(
                            overlayPadding,
                            ::pop,
                            onAccount = { push(Overlay.Account) },
                            onCalendars = { push(Overlay.Calendars) },
                        )
                        Overlay.Account -> AccountScreen(overlayPadding, ::pop)
                        Overlay.Calendars -> CalendarsScreen(overlayPadding, ::pop, onAccount = { push(Overlay.Account) })
                    }
                }
            }
        }
    }
}

/** Se traga todos los toques (para las pestañas que quedan debajo de otra pantalla). */
private suspend fun PointerInputScope.swallowTouches() {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Pestañas que se cambian deslizando, con el HorizontalPager de Compose. Todas siguen creadas
 * aunque no se vean y la burbuja de la barra sigue al dedo.
 */
@Composable
private fun SwipeTabs(
    tab: Int,
    onScreen: Boolean,
    onTab: (Int) -> Unit,
    onDrag: (Float?) -> Unit,
    content: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = tab) { TAB_COUNT }
    val select by rememberUpdatedState(onTab)
    val drag by rememberUpdatedState(onDrag)
    val reduced = LocalReducedMotion.current
    // Deslizar: cuando la página se asienta pasa a ser la pestaña elegida.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { select(it) }
    }
    // Mientras se mueve, la barra muestra la posición exacta (p. ej. 1,4).
    LaunchedEffect(pager) {
        snapshotFlow { if (pager.isScrollInProgress) pager.currentPage + pager.currentPageOffsetFraction else null }
            .collect { drag(it) }
    }
    // Tocar la barra o volver atrás: se va a esa página (sin animar si se piden menos animaciones).
    LaunchedEffect(tab) {
        if (pager.targetPage != tab) {
            if (reduced) pager.scrollToPage(tab) else pager.animateScrollToPage(tab)
        }
    }
    HorizontalPager(
        state = pager,
        // Basta con arrastrar un 15 % del ancho (o un gesto rápido) para cambiar de pestaña; el
        // valor por defecto pide pasar de la mitad.
        flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = 0.15f),
        beyondViewportPageCount = TAB_COUNT - 1,
        userScrollEnabled = onScreen,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        CompositionLocalProvider(LocalTabActive provides (page == pager.currentPage && onScreen)) { content(page) }
    }
}

/** Iconos de las barras del sistema según el tema (claros sobre fondo oscuro). */
@Composable
fun SystemBarsFor(dark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
