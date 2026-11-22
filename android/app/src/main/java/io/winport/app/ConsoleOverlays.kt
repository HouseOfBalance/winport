package io.winport.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun ModalShade(close: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    BackHandler(onBack = close)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .66f)).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        Box(Modifier.fillMaxSize().pointerInput(close) { detectTapGestures { close() } })
        content()
    }
}

@Composable
fun ConsolePrompt(title: String, text: String, action: String, close: () -> Unit, confirm: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    ModalShade(close) {
        Column(Modifier.align(Alignment.Center).widthIn(max = 610.dp).fillMaxWidth(.8f).background(Glass, RoundedCornerShape(12.dp)).padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(title, fontSize = 27.sp, fontWeight = FontWeight.Light)
            Text(text, fontSize = 13.sp, color = Soft, modifier = Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState()))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConsoleButton(action, confirm, Modifier.focusRequester(first), primary = true)
                if (action != "Close" && action != "Done") ConsoleButton("Cancel", close)
            }
        }
    }
}

@Composable
fun SideMenu(game: Game, close: () -> Unit, edit: () -> Unit, remove: () -> Unit) {
    val first = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    val offset by animateDpAsState(if (entered) 0.dp else 340.dp, spring(.9f, 380f), label = "side menu")
    LaunchedEffect(Unit) { entered = true; first.requestFocus() }
    ModalShade(close) {
        Column(Modifier.align(Alignment.CenterEnd).offset(x = offset).width(310.dp).fillMaxHeight().background(Color(0xFF141A26)).verticalScroll(rememberScrollState()).padding(horizontal = 25.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GAME OPTIONS", fontSize = 10.sp, color = Soft, letterSpacing = 2.sp)
            Text(game.name, fontSize = 27.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(bottom = 14.dp))
            MenuRow("Edit game", "Name, executable & artwork", Mark.Tune, Modifier.focusRequester(first), edit)
            MenuRow("Change container", "Choose a Wine environment", Mark.Box, action = edit)
            MenuRow("Change profile", "Performance & compatibility", Mark.Chip, action = edit)
            MenuRow("Remove", "Keep files on this device", Mark.Close, action = remove)
            Spacer(Modifier.height(12.dp))
            ConsoleButton("Back", close, mark = Mark.Back)
        }
    }
}

@Composable
fun MenuRow(title: String, subtitle: String, mark: Mark, modifier: Modifier = Modifier, action: () -> Unit) {
    FocusPanel(modifier.fillMaxWidth().height(66.dp), action) { focused ->
        Row(Modifier.fillMaxSize().background(if (focused) Color.White.copy(alpha=.12f) else Color.White.copy(alpha=.025f)).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(mark, Modifier.size(23.dp), if (focused) Color.White else Soft)
            Spacer(Modifier.width(15.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Light); if (subtitle.isNotEmpty()) Text(subtitle, color = Soft, fontSize = 10.sp) }
        }
    }
}

@Composable
fun ControlCenter(selected: String?, ui: UiState, close: () -> Unit, home: () -> Unit, page: (String?) -> Unit, exit: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(selected) { first.requestFocus() }
    val items = listOf("Home" to Mark.Home, "Notifications" to Mark.Bell, "Downloads" to Mark.Download, "Sound" to Mark.Sound, "Network" to Mark.Network, "Accessibility" to Mark.Access, "Power" to Mark.Power)
    ModalShade({ if (selected != null) page(null) else close() }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xFF101724)).padding(horizontal = 48.dp, vertical = 17.dp)) {
            if (selected != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(selected, fontSize = 25.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.weight(1f)); ConsoleIcon(Mark.Close, "Close control panel", { page(null) })
                }
                when (selected) {
                    "Power" -> Row(Modifier.padding(vertical = 16.dp)) { ConsoleButton("Exit app", exit, primary = true, mark = Mark.Power) }
                    "Notifications" -> Text(ui.error ?: ui.message ?: "You're all caught up. No new notifications.", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 23.dp))
                    "Downloads" -> Text(if (ui.busy) "A core operation is in progress…" else "Downloads are user-triggered. Manage libretro cores in Core settings.", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 23.dp))
                    "Sound" -> {
                        val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
                        var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Media volume", fontSize = 13.sp, color = Soft, modifier = Modifier.width(130.dp))
                            Slider(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) }, valueRange = 0f..audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat(), modifier = Modifier.width(320.dp))
                        }
                    }
                    "Network", "Accessibility" -> Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Managed by Android", color = Soft, fontSize = 13.sp, modifier = Modifier.width(230.dp))
                        ConsoleButton("Open system settings", { context.startActivity(Intent(if (selected == "Network") Settings.ACTION_WIRELESS_SETTINGS else Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                    }
                }
            } else {
                Text("CONTROL CENTER", color = Soft, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 16.dp))
            }
            Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
                items.forEachIndexed { i, (name, mark) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(85.dp)) {
                        ConsoleIcon(mark, name, { if (name == "Home") home() else page(name) }, (if (i == 0) Modifier.focusRequester(first) else Modifier).background(if (selected == name) Color(0xFF43516A) else Glass, RoundedCornerShape(50)))
                        Text(name, fontSize = 10.sp, color = if (selected == name) Color.White else Soft, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}
