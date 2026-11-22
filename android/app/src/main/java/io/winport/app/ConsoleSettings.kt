package io.winport.app

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val sections = listOf(
    Triple("Containers", "Isolated Wine environments for your games", Mark.Box),
    Triple("Performance Profiles", "Fine-tune compatibility, display & performance", Mark.Tune),
    Triple("Components", "Manage your Box64, Wine, DXVK & rootfs", Mark.Chip),
    Triple("System info", "Your device and the Winport runtime", Mark.Info)
)

@Composable
fun SettingsPage(back: () -> Unit, open: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 25.dp)) {
        PageTitle("Settings", "WINPORT / SYSTEM", back)
        LazyColumn(Modifier.fillMaxWidth(.85f).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(sections.size) { i ->
                val item = sections[i]
                MenuRow(item.first, item.second, item.third, if (i == 0) Modifier.focusRequester(first) else Modifier) { open(item.first) }
            }
        }
    }
}

@Composable
fun SettingsDetail(section: String, ui: UiState, vm: CoreViewModel, back: () -> Unit) {
    var selected by remember(section) { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<Pair<String, String>?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(section) { first.requestFocus() }
    val profiles = section == "Performance Profiles"
    val containers = section == "Containers"
    val components = section == "Components"
    val list = when {
        profiles -> ui.data.profiles.map { it.id to it.name }
        containers -> ui.data.containers.map { it.id to it.name }
        components -> ui.data.components.map { it.id to "${it.kind} · ${it.version}" }
        else -> listOf("device" to "Device", "runtime" to "Runtime & limitations", "about" to "About Winport")
    }
    val active = selected ?: if (components) "new" else list.firstOrNull()?.first ?: "new"
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 23.dp)) {
        PageTitle(section, "SETTINGS / ${section.uppercase()}", back)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LazyColumn(Modifier.width(235.dp).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    val title = when { profiles -> "Create profile"; containers -> "Create container"; components -> "Import component"; else -> "Overview" }
                    MenuRow(title, if (active == "new") "Selected" else "", if (section == "System info") Mark.Info else Mark.Add, Modifier.focusRequester(first)) { selected = "new" }
                }
                items(list, key = { it.first }) { (id, name) -> MenuRow(name, if (id == active) "Selected" else "", sections.first { it.first == section }.third, action = { selected = id }) }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha=.12f)))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup().padding(end = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                when {
                    containers -> {
                        val original = ui.data.containers.find { it.id == active }
                        key(active, original) {
                            var name by remember { mutableStateOf(original?.name ?: "") }
                            var wine by remember { mutableStateOf(original?.wine_component ?: "") }
                            Text(original?.name ?: "New container", fontSize = 27.sp, fontWeight = FontWeight.Light)
                            Text("A private Wine prefix. Game files stay isolated from other containers.", color = Soft, fontSize = 12.sp)
                            Field("Name", name, { name = it })
                            Choice("Wine component", wine, ui.data.components.filter { it.kind == "Wine" }.map { it.id to it.version }) { wine = it }
                            if (ui.data.components.none { it.kind == "Wine" }) Text("Import a Wine archive under Components before creating a container.", color = Ice, fontSize = 12.sp)
                            ConsoleButton("Save container", { vm.saveContainer(original?.id ?: "", name, wine) }, primary = true, enabled = name.isNotBlank() && wine.isNotBlank() && !ui.busy)
                            if (original != null) {
                                DetailLabel("Prefix directory", original.dir)
                                ConsoleButton("Delete container", { delete = "Container" to original.id }, enabled = !ui.busy)
                            }
                        }
                    }
                    profiles -> {
                        val original = ui.data.profiles.find { it.id == active } ?: Profile()
                        Text(if (active == "new") "New profile" else original.name, fontSize = 27.sp, fontWeight = FontWeight.Light)
                        Text("Choose the balance between speed and compatibility.", color = Soft, fontSize = 12.sp)
                        key(active) { ProfileFields(original, ui, vm) {} }
                        if (active != "new") ConsoleButton("Delete profile", { delete = "Profile" to active }, enabled = !ui.busy)
                    }
                    components -> {
                        val component = ui.data.components.find { it.id == active }
                        if (component == null) {
                            var kind by remember { mutableStateOf("Box64") }
                            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importComponent(uri, kind) }
                            Text("Bring your own runtime", fontSize = 27.sp, fontWeight = FontWeight.Light)
                            Text("Nothing is bundled. Import trusted archives from your device.", fontSize = 12.sp, color = Soft)
                            Choice("Component type", kind, listOf("Box64", "Wine", "Dxvk", "Rootfs").map { it to it }) { kind = it }
                            ConsoleButton("Choose archive", { picker.launch(arrayOf("*/*")) }, primary = true, mark = Mark.Download, enabled = !ui.busy)
                            Text(".tar.xz  /  .tar.gz  /  .zip", color = Ice, fontSize = 12.sp)
                            Text("The archive basename is used as its version. Archives must be flattened: links and special files are rejected. Existing versions are never overwritten.", fontSize = 12.sp, color = Soft)
                            HorizontalDivider(color = Color.White.copy(alpha=.1f))
                            Text("Real launch on Android 10+ still needs a proot / loader backend, a display server and GPU integration. Importing runtimes alone does not enable game execution.", color = Soft, fontSize = 11.sp)
                        } else {
                            Text(component.kind, fontSize = 30.sp, fontWeight = FontWeight.Light)
                            DetailLabel("Version", component.version)
                            DetailLabel("Installation path", component.path)
                            DetailLabel("Component ID", component.id)
                            ConsoleButton("Uninstall component", { delete = "Component" to component.id }, enabled = !ui.busy)
                        }
                    }
                    else -> {
                        Text(if (active == "runtime") "Runtime & limitations" else if (active == "about") "About Winport" else "Your system", fontSize = 29.sp, fontWeight = FontWeight.Light)
                        if (active == "runtime") {
                            DetailLabel("Architecture", "Kotlin / Compose presentation → JSON JNI → Rust core")
                            DetailLabel("Runtime", "Box64 + Wine + DXVK + imported rootfs. No binaries bundled.")
                            Text("Android 10+ W^X blocks direct execution from writable app data. A policy-compliant proot / ld-linux loader backend, display, GPU and audio integration remain next steps. AAA games are unlikely to run well.", color = Soft, fontSize = 13.sp)
                        } else if (active == "about") {
                            DetailLabel("Winport", "0.1.0 · Independent experimental launcher")
                            Text("Original vector geometry, generated gradients and Android's system sans font. No Sony assets, logos, button symbols, fonts or sounds. Not affiliated with Sony Interactive Entertainment.", color = Soft, fontSize = 13.sp)
                        } else {
                            DetailLabel("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                            DetailLabel("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                            DetailLabel("Supported ABIs", Build.SUPPORTED_ABIS.joinToString())
                            DetailLabel("Your collection", "${ui.data.games.size} games · ${ui.data.containers.size} containers · ${ui.data.components.size} components")
                        }
                    }
                }
            }
        }
    }
    delete?.let { (kind, id) -> ConsolePrompt("Delete ${kind.lowercase()}?", if (kind == "Container") "The prefix and its files will be permanently deleted. Items in use by games cannot be removed." else "This permanently removes the selected item. Items in use cannot be removed.", "Delete", close = { delete = null }) { vm.remove(kind, id); delete = null; selected = null } }
}
