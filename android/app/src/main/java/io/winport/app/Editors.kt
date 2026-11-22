package io.winport.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Choice(label: String, value: String, options: List<Pair<String, String>>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(options.find { it.first == value }?.second ?: "Select $label", {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { changed(id); expanded = false }) }
        }
    }
}

@Composable
fun EditorFrame(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable
fun GameEditor(original: Game, ui: UiState, vm: CoreViewModel, close: () -> Unit) {
    var game by remember(original) { mutableStateOf(original) }
    var args by remember { mutableStateOf(original.extra_args.joinToString("\n")) }
    var preview by remember { mutableStateOf("Save your changes, then preview the persisted launch plan.") }
    var previewRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(previewRequest) {
        if (previewRequest > 0 && game.id.isNotEmpty()) preview = vm.preview(game.id)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importExe(uri, game.container_id) { path -> game = game.copy(exe_path = path) }
    }
    EditorFrame(if (original.id.isEmpty()) "Add a game" else "Edit game", close) {
        Text("Choose a prefix and import an executable, or enter an existing relative path. Game data must also be copied into that prefix.", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(game.name, { game = game.copy(name = it) }, label = { Text("Game name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Choice("Container", game.container_id, ui.data.containers.map { it.id to it.name }) { game = game.copy(container_id = it, exe_path = "") }
        if (ui.data.containers.isEmpty()) Text("Import Wine in Components, then create a container first.", color = Mint)
        Choice("Profile", game.profile_id, ui.data.profiles.map { it.id to it.name }) { game = game.copy(profile_id = it) }
        OutlinedTextField(game.exe_path, { game = game.copy(exe_path = it) }, label = { Text("Relative .exe path") }, placeholder = { Text("drive_c/Games/MyGame/game.exe") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = game.container_id.isNotEmpty() && !ui.busy) {
            Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose .exe file")
        }
        OutlinedTextField(args, { args = it }, label = { Text("Extra arguments — one per line") }, supportingText = { Text("Spaces within a line are preserved. No shell quoting needed.") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(onClick = { vm.saveGame(game.copy(extra_args = args.lines().filter { it.isNotEmpty() })); close() }, enabled = !ui.busy && game.name.isNotBlank() && game.exe_path.isNotBlank() && game.container_id.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save game") }
        HorizontalDivider()
        Text("LAUNCH PLAN", color = Mint, style = MaterialTheme.typography.labelMedium)
        Text(preview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { previewRequest++ }, enabled = game.id.isNotEmpty() && !ui.busy) { Text("Preview saved command + environment") }
        if (original.id.isNotEmpty()) TextButton(onClick = { vm.remove("Game", original.id); close() }) { Text("Remove from library", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun ProfileEditor(original: Profile, ui: UiState, vm: CoreViewModel, close: () -> Unit) {
    var profile by remember { mutableStateOf(original) }
    var cores by remember { mutableStateOf(original.cpu_cores.joinToString(",")) }
    var width by remember { mutableStateOf(original.width.toString()) }
    var height by remember { mutableStateOf(original.height.toString()) }
    val parsedCores = if (cores.isBlank()) emptyList() else cores.split(',').mapNotNull { it.trim().toIntOrNull() }
    val valid = (width.toIntOrNull() ?: 0) in 320..7680 && (height.toIntOrNull() ?: 0) in 200..4320 && (cores.isBlank() || cores.split(',').all { (it.trim().toIntOrNull() ?: -1) in 0..1023 })
    EditorFrame("Performance profile", close) {
        OutlinedTextField(profile.name, { profile = profile.copy(name = it) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        SettingSlider("Dynarec big block", profile.bigblock, 3) { profile = profile.copy(bigblock = it) }
        SettingSlider("Strong memory ordering", profile.strongmem, 2) { profile = profile.copy(strongmem = it) }
        SettingSlider("Safe flags", profile.safeflags, 2) { profile = profile.copy(safeflags = it) }
        Text("FPS limit: ${if (profile.fps_limit == 0) "Unlimited" else profile.fps_limit}")
        Slider(profile.fps_limit.toFloat(), { profile = profile.copy(fps_limit = it.toInt()) }, valueRange = 0f..240f, steps = 23)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("DXVK performance HUD")
            Switch(profile.dxvk_hud, { profile = profile.copy(dxvk_hud = it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(width, { width = it }, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(height, { height = it }, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        OutlinedTextField(cores, { cores = it }, label = { Text("CPU cores: e.g. 4,5,6,7") }, supportingText = { Text("Blank uses all available cores. Offline/invalid cores fail at launch.") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveProfile(profile.copy(width = width.toInt(), height = height.toInt(), cpu_cores = parsedCores)); close() }, enabled = valid && profile.name.isNotBlank() && !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
        if (original.id.isNotEmpty()) TextButton(onClick = { vm.remove("Profile", profile.id); close() }) { Text("Delete profile", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun SettingSlider(label: String, value: Int, max: Int, change: (Int) -> Unit) {
    Text("$label: $value")
    Slider(value.toFloat(), { change(it.toInt()) }, valueRange = 0f..max.toFloat(), steps = max - 1)
}
