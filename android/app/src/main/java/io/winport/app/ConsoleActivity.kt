package io.winport.app

import android.os.Bundle
import android.view.KeyEvent as AndroidKey
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        immersive()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, onPrimary = Night, background = Night, surface = Glass, onSurface = Color.White, secondary = Ice), typography = Typography()) {
                Surface(Modifier.fillMaxSize(), color = Night, contentColor = Color.White) { ConsoleShell(exit = { finishAndRemoveTask() }) }
            }
        }
    }
    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun dispatchKeyEvent(event: AndroidKey): Boolean {
        if (event.keyCode == AndroidKey.KEYCODE_BUTTON_A) return super.dispatchKeyEvent(AndroidKey(event.downTime, event.eventTime, event.action, AndroidKey.KEYCODE_DPAD_CENTER, event.repeatCount))
        if (event.keyCode == AndroidKey.KEYCODE_BUTTON_B || event.keyCode == AndroidKey.KEYCODE_ESCAPE) return super.dispatchKeyEvent(AndroidKey(event.downTime, event.eventTime, event.action, AndroidKey.KEYCODE_BACK, event.repeatCount))
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun ConsoleShell(exit: () -> Unit, vm: CoreViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val memory = remember { ConsoleMemory(context) }
    var route by remember { mutableStateOf("home") }
    var detail by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<Game?>(null) }
    var options by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf(false) }
    var controlPage by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<Game?>(null) }
    var search by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf(false) }
    val game = ui.data.games.find { it.id == selected }
    val back = {
        when {
            ui.error != null || ui.message != null -> vm.dismiss()
            confirmRemove != null -> confirmRemove = null
            editor != null -> editor = null
            options -> options = false
            controlPage != null -> controlPage = null
            control -> control = false
            search != null -> search = null
            detail != null -> detail = null
            route != "home" -> route = "home"
            media -> media = false
            else -> control = true
        }
    }
    BackHandler { back() }
    Box(Modifier.fillMaxSize().background(Night).onPreviewKeyEvent { event ->
        when (event.nativeKeyEvent.keyCode) {
            AndroidKey.KEYCODE_BUTTON_START, AndroidKey.KEYCODE_MENU -> { if (event.nativeKeyEvent.action == AndroidKey.ACTION_DOWN) { control = !control; controlPage = null }; true }
            else -> false
        }
    }) {
        AmbientBackground()
        AnimatedContent(targetState = if (route == "home" && !media) game else null, transitionSpec = { fadeIn(tween(650)) togetherWith fadeOut(tween(650)) }, label = "hero") { hero ->
            if (hero != null) GameArt(hero, Modifier.fillMaxSize(), memory.art(hero), hero = true)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Night.copy(alpha = .95f)))))
        when (route) {
            "home" -> HomeScreen(ui, game, memory, media,
                onTab = { media = it }, onSelect = { selected = it }, onLibrary = { route = "library" },
                onAdd = { editor = Game() }, onEdit = { editor = it }, onOptions = { options = true },
                onSettings = { route = "settings" }, onSearch = { route = "library"; search = "" },
                onControl = { control = true }, onPlay = { if (it.id in ui.running) vm.stop(it) else { memory.markPlayed(it); vm.launch(it) } }, vm = vm)
            "library" -> LibraryPage(ui, memory, search, { search = it }, back = { route = "home"; search = null }, add = { editor = Game() }, open = { selected = it.id; route = "home"; media = false })
            "settings" -> if (detail == null) SettingsPage(back = { route = "home" }, open = { detail = it }) else SettingsDetail(detail!!, ui, vm, back = { detail = null })
        }
        if (ui.busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp), color = Ice)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(12.dp).pointerInput(Unit) { detectVerticalDragGestures { _, amount -> if (amount < -3) control = true } })
        if (options && game != null) SideMenu(game, close = { options = false }, edit = { options = false; editor = game }, remove = { options = false; confirmRemove = game })
        AnimatedVisibility(control, enter = fadeIn(tween(180)) + slideInVertically(spring(.85f, 420f)) { it }, exit = fadeOut(tween(180)) + slideOutVertically { it }) {
            ControlCenter(controlPage, ui, close = { control = false; controlPage = null }, home = { route = "home"; detail = null; control = false; media = false }, page = { controlPage = it }, exit = exit)
        }
        if (editor != null) GameForm(editor!!, ui, vm, memory, close = { editor = null })
        confirmRemove?.let { removing -> ConsolePrompt("Remove ${removing.name}?", "Only the library entry is removed. Your game files stay in the container.", "Remove", close = { confirmRemove = null }) { vm.remove("Game", removing.id); confirmRemove = null; selected = null } }
        (ui.error ?: ui.message)?.let { message -> ConsolePrompt(if (ui.error != null) "Unable to continue" else "Winport", message, "Close", close = vm::dismiss, confirm = vm::dismiss) }
    }
}

