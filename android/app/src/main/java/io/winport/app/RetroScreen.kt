package io.winport.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream

object RetroNative {
    init { System.loadLibrary("winport_core") }
    external fun command(action: String, payload: String = "{}"): String
    external fun surface(surface: Surface?)
    external fun audio(): ShortArray
    external fun input(port: Int, id: Int, value: Short)
}

object RetroInput {
    @Volatile var active = false
    @Volatile var paused = false
    private val buttons = Array(3) { BooleanArray(16) }
    private val keys = mapOf(KeyEvent.KEYCODE_BUTTON_A to 8, KeyEvent.KEYCODE_BUTTON_B to 0,
        KeyEvent.KEYCODE_BUTTON_X to 9, KeyEvent.KEYCODE_BUTTON_Y to 1,
        KeyEvent.KEYCODE_BUTTON_SELECT to 2, KeyEvent.KEYCODE_BUTTON_START to 3,
        KeyEvent.KEYCODE_DPAD_UP to 4, KeyEvent.KEYCODE_DPAD_DOWN to 5,
        KeyEvent.KEYCODE_DPAD_LEFT to 6, KeyEvent.KEYCODE_DPAD_RIGHT to 7,
        KeyEvent.KEYCODE_BUTTON_L1 to 10, KeyEvent.KEYCODE_BUTTON_R1 to 11,
        KeyEvent.KEYCODE_BUTTON_L2 to 12, KeyEvent.KEYCODE_BUTTON_R2 to 13,
        KeyEvent.KEYCODE_BUTTON_THUMBL to 14, KeyEvent.KEYCODE_BUTTON_THUMBR to 15)
    @Synchronized fun button(source: Int, id: Int, down: Boolean) {
        buttons[source][id] = down
        RetroNative.input(0, id, if (buttons.any { it[id] }) 1 else 0)
    }
    @Synchronized fun clear() {
        buttons.forEach { it.fill(false) }
        for (id in 0..19) RetroNative.input(0, id, 0)
    }
    fun key(event: KeyEvent): Boolean {
        if (!active || paused) return false
        val id = keys[event.keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) button(0, id, event.action == KeyEvent.ACTION_DOWN)
        return true
    }
    fun motion(event: MotionEvent): Boolean {
        if (!active || paused || event.source and android.view.InputDevice.SOURCE_JOYSTICK == 0) return false
        listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ).forEachIndexed { index, axis ->
            val value = event.getAxisValue(axis).let { if (kotlin.math.abs(it) < .15f) 0f else it }.coerceIn(-1f, 1f)
            RetroNative.input(0, 16 + index, (value * 32767).toInt().toShort())
        }
        val x = event.getAxisValue(MotionEvent.AXIS_HAT_X); val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        button(2, 4, y < -.5f); button(2, 5, y > .5f); button(2, 6, x < -.5f); button(2, 7, x > .5f)
        button(2, 12, event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > .5f)
        button(2, 13, event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > .5f)
        return true
    }
}

private val coreNames = listOf("swanstation", "mednafen_psx", "pcsx_rearmed", "ppsspp", "play")
private fun coreLabel(name: String) = when (name) {
    "ppsspp" -> "PSP · PPSSPP"
    "play" -> "PS2 · Play!"
    "pcsx_rearmed" -> "PS1 · PCSX ReARMed HLE"
    else -> "PS1 · $name"
}
private fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
    ?: error("This step supports arm64-v8a and x86_64 only")
private fun coreFile(context: Context, name: String) = File(context.filesDir, "cores/${abi()}/${name}_libretro_android.so")

