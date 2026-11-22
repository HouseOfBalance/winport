package io.winport.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Field(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, multiline: Boolean = false, hint: String = "") {
    OutlinedTextField(value, change, modifier.fillMaxWidth(), label = { Text(label, fontSize = 12.sp) }, singleLine = !multiline, minLines = if (multiline) 2 else 1,
        supportingText = if (hint.isBlank()) null else ({ Text(hint, fontSize = 10.sp) }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha=.22f), focusedLabelColor = Color.White))
}

@Composable
fun Choice(label: String, value: String, options: List<Pair<String, String>>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), fontSize = 9.sp, color = Soft, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 5.dp))
        Box {
            ConsoleButton(options.find { it.first == value }?.second ?: "Select $label", { expanded = true }, Modifier.fillMaxWidth(), mark = Mark.More)
            DropdownMenu(expanded, { expanded = false }, containerColor = Glass) {
                if (options.isEmpty()) DropdownMenuItem(text = { Text("No options available") }, onClick = {}, enabled = false)
                options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { changed(id); expanded = false }) }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun GameForm(original: Game, ui: UiState, vm: CoreViewModel, memory: ConsoleMemory, close: () -> Unit) {
    BackHandler(onBack = close)
    var game by remember(original) { mutableStateOf(original) }
    var args by remember { mutableStateOf(original.extra_args.joinToString("\n")) }
    var artwork by remember { mutableStateOf(memory.art(original)) }
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importExe(uri, game.container_id) { path -> game = game.copy(exe_path = path) } }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); artwork = uri.toString() }
            catch (_: SecurityException) { artwork = null }
        }
    }
    Box(Modifier.fillMaxSize().background(Night).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        GameArt(game, Modifier.fillMaxSize(), artwork, hero = true)
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 23.dp).imePadding()) {
            PageTitle(if (original.id.isEmpty()) "Add a game" else "Edit game", "YOUR COLLECTION / GAME DETAILS", close)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(35.dp)) {
                Column(Modifier.width(220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(170.dp)) { GameArt(game, Modifier.fillMaxSize(), artwork) }
                    Text(game.name.ifBlank { "Your next adventure" }, fontSize = 24.sp, fontWeight = FontWeight.Light)
                    ConsoleButton("Choose artwork", { imagePicker.launch(arrayOf("image/*")) }, mark = Mark.Image)
                    if (artwork != null) ConsoleButton("Use generated artwork", { artwork = null })
                    Text("Pick your own image, or use an original gradient generated from the game name.", fontSize = 10.sp, color = Soft)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).focusGroup(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Field("Game name", game.name, { game = game.copy(name = it) }, Modifier.focusRequester(first))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f)) { Choice("Container", game.container_id, ui.data.containers.map { it.id to it.name }) { game = game.copy(container_id = it, exe_path = "") } }
                        Column(Modifier.weight(1f)) { Choice("Performance profile", game.profile_id, ui.data.profiles.map { it.id to it.name }) { game = game.copy(profile_id = it) } }
                    }
                    if (ui.data.containers.isEmpty()) Text("No containers yet. Go to Settings → Components to import Wine, then create a container.", fontSize = 12.sp, color = Ice)
                    Field("Executable inside container", game.exe_path, { game = game.copy(exe_path = it) }, hint = "For example: drive_c/Games/MyGame/game.exe")
                    ConsoleButton("Choose .exe via file picker", { picker.launch(arrayOf("*/*")) }, mark = Mark.Download, enabled = game.container_id.isNotBlank() && !ui.busy)
                    Text("SAF copies the selected executable only. Place its DLLs and game assets in the same prefix separately.", fontSize = 10.sp, color = Soft)
                    Field("Extra arguments", args, { args = it }, multiline = true, hint = "One argument per line. Spaces are preserved; no shell quoting.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                        ConsoleButton("Save game", {
                            val saved = game.copy(extra_args = args.lines().filter { it.isNotEmpty() })
                            memory.setArt(saved, artwork)
                            vm.saveGame(saved); close()
                        }, primary = true, enabled = !ui.busy && game.name.isNotBlank() && game.exe_path.isNotBlank() && game.container_id.isNotBlank() && game.profile_id.isNotBlank())
                        ConsoleButton("Cancel", close)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileFields(original: Profile, ui: UiState, vm: CoreViewModel, saved: () -> Unit) {
    var profile by remember(original) { mutableStateOf(original) }
    var width by remember(original) { mutableStateOf(original.width.toString()) }
    var height by remember(original) { mutableStateOf(original.height.toString()) }
    var cores by remember(original) { mutableStateOf(original.cpu_cores.joinToString(",")) }
    val valid = (width.toIntOrNull() ?: 0) in 320..7680 && (height.toIntOrNull() ?: 0) in 200..4320 && (cores.isBlank() || cores.split(',').all { (it.trim().toIntOrNull() ?: -1) in 0..1023 })
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Field("Profile name", profile.name, { profile = profile.copy(name = it) })
        ProfileSlider("Dynarec big block", profile.bigblock, 3) { profile = profile.copy(bigblock = it) }
        ProfileSlider("Strong memory ordering", profile.strongmem, 2) { profile = profile.copy(strongmem = it) }
        ProfileSlider("Safe flags", profile.safeflags, 2) { profile = profile.copy(safeflags = it) }
        Text("Frame limit   ${if (profile.fps_limit == 0) "Unlimited" else "${profile.fps_limit} FPS"}", fontSize = 12.sp)
        Slider(profile.fps_limit.toFloat(), { profile = profile.copy(fps_limit = it.toInt()) }, valueRange = 0f..240f, steps = 23)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("DXVK performance HUD", fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(profile.dxvk_hud, { profile = profile.copy(dxvk_hud = it) }) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Width", width, { width = it }, Modifier.weight(1f))
            Field("Height", height, { height = it }, Modifier.weight(1f))
        }
        Field("CPU core affinity", cores, { cores = it }, hint = "Comma-separated core indices (e.g. 4,5,6,7). Blank = system default.")
        ConsoleButton("Save profile", { vm.saveProfile(profile.copy(width = width.toInt(), height = height.toInt(), cpu_cores = if (cores.isBlank()) emptyList() else cores.split(',').map { it.trim().toInt() })); saved() }, primary = true, enabled = valid && !ui.busy && profile.name.isNotBlank())
    }
}

@Composable
fun ProfileSlider(label: String, value: Int, max: Int, change: (Int) -> Unit) {
    Row { Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(value.toString(), fontSize = 12.sp, color = Ice) }
    Slider(value.toFloat(), { change(it.toInt()) }, valueRange = 0f..max.toFloat(), steps = max - 1)
}