@Composable
fun HomeScreen(ui: UiState, game: Game?, memory: ConsoleMemory, media: Boolean, onTab: (Boolean) -> Unit, onSelect: (String?) -> Unit, onLibrary: () -> Unit, onAdd: () -> Unit, onEdit: (Game) -> Unit, onOptions: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit, onControl: () -> Unit, onPlay: (Game) -> Unit, vm: CoreViewModel) {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(1000) } }
    val initial = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var enterHub by remember { mutableIntStateOf(0) }
    LaunchedEffect(media) { if (!media) initial.requestFocus() }
    LaunchedEffect(enterHub) { if (enterHub > 0) playFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 38.dp, top = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp).focusGroup(), verticalAlignment = Alignment.CenterVertically) {
            TabWord("Games", !media) { onTab(false) }
            Spacer(Modifier.width(22.dp))
            TabWord("Media", media) { onTab(true) }
            Spacer(Modifier.weight(1f))
            ConsoleIcon(Mark.Search, "Search games", onSearch)
            Spacer(Modifier.width(15.dp))
            ConsoleIcon(Mark.Settings, "Settings", onSettings)
            Spacer(Modifier.width(15.dp))
            Box(Modifier.clip(CircleShape).background(Color(0xFF526B87))) { ConsoleIcon(Mark.User, "User and control center", onControl) }
            Text(time, fontSize = 18.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 27.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (media) {
            Spacer(Modifier.weight(1f))
            Text("Your media. Your space.", fontSize = 38.sp, fontWeight = FontWeight.Light)
            Text("No media apps have been added.", color = Soft, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.weight(1f))
        } else {
            LazyRow(Modifier.fillMaxWidth().height(100.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(5.dp)) {
                item("library") { HomeTile("Game Library", game == null, Mark.Library, if (game == null) Modifier.focusRequester(initial) else Modifier, { onSelect(null) }, onLibrary) }
                items(ui.data.games, key = { it.id }) { tile ->
                    HomeTile(tile.name, game?.id == tile.id, null, if (game?.id == tile.id) Modifier.focusRequester(initial) else Modifier, onFocus = { onSelect(tile.id) }, onClick = { onSelect(tile.id); enterHub++ }, art = { GameArt(tile, Modifier.fillMaxSize(), memory.art(tile)) })
                }
                item("add") { HomeTile("Add game", false, Mark.Add, onFocus = {}, onClick = onAdd) }
            }
            Row(Modifier.fillMaxWidth().padding(start = if (game == null) 130.dp else 200.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (game == null) "Game Library" else "WINDOWS  /  ${game.name}", fontSize = 12.sp, color = Color.White.copy(alpha = .8f), maxLines = 1)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
                AnimatedContent(targetState = game, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "hub") { focused ->
                    if (focused == null) {
                        Column {
                            Text(if (ui.data.games.isEmpty()) "A world of play awaits." else "All your games. One place.", fontSize = 29.sp, fontWeight = FontWeight.Light)
                            Text(if (ui.data.games.isEmpty()) "Your collection starts with you. Add a Windows game to begin." else "Explore your collection or bring something new.", color = Soft, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ConsoleButton(if (ui.data.games.isEmpty()) "Add game" else "View collection", if (ui.data.games.isEmpty()) onAdd else onLibrary, primary = true)
                                ConsoleButton("Runtime settings", onSettings)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                                ActivityCard("YOUR COLLECTION", "${ui.data.games.size} installed", "Only games you add appear here.", Mark.Library, onLibrary)
                                ActivityCard("MAKE IT YOURS", "Ready when you are", "Set up a container and choose a profile.", Mark.Tune, onSettings)
                                ActivityCard("WINPORT", "Built for possibility", "Box64 · Wine · DXVK", Mark.Chip, onSettings)
                            }
                        }
                    } else {
                        Column {
                            Text(focused.name, fontSize = 32.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("WINDOWS PC  •  ${ui.data.containers.find { it.id == focused.container_id }?.name ?: "Container unavailable"}", color = Soft, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 3.dp, bottom = 9.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ConsoleButton(if (focused.id in ui.running) "Stop" else "Play", { onPlay(focused) }, Modifier.width(170.dp).focusRequester(playFocus), primary = true)
                                ConsoleButton("", onOptions, Modifier.size(43.dp), mark = Mark.More)
                            }
                            GameActivities(focused, ui, memory, vm, edit = { onEdit(focused) })
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("WINPORT  /  PERSONAL GAMING SYSTEM", color = Soft.copy(alpha = .55f), fontSize = 8.sp, letterSpacing = 1.8.sp)
            Text("Control Center  ↑", color = Soft, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onControl).padding(8.dp))
        }
    }
}

@Composable
fun TabWord(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    androidx.compose.foundation.text.BasicText(label, Modifier.onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(4.dp)).background(if (focused) Color.White.copy(alpha=.15f) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 7.dp), style = androidx.compose.ui.text.TextStyle(color = if (active || focused) Color.White else Soft.copy(alpha=.6f), fontSize = 22.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Light))
}

@Composable
fun HomeTile(title: String, selected: Boolean, icon: Mark?, modifier: Modifier = Modifier, onFocus: () -> Unit, onClick: () -> Unit, art: @Composable (() -> Unit)? = null) {
    val width by animateDpAsState(if (selected) 92.dp else 68.dp, spring(.8f, 450f), label = "selected tile")
    FocusPanel(modifier.width(width).height(if (selected) 92.dp else 68.dp), onClick, onFocus) {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF334255), Color(0xFF192231)))),
            contentAlignment = Alignment.Center
        ) {
            art?.invoke()
            if (icon != null) Glyph(icon, Modifier.size(if (selected) 37.dp else 26.dp))
            else Column(Modifier.align(Alignment.BottomStart).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=.7f)))).fillMaxWidth().padding(8.dp)) {
                Text(title, fontSize = if (selected) 12.sp else 9.sp, fontWeight = FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ActivityCard(kicker: String, title: String, description: String, mark: Mark, action: () -> Unit) {
    FocusPanel(Modifier.width(215.dp).height(94.dp), action) {
        Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF263449).copy(alpha=.88f), Glass.copy(alpha=.8f)))).padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(kicker, fontSize = 8.sp, letterSpacing = 1.sp, color = Soft); Glyph(mark, Modifier.size(16.dp), Soft) }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(description, fontSize = 10.sp, color = Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GameActivities(game: Game, ui: UiState, memory: ConsoleMemory, vm: CoreViewModel, edit: () -> Unit) {
    var preview by remember(game.id) { mutableStateOf("Select to view the generated command.") }
    var request by remember(game.id) { mutableIntStateOf(0) }
    var show by remember(game.id) { mutableStateOf(false) }
    LaunchedEffect(game.id, request) { if (request > 0) { preview = vm.preview(game.id); show = true } }
    val played = memory.played(game)
    LazyRow(Modifier.padding(top = 12.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ActivityCard("CONTAINER", ui.data.containers.find { it.id == game.container_id }?.name ?: "Not available", "Your isolated Wine environment", Mark.Box, edit) }
        item { ActivityCard("PERFORMANCE", ui.data.profiles.find { it.id == game.profile_id }?.name ?: "Not available", "Resolution, frame rate & compatibility", Mark.Tune, edit) }
        item { ActivityCard("UNDER THE HOOD", "Launch command", "Preview the saved environment & arguments", Mark.Chip, { request++ }) }
        item { ActivityCard("LAST LAUNCH ATTEMPT", if (played == 0L) "Not played yet" else SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(played)), "Local to this device", Mark.Info, {}) }
    }
    if (show) ConsolePrompt("Launch plan", preview, "Done", close = { show = false }, confirm = { show = false })
}

