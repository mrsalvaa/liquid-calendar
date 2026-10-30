package dev.liquidcalendar

import android.content.Intent
import android.os.Bundle
import dev.liquidcalendar.data.Agenda
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
import dev.liquidcalendar.ui.glass.FlatNavBar
import dev.liquidcalendar.ui.glass.FlatNavBarHeight
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
import dev.liquidcalendar.data.AgendaItem
import dev.liquidcalendar.ui.screens.CalendarTab
import dev.liquidcalendar.ui.screens.ChangesScreen
import dev.liquidcalendar.ui.screens.EditTarget
import dev.liquidcalendar.ui.screens.EventEditScreen
import dev.liquidcalendar.ui.glass.DialogHost
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.GlassIconButton
import dev.liquidcalendar.ui.glass.LocalDialog
import androidx.compose.material.icons.rounded.Add
import dev.liquidcalendar.ui.screens.EventDetailScreen
import dev.liquidcalendar.ui.screens.TAB_DAY
import dev.liquidcalendar.ui.screens.TabContext
import dev.liquidcalendar.ui.screens.views.rememberDeviceClock
import androidx.compose.runtime.mutableLongStateOf
import java.time.DayOfWeek
import java.time.LocalDate
import dev.liquidcalendar.core.ics.ImportPreview
import dev.liquidcalendar.ui.screens.CalendarEditScreen
import dev.liquidcalendar.ui.screens.CalendarTarget
import dev.liquidcalendar.ui.screens.CalendarsAction
import dev.liquidcalendar.ui.screens.CalendarsScreen
import dev.liquidcalendar.ui.screens.ImportScreen
import dev.liquidcalendar.ui.screens.SubscriptionScreen
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
    data class Event(val item: AgendaItem) : Overlay
    data class Edit(val target: EditTarget) : Overlay
    data object Changes : Overlay
    data class CalendarEdit(val target: CalendarTarget) : Overlay
    data class Subscription(val calendarId: Long?) : Overlay
    data class Import(val preview: ImportPreview) : Overlay
}

/** Lo que pidió el widget al abrir la app. */
sealed interface OpenRequest {
    data object NewEvent : OpenRequest
    data class Event(val calendarId: Long, val uid: String, val key: String, val day: LocalDate) : OpenRequest
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Solo la primera vez: al recrear la actividad (p. ej. al girar) no se repite.
        if (savedInstanceState == null) handle(intent)
        preferHighestRefreshRate()
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) {
                App(settings.animatedBackground, settings.weekStart(), flatNav = settings.uiStyle == 1)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        openRequests.value = when (intent?.action) {
            ACTION_NEW_EVENT -> OpenRequest.NewEvent
            ACTION_OPEN_EVENT -> {
                val uid = intent.getStringExtra(EXTRA_UID)
                val key = intent.getStringExtra(EXTRA_KEY)
                val calendar = intent.getLongExtra(EXTRA_CALENDAR, -1)
                val day = intent.getLongExtra(EXTRA_DAY, Long.MIN_VALUE)
                if (uid == null || key == null || calendar < 0 || day == Long.MIN_VALUE) null
                else OpenRequest.Event(calendar, uid, key, LocalDate.ofEpochDay(day))
            }
            else -> null
        }
    }

    companion object {
        const val ACTION_OPEN = "dev.liquidcalendar.OPEN"
        const val ACTION_NEW_EVENT = "dev.liquidcalendar.NEW_EVENT"
        const val ACTION_OPEN_EVENT = "dev.liquidcalendar.OPEN_EVENT"
        const val EXTRA_CALENDAR = "calendar"
        const val EXTRA_UID = "uid"
        const val EXTRA_KEY = "key"
        const val EXTRA_DAY = "day"

        val openRequests = MutableStateFlow<OpenRequest?>(null)
    }
}