private fun downloadPspAssets(context: Context) {
    val system = File(context.filesDir, "system").apply { mkdirs() }
    val target = File(system, "PPSSPP")
    if (File(target, ".installed").exists()) return
    val stage = File(system, "PPSSPP.part").apply { deleteRecursively(); mkdirs() }
    val backup = File(system, "PPSSPP.previous")
    val connection = URL("https://buildbot.libretro.com/assets/system/PPSSPP.zip").openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 15000; connection.readTimeout = 30000
    try {
        check(connection.responseCode == 200) { "PPSSPP assets HTTP ${connection.responseCode}" }
        var total = 0L; var entries = 0
        ZipInputStream(connection.inputStream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 4096) { "Too many PPSSPP asset files" }
                require(entry.name.startsWith("PPSSPP/")) { "Unexpected PPSSPP asset path" }
                val relative = entry.name.removePrefix("PPSSPP/")
                val destination = File(stage, relative)
                require(destination.canonicalPath == stage.canonicalPath || destination.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "Unsafe PPSSPP asset path" }
                if (entry.isDirectory) destination.mkdirs() else {
                    destination.parentFile!!.mkdirs()
                    destination.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer); if (count < 0) break
                            total += count; require(total <= 100_000_000) { "PPSSPP assets exceed 100 MB" }
                            out.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        check(File(stage, "flash0/font/ltn0.pgf").isFile && File(stage, "LICENSE.TXT").isFile) { "Incomplete PPSSPP assets" }
        File(stage, ".installed").writeText("libretro PPSSPP assets\n")
        backup.deleteRecursively()
        if (target.exists()) check(target.renameTo(backup)) { "Cannot stage previous PPSSPP assets" }
        if (!stage.renameTo(target)) {
            backup.renameTo(target)
            error("Cannot install PPSSPP assets")
        }
        backup.deleteRecursively()
    } finally { connection.disconnect(); stage.deleteRecursively() }
}

private fun downloadCore(context: Context, name: String): File {
    require(name in coreNames)
    val target = coreFile(context, name)
    target.parentFile!!.mkdirs()
    val archive = File(context.cacheDir, "core-download.zip")
    val staging = File(target.path + ".part")
    try {
        val connection = URL("https://buildbot.libretro.com/nightly/android/latest/${abi()}/${target.name}.zip").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 30000
        try {
            require(connection.responseCode == 200) { "Buildbot HTTP ${connection.responseCode}: $name / ${abi()}" }
            connection.inputStream.use { input -> archive.outputStream().use { out ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 100_000_000) { "Core download exceeds 100 MB" }; out.write(buffer, 0, count) }
            } }
        } finally { connection.disconnect() }
        var found = false
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == target.name && !entry.isDirectory) {
                    check(!found) { "Duplicate core in archive" }; found = true
                    staging.outputStream().use { out ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { val count = zip.read(buffer); if (count < 0) break; total += count; require(total <= 200_000_000) { "Core exceeds 200 MB" }; out.write(buffer, 0, count) }
                    }
                }
                zip.closeEntry()
            }
        }
        check(found) { "Archive does not contain ${target.name}" }
        val header = ByteArray(4); staging.inputStream().use { check(it.read(header) == 4) }
        check(header.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) { "Downloaded core is not ELF" }
        if (name == "ppsspp") downloadPspAssets(context)
        java.nio.file.Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    } finally { staging.delete(); archive.delete() }
}

private fun importRom(context: Context, uri: Uri): File {
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: error("No ROM filename")
    require(name.substringAfterLast('.').lowercase() in listOf("exe", "ps-exe", "bin", "iso", "img", "chd", "pbp", "elf", "prx", "cso", "isz")) { "Choose single-file PS1, PSP or PS2 content (.exe, .elf, .pbp, .iso, .chd, .cso). Multi-file CUE import comes later." }
    val temp = File.createTempFile("rom-", ".part", context.cacheDir)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { out ->
            val buffer = ByteArray(65536); var total = 0L
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 2_000_000_000L) { "ROM exceeds 2 GB" }; digest.update(buffer, 0, count); out.write(buffer, 0, count) }
        } } ?: error("Cannot read ROM")
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "roms/$hash").apply { mkdirs() }
        val target = File(dir, name.substringAfterLast('/').substringAfterLast('\\'))
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    } finally { temp.delete() }
}

