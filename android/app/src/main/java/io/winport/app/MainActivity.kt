package io.winport.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

val Mint = Color(0xFFB6F36A)
val Ink = Color(0xFF0F1216)
val Panel = Color(0xFF1A2027)
val Muted = Color(0xFF9AA5B1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, surface = Panel, secondary = Color(0xFFB9C8DE))) {
                WinportApp()
            }
        }
    }
}

@Composable
fun WinportApp(vm: CoreViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var game by remember { mutableStateOf<Game?>(null) }
    var addGame by remember { mutableStateOf(false) }
    val labels = listOf("Library", "Containers", "Profiles", "Components")
    val icons = listOf(Icons.Outlined.SportsEsports, Icons.Outlined.Inventory2, Icons.Outlined.Tune, Icons.Outlined.Extension)
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                labels.forEachIndexed { i, label -> NavigationBarItem(
                    selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], label) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Mint.copy(alpha = .15f), selectedIconColor = Mint, selectedTextColor = Mint)
                ) }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = Mint, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("winport", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Surface(color = Mint.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
                    Text("EARLY ACCESS", color = Mint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
            when (tab) {
                0 -> LibraryScreen(ui, onAdd = { addGame = true }, onEdit = { game = it }, vm = vm)
                1 -> ContainersScreen(ui, vm)
                2 -> ProfilesScreen(ui, vm)
                3 -> ComponentsScreen(ui, vm)
            }
        }
    }
    if (addGame || game != null) GameEditor(game ?: Game(), ui, vm) { addGame = false; game = null }
    val notice = ui.error ?: ui.message
    if (notice != null) AlertDialog(onDismissRequest = vm::dismiss, title = { Text(if (ui.error != null) "Unable to continue" else "Winport") }, text = { Text(notice) }, confirmButton = { TextButton(onClick = vm::dismiss) { Text("Got it") } })
}

@Composable
fun ScreenHeading(title: String, subtitle: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
        }
        action()
    }
}

@Composable
fun LibraryScreen(ui: UiState, onAdd: () -> Unit, onEdit: (Game) -> Unit, vm: CoreViewModel) {
    var grid by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    ScreenHeading("Your library", "Windows games. A new home.") {
        FilledIconButton(onClick = onAdd, enabled = !ui.busy) { Icon(Icons.Outlined.Add, "Add game") }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF27372B), Panel))).padding(20.dp)) {
            Text("BUILT FOR POSSIBILITY", color = Mint, style = MaterialTheme.typography.labelSmall)
            Text("Your next adventure\nstarts here.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 10.dp))
            Text("Box64  +  Wine  +  DXVK", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(search, { search = it }, placeholder = { Text("Search your games") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp))
        IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, "Toggle grid or list") }
    }
    val games = ui.data.games.filter { it.name.contains(search, ignoreCase = true) }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("ALL GAMES", style = MaterialTheme.typography.labelSmall, color = Muted)
        Text("${games.size} titles", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
    if (games.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.SportsEsports, null, tint = Mint, modifier = Modifier.size(48.dp))
            Text(if (search.isBlank()) "Make room for play" else "No matches", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 14.dp))
            Text(if (search.isBlank()) "Import components, create a container,\nthen bring your first .exe." else "Try a different game name.", color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
            OutlinedButton(onClick = onAdd, enabled = !ui.busy) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add your first game") }
        }
    } else if (grid) {
        LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(games, key = { it.id }) { GameCard(it, ui, onEdit, vm) }
        }
    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(games, key = { it.id }) { GameCard(it, ui, onEdit, vm) } }
}

@Composable
fun GameCard(game: Game, ui: UiState, edit: (Game) -> Unit, vm: CoreViewModel) {
    Card(onClick = { edit(game) }, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.SportsEsports, null, tint = Mint, modifier = Modifier.padding(vertical = 15.dp).size(36.dp))
            Text(game.name, fontWeight = FontWeight.SemiBold)
            Text(ui.data.profiles.find { it.id == game.profile_id }?.name ?: "No profile", color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (game.id in ui.running) vm.stop(game) else vm.launch(game) }, enabled = !ui.busy, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
                Icon(if (game.id in ui.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                Text(if (game.id in ui.running) "Stop" else "Launch")
            }
        }
    }
}