@Composable
private fun App(animated: Boolean, weekStart: DayOfWeek, flatNav: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_MONTH) }
    val clock = rememberDeviceClock()
    // Fecha elegida, compartida por todas las pestañas (se guarda como día desde 1970).
    var selectedDay by rememberSaveable { mutableLongStateOf(clock.today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedDay)
    // Posición de la barra mientras se desliza entre pestañas (p. ej. 1,4), o null.
    var tabDrag by remember { mutableStateOf<Float?>(null) }
    val stack = remember { mutableStateListOf<Overlay>() }
    val holder = rememberSaveableStateHolder()
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var prompt by remember { mutableStateOf<PromptSpec?>(null) }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
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
    BackHandler(enabled = stack.isNotEmpty() && sheet == null && prompt == null && dialog == null) { pop() }

    // Pedidos del widget: un evento (se busca entre las ocurrencias de ese día) o uno nuevo hoy.
    LaunchedEffect(Unit) {
        MainActivity.openRequests.filterNotNull().collect { request ->
            MainActivity.openRequests.value = null
            val target = when (request) {
                OpenRequest.NewEvent -> Overlay.Edit(EditTarget.New(clock.today))
                is OpenRequest.Event -> {
                    val zone = clock.zone
                    Agenda.load(request.day.atStartOfDay(zone).toInstant(), request.day.plusDays(1).atStartOfDay(zone).toInstant(), zone)
                        .firstOrNull { it.calendar.id == request.calendarId && it.occurrence.uid == request.uid && it.occurrence.key == request.key }
                        ?.let {
                            selectedDay = request.day.toEpochDay()
                            Overlay.Event(it)
                        }
                }
            }
            if (target != null) {
                while (stack.isNotEmpty()) pop()
                push(target)
            }
        }
    }
    BackHandler(enabled = stack.isEmpty() && tab != TAB_MONTH) { tab = TAB_MONTH }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    // Alto de la barra inferior (flotante: 70 + 12 de margen; plana: pegada abajo).
    val navBar = if (flatNav) FlatNavBarHeight else 70.dp + 12.dp
    val navHeight = navBar + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
    val tabContext = TabContext(
        selected = selected,
        onSelect = { selectedDay = it.toEpochDay() },
        clock = clock,
        weekStart = weekStart,
        padding = tabPadding,
        onEvent = { push(Overlay.Event(it)) },
        onOpenDay = {
            selectedDay = it.toEpochDay()
            tab = TAB_DAY
        },
        onSettings = openSettings,
        onCalendars = { push(Overlay.Calendars) },
    )

    CompositionLocalProvider(
        LocalActionSheet provides { sheet = it },
        LocalPrompt provides { prompt = it },
        LocalDialog provides { dialog = it },
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
                    val navItems = listOf(
                        NavItem(str(R.string.tab_month), Icons.Rounded.CalendarMonth),
                        NavItem(str(R.string.tab_week), Icons.Rounded.CalendarViewWeek),
                        NavItem(str(R.string.tab_day), Icons.Rounded.CalendarViewDay),
                        NavItem(str(R.string.tab_agenda), Icons.Rounded.ViewAgenda),
                    )
                    if (flatNav) {
                        FlatNavBar(navItems, selected = tab, onSelect = { tab = it })
                    } else {
                        GlassNavBar(
                            items = navItems,
                            selected = tab,
                            dragPosition = { tabDrag },
                            onSelect = { tab = it },
                            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                // Nuevo evento, en el día elegido.
                AnimatedVisibility(
                    stack.isEmpty(),
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = navBar + 18.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    GlassIconButton(Icons.Rounded.Add, str(R.string.new_event), { push(Overlay.Edit(EditTarget.New(selected))) }, size = 58.dp, prominent = true)
                }
                ToastHost(toast) { toast = null }
                ActionSheetHost(sheet) { sheet = null }
                PromptHost(prompt) { prompt = null }
                DialogHost(dialog) { dialog = null }
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
                    CalendarTab(t, tabContext)
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
                        Overlay.Calendars -> CalendarsScreen(overlayPadding, ::pop, onAccount = { push(Overlay.Account) }, onChanges = { push(Overlay.Changes) }) { a ->
                            push(
                                when (a) {
                                    is CalendarsAction.Edit -> Overlay.CalendarEdit(a.target)
                                    is CalendarsAction.Subscription -> Overlay.Subscription(a.calendarId)
                                    is CalendarsAction.Import -> Overlay.Import(a.preview)
                                },
                            )
                        }
                        is Overlay.CalendarEdit -> CalendarEditScreen(current.target, overlayPadding, ::pop)
                        is Overlay.Subscription -> SubscriptionScreen(current.calendarId, overlayPadding, ::pop)
                        is Overlay.Import -> ImportScreen(current.preview, overlayPadding, ::pop)
                        is Overlay.Event -> EventDetailScreen(current.item, clock, overlayPadding, ::pop, onEdit = { push(Overlay.Edit(it)) })
                        is Overlay.Edit -> EventEditScreen(current.target, clock, weekStart, overlayPadding, ::pop, onSaved = {
                            // Al guardar se vuelve a la vista (el detalle abierto debajo ya no está al día).
                            pop()
                            if (stack.lastOrNull() is Overlay.Event) pop()
                        })
                        Overlay.Changes -> ChangesScreen(clock, overlayPadding, ::pop)
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