class RetroSession(private val context: Context, private val core: File, private val rom: File,
    private val status: (String) -> Unit, private val ended: (String?) -> Unit) {
    @Volatile private var alive = true
    @Volatile var readySurface = false
    @Volatile private var sampleRate = 0
    @Volatile private var paused = false
    private val commands = ConcurrentLinkedQueue<String>()
    private val main = Handler(Looper.getMainLooper())
    fun action(command: String) { commands.add(command) }
    fun close() { alive = false }
    fun start() {
        Thread({
            var failure: String? = null
            try {
                while (alive && !readySurface) Thread.sleep(10)
                if (!alive) return@Thread
                val coreHash = MessageDigest.getInstance("SHA-256").digest(core.readBytes()).take(12).joinToString("") { "%02x".format(it) }
                val saves = File(context.filesDir, "saves/$coreHash/${rom.parentFile!!.name}")
                val info = Json.parseToJsonElement(RetroNative.command("start", buildJsonObject {
                    put("core", core.path); put("rom", rom.path); put("system", File(context.filesDir, "system").path); put("saves", saves.path)
                }.toString())).jsonObject
                sampleRate = info.getValue("sampleRate").jsonPrimitive.int
                RetroInput.active = true; RetroInput.paused = false
                main.post { status("${info.getValue("name").jsonPrimitive.content} · ${info.getValue("fps")} Hz · $sampleRate Hz audio") }
                while (alive) {
                    while (true) {
                        val command = commands.poll() ?: break
                        try {
                            when (command) {
                                "pause", "resume" -> { paused = command == "pause"; RetroInput.paused = paused; RetroInput.clear(); RetroNative.command("pause", paused.toString()) }
                                else -> { val result = RetroNative.command(command); android.util.Log.i("WinportState", "$command $result"); main.post { status("$command: $result") } }
                            }
                        } catch (e: Exception) { main.post { status(e.message ?: "Operation failed") } }
                    }
                    RetroNative.command("surfaceSync")
                    if (readySurface && !paused) RetroNative.command("run") else Thread.sleep(10)
                }
            } catch (e: Exception) { failure = e.message ?: "Emulation failed" }
            finally {
                alive = false; RetroInput.active = false; RetroInput.clear()
                try { RetroNative.command("close") } catch (e: Exception) { failure = "SRAM save failed: ${e.message}" }
                main.post { ended(failure) }
            }
        }, "libretro-run").start()
        Thread({
            var track: AudioTrack? = null
            try {
                while (alive && sampleRate == 0) Thread.sleep(10)
                if (!alive) return@Thread
                val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unsupported audio sample rate: $sampleRate" }
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, 16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
                track.play()
                var stopped = false
                while (alive) {
                    if (paused || !readySurface) {
                        if (!stopped) { track.pause(); track.flush(); stopped = true }
                        Thread.sleep(10); continue
                    }
                    if (stopped) { track.play(); stopped = false }
                    val samples = RetroNative.audio()
                    if (samples.isEmpty()) Thread.sleep(3) else {
                        var offset = 0
                        while (alive && offset < samples.size) {
                            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                            check(written >= 0) { "AudioTrack write failed: $written" }; if (written == 0) break; offset += written
                        }
                    }
                }
            } catch (e: Exception) { main.post { status("Audio error: ${e.message}") } }
            finally { track?.release() }
        }, "libretro-audio").start()
    }
}