@Composable
fun LibraryPage(ui: UiState, memory: ConsoleMemory, search: String?, changeSearch: (String?) -> Unit, back: () -> Unit, add: () -> Unit, open: (Game) -> Unit) {
    var ascending by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 25.dp)) {
        PageTitle("Game Library", back = back)
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Installed", fontSize = 20.sp, fontWeight = FontWeight.Light); Text("${ui.data.games.size} games", fontSize = 11.sp, color = Soft) }
            Spacer(Modifier.weight(1f))
            if (search != null) OutlinedTextField(search, { changeSearch(it) }, placeholder = { Text("Search games") }, singleLine = true, modifier = Modifier.width(220.dp).height(55.dp))
            else ConsoleIcon(Mark.Search, "Search", { changeSearch("") })
            Spacer(Modifier.width(12.dp))
            ConsoleButton(if (ascending) "Name A–Z" else "Name Z–A", { ascending = !ascending }, mark = Mark.Sort)
            Spacer(Modifier.width(12.dp))
            ConsoleButton("Add game", add, mark = Mark.Add)
        }
        val games = ui.data.games.filter { it.name.contains(search ?: "", true) }.sortedBy { it.name.lowercase() }.let { if (ascending) it else it.reversed() }
        if (games.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Glyph(Mark.Library, Modifier.size(46.dp), Soft)
                Text(if (search.isNullOrBlank()) "Your collection starts here." else "No games found.", fontSize = 27.sp, fontWeight = FontWeight.Light)
                Text("Only your own Windows games appear in this library.", fontSize = 12.sp, color = Soft)
                ConsoleButton("Add game", add, primary = true)
            }
        } else LazyVerticalGrid(GridCells.Adaptive(145.dp), horizontalArrangement = Arrangement.spacedBy(17.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            items(games, key = { it.id }) { game -> Column {
                FocusPanel(Modifier.fillMaxWidth().aspectRatio(1f), { open(game) }) { GameArt(game, Modifier.fillMaxSize(), memory.art(game)) }
                Text(game.name, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("WINDOWS PC", fontSize = 8.sp, color = Soft, modifier = Modifier.padding(start = 4.dp, top = 3.dp))
            } }
        }
    }
}
