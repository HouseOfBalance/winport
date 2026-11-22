package io.winport.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ContainersScreen(ui: UiState, vm: CoreViewModel) {
    var edit by remember { mutableStateOf<Container?>(null) }
    var delete by remember { mutableStateOf<Container?>(null) }
    ScreenHeading("Containers", "Isolated Wine prefixes for your games.") {
        FilledIconButton(onClick = { edit = Container("", "", "", "") }, enabled = !ui.busy) { Icon(Icons.Outlined.Add, "Create container") }
    }
    if (ui.data.containers.isEmpty()) HintCard("A clean slate", "Import a Wine component first. Each container gets its own prefix directory; Wine initialization is deferred to a future runtime backend.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ui.data.containers, key = { it.id }) { container ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Inventory2, null, tint = Mint)
                    Text(container.name, style = MaterialTheme.typography.titleMedium)
                    Text(container.wine_component, color = Mint, style = MaterialTheme.typography.labelMedium)
                    Text(container.dir, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { edit = container }, enabled = !ui.busy) { Text("Edit") }
                        TextButton(onClick = { delete = container }, enabled = !ui.busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    edit?.let { original ->
        var name by remember(original) { mutableStateOf(original.name) }
        var wine by remember(original) { mutableStateOf(original.wine_component) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (original.id.isEmpty()) "New container" else "Edit container") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                Choice("Wine", wine, ui.data.components.filter { it.kind == "Wine" }.map { it.id to it.version }) { wine = it }
                if (ui.data.components.none { it.kind == "Wine" }) Text("No Wine installed. Import an archive in Components.", color = Mint)
            }
        }, confirmButton = { TextButton(onClick = { vm.saveContainer(original.id, name, wine); edit = null }, enabled = name.isNotBlank() && wine.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
    delete?.let { container ->
        ConfirmDelete("Delete ${container.name}?", "This permanently removes the prefix and all files in it. Containers used by games cannot be deleted.", { delete = null }) { vm.remove("Container", container.id); delete = null }
    }
}

@Composable
fun ProfilesScreen(ui: UiState, vm: CoreViewModel) {
    var edit by remember { mutableStateOf<Profile?>(null) }
    ScreenHeading("Profiles", "Tune the balance. Find your sweet spot.") {
        FilledIconButton(onClick = { edit = Profile() }, enabled = !ui.busy) { Icon(Icons.Outlined.Add, "Add profile") }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ui.data.profiles, key = { it.id }) { profile ->
            Card(onClick = { edit = profile }, enabled = !ui.busy, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Tune, null, tint = Mint)
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Text("${profile.width} × ${profile.height}  ·  ${profile.fps_limit} FPS", color = Mint)
                    Text("Big block ${profile.bigblock}  /  Strong mem ${profile.strongmem}  /  Safe flags ${profile.safeflags}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (ui.data.profiles.isEmpty()) HintCard("No profiles", "Create a profile to configure launch settings.")
    edit?.let { ProfileEditor(it, ui, vm) { edit = null } }
}

@Composable
fun ComponentsScreen(ui: UiState, vm: CoreViewModel) {
    var kind by remember { mutableStateOf("Box64") }
    var delete by remember { mutableStateOf<Component?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importComponent(uri, kind) }
    ScreenHeading("Components", "Bring your own runtime. Stay in control.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            HintCard("Nothing bundled. Nothing hidden.", "Import trusted .tar.xz, .tar.gz or .zip archives. The archive basename becomes its version. Flatten links before import; see README for layouts.")
        }
        item { Choice("Component type", kind, listOf("Box64", "Wine", "Dxvk", "Rootfs").map { it to it }) { kind = it } }
        item {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Import archive") }
        }
        item { Text("INSTALLED · ${ui.data.components.size}", style = MaterialTheme.typography.labelMedium, color = Muted) }
        items(ui.data.components, key = { it.id }) { component ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(component.kind, color = Mint, style = MaterialTheme.typography.labelMedium)
                    Text(component.version, style = MaterialTheme.typography.titleMedium)
                    Text(component.path, color = Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { delete = component }, enabled = !ui.busy) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Text("Runtime preview only. Android 10+ requires a proot / loader backend for real execution. A desktop display server, audio and GPU integration are not included.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
    }
    delete?.let { component -> ConfirmDelete("Uninstall ${component.version}?", "This removes the extracted component. Wine in use by a container cannot be removed.", { delete = null }) { vm.remove("Component", component.id); delete = null } }
}

@Composable
fun HintCard(title: String, text: String) {
    Surface(color = Panel, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Mint)
            Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
fun ConfirmDelete(title: String, text: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = confirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