@Composable
fun RetroShell(exit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf(false) }
    var selectedCore by remember { mutableStateOf("pcsx_rearmed") }
    var installed by remember { mutableStateOf(coreFile(context, selectedCore).exists()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<Pair<File, File>?>(null) }
    var controls by remember { mutableStateOf(false) }
    var controlPage by remember { mutableStateOf<String?>(null) }
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(1000) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { val rom = withContext(Dispatchers.IO) { importRom(context, uri) }; running = coreFile(context, selectedCore) to rom }
            catch (e: Exception) { message = e.message }
            finally { busy = false }
        }
    }
    if (running != null) {
        val pair = running!!
        EmulationScreen(pair.first, pair.second) { error -> running = null; message = error }
        return
    }
    BackHandler { when { message != null -> message = null; controlPage != null -> controlPage = null; controls -> controls = false; settings -> settings = false; media -> media = false; else -> controls = true } }
    Box(Modifier.fillMaxSize().background(Night).onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU || event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) { controls = !controls; controlPage = null }; true
        } else false
    }) {
        AmbientBackground()
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 22.dp)) {
            if (settings) {
                PageTitle("PlayStation runtime", "SETTINGS / LIBRETRO", { settings = false })
                Row(horizontalArrangement = Arrangement.spacedBy(35.dp)) {
                    Column(Modifier.width(260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        coreNames.forEach { name -> MenuRow(coreLabel(name), if (name == selectedCore) "Selected" else "Downloadable core", Mark.Chip) { if (!busy) { selectedCore = name; installed = coreFile(context, name).exists() } } }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        Text(selectedCore, fontSize = 30.sp, fontWeight = FontWeight.Light)
                        Text("Android ${abi()} · software / GLES3 video · stereo PCM", color = Soft)
                        Text(when (selectedCore) {
                            "pcsx_rearmed" -> "PCSX ReARMed supports HLE BIOS: no Sony BIOS is needed for the test homebrew. Compatibility is limited."
                            "ppsspp" -> "PSP via OpenGL ES. Download includes PPSSPP's open-source assets and replacement fonts in system/PPSSPP; no Sony BIOS."
                            "play" -> "PS2 via Play! and OpenGL ES. Play! uses HLE, not a Sony BIOS. Compatibility and device performance vary."
                            else -> "SwanStation and Beetle require a compatible, legally obtained BIOS. No BIOS is bundled or downloaded. PS1 uses software video."
                        }, color = Soft)
                        ConsoleButton(if (busy) "Downloading…" else if (installed) "Download latest again" else "Download core", {
                            scope.launch { busy = true; try { withContext(Dispatchers.IO) { downloadCore(context, selectedCore) }; installed = true; message = "Core downloaded to app-private data. Ready to open a ROM." } catch (e: Exception) { message = "Download failed: ${e.message}" } finally { busy = false } }
                        }, primary = true, enabled = !busy)
                        Text("User-triggered download from buildbot.libretro.com/nightly/android/latest. Native cores run with this app's permissions. Nightlies are not pinned; states are isolated by core binary and ROM hash.", color = Soft, fontSize = 12.sp)
                        Text("BIOS / system directory: ${File(context.filesDir, "system")}", color = Ice, fontSize = 11.sp)
                        if (installed) ConsoleButton("Open ROM", { picker.launch(arrayOf("*/*")) }, enabled = !busy, mark = Mark.Play)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TabWord("Games", !media) { media = false }; Spacer(Modifier.width(22.dp)); TabWord("Media", media) { media = true }
                    Spacer(Modifier.weight(1f)); ConsoleIcon(Mark.Settings, "Core settings", { settings = true }); Spacer(Modifier.width(15.dp)); ConsoleIcon(Mark.User, "Control center", { controls = true }); Text(clock, fontSize = 20.sp, modifier = Modifier.padding(start = 25.dp))
                }
                Spacer(Modifier.height(25.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeTile("Open ROM", true, Mark.Library, onFocus = {}, onClick = { if (installed) picker.launch(arrayOf("*/*")) else settings = true })
                    HomeTile("Core settings", false, Mark.Chip, onFocus = {}, onClick = { settings = true })
                }
                Spacer(Modifier.height(35.dp))
                Text(if (media) "Your media. Your space." else "Play beyond the familiar.", fontSize = 36.sp, fontWeight = FontWeight.Light)
                Text(if (media) "No media apps added." else "Your own PlayStation games. An open-source runtime. No games or BIOS bundled.", color = Soft, modifier = Modifier.padding(vertical = 15.dp))
                if (!media) Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    ConsoleButton(if (installed) "Open ROM" else "Set up core", { if (installed) picker.launch(arrayOf("*/*")) else settings = true }, primary = true, enabled = !busy)
                    ConsoleButton("Core settings", { settings = true }, mark = Mark.Settings)
                }
                Spacer(Modifier.weight(1f))
                Text("WINPORT  /  LIBRETRO FRONTEND", color = Soft, fontSize = 10.sp, letterSpacing = 2.sp)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        androidx.compose.animation.AnimatedVisibility(controls, enter = androidx.compose.animation.slideInVertically { it }, exit = androidx.compose.animation.slideOutVertically { it }) {
            ControlCenter(controlPage, UiState(), close = { controls = false; controlPage = null }, home = { settings = false; controls = false; controlPage = null; media = false }, page = { controlPage = it }, exit = exit)
        }
        message?.let { ConsolePrompt("Winport", it, "Close", { message = null }, { message = null }) }
    }
}

@Composable
fun EmulationScreen(core: File, rom: File, exit: (String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var paused by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading core…") }
    val session = remember { RetroSession(context, core, rom, { status = it }, exit) }
    val owner = LocalLifecycleOwner.current
    fun pause() { paused = true; session.action("pause") }
    BackHandler { pause() }
    DisposableEffect(session) {
        session.start()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); session.close(); RetroNative.surface(null) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {}) {
        AndroidView(factory = { ctx -> SurfaceView(ctx).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {}
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { RetroNative.surface(holder.surface); session.readySurface = true }
                override fun surfaceDestroyed(holder: SurfaceHolder) { session.readySurface = false; RetroInput.clear(); RetroNative.surface(null) }
            })
        } }, modifier = if (core.name.startsWith("ppsspp_") || core.name.startsWith("play_")) Modifier.fillMaxSize() else Modifier.fillMaxHeight().aspectRatio(4f / 3f).align(Alignment.Center))
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { ConsoleButton("Pause", { pause() }, mark = Mark.More) }
        if (!paused) {
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PadButton("UP", 4); Row { PadButton("LEFT", 6); Spacer(Modifier.width(48.dp)); PadButton("RIGHT", 7) }; PadButton("DOWN", 5)
                Row { PadButton("L", 10); PadButton("L2", 12) }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PadButton("Y", 1); Row { PadButton("X", 9); Spacer(Modifier.width(48.dp)); PadButton("B", 0) }; PadButton("A", 8)
                Row { PadButton("R", 11); PadButton("R2", 13) }
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(15.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) { PadButton("SELECT", 2); PadButton("START", 3) }
        }
        if (paused) ModalShade({ paused = false; session.action("resume") }) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 620.dp).fillMaxWidth(.75f).background(Glass).padding(25.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paused", fontSize = 30.sp, fontWeight = FontWeight.Light)
                Text(status, color = Soft, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConsoleButton("Resume", { paused = false; session.action("resume") }, primary = true)
                    ConsoleButton("Save state", { session.action("save") })
                    ConsoleButton("Load state", { session.action("load") })
                }
                ConsoleButton("Verify state round trip", { session.action("verify") })
                ConsoleButton("Exit", { session.close() }, mark = Mark.Power)
            }
        }
    }
}

@Composable
private fun PadButton(label: String, id: Int) {
    Box(Modifier.padding(3.dp).size(if (label.length > 4) 64.dp else 48.dp).background(Color(0x5565758A), CircleShape)
        .pointerInput(id) { detectTapGestures(onPress = { RetroInput.button(1, id, true); try { tryAwaitRelease() } finally { RetroInput.button(1, id, false) } }) }, contentAlignment = Alignment.Center) {
        Text(label, color = Color.White.copy(alpha = .8f), fontSize = 10.sp)
    }
}
