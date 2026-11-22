package io.winport.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

private val systems = listOf("PS1","PSP","PS2","PS3")
private fun preferences(lib: JsonObject) = lib["prefs"] as? JsonObject ?: buildJsonObject {}
private fun md5(file: File): String {
    val digest=MessageDigest.getInstance("MD5")
    file.inputStream().use { input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
    return digest.digest().joinToString("") {"%02x".format(it)}
}

@Composable
fun StationImage(path: String, modifier: Modifier, revision: String = "") {
    val bitmap by produceState<ImageBitmap?>(null,path,revision) {
        value=withContext(Dispatchers.IO) {
            if(path.isBlank()) null else runCatching {
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(path,options)
                check(options.outWidth>0 && options.outHeight>0)
                options.inSampleSize=1
                while(options.outWidth/options.inSampleSize>1920 || options.outHeight/options.inSampleSize>1080) options.inSampleSize*=2
                options.inJustDecodeBounds=false
                BitmapFactory.decodeFile(path,options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF253655),Night)))) {
        bitmap?.let { Image(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
    }
}

@Composable
fun StationShell(exit: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val owner=LocalLifecycleOwner.current
    var library by remember { mutableStateOf(buildJsonObject {}) }
    var page by remember { mutableStateOf("home") }
    var selected by remember { mutableStateOf("") }
    var system by remember { mutableStateOf("PS1") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var staging by remember { mutableStateOf<StageProgress?>(null) }
    var pending by remember { mutableStateOf<JsonObject?>(null) }
    var options by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf("") }
    var emuPending by remember { mutableStateOf(false) }
    var emuPid by remember { mutableIntStateOf(0) }
    var heartbeatAt by remember { mutableLongStateOf(0) }
    val games=(library["games"] as? JsonArray)?.map { it.jsonObject }?.filter {it.str("system")!="PS4"} ?: emptyList()
    val game=games.find { it.str("uri")==selected } ?: games.firstOrNull()
    fun job(block: suspend () -> String?) {
        if(busy) return
        scope.launch { busy=true; try { message=withContext(Dispatchers.IO) { block() }; library=withContext(Dispatchers.IO) { station(context,"load").jsonObject } } catch(e: Exception) { message="Không thể thực hiện: ${e.message}" } finally { busy=false;staging=null } }
    }
    LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) { station(context,"load").jsonObject } }.onSuccess {library=it}.onFailure {message="Không đọc được thư viện: ${it.message}"}; while(true) { clock=SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date());delay(1000) } }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) scope.launch { runCatching { withContext(Dispatchers.IO) { station(context,"load").jsonObject } }.onSuccess { library=it } } }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val receiver=object: android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                heartbeatAt=android.os.SystemClock.elapsedRealtime()
                emuPid=intent.getIntExtra("pid",0)
                if(intent.getBooleanExtra("stalled",false)) message="Core bị treo… Không phản hồi quá 5 giây. Quay lại phiên giả lập để chờ thêm hoặc buộc thoát. Dữ liệu chưa lưu có thể mất."
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context,receiver,android.content.IntentFilter("io.winport.app.EMU_HEARTBEAT"),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(emuPending) {
        while(emuPending) {
            delay(1000)
            if(android.os.SystemClock.elapsedRealtime()-heartbeatAt>5000) message="Core bị treo… Phiên giả lập không gửi nhịp phản hồi quá 5 giây. Buộc thoát có thể mất dữ liệu chưa lưu."
        }
    }
    val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri!=null) job {
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        station(context,"folder",obj("uri" to uri.toString()))
        val result=scan(context,uri)
        "Đã nhận diện ${result.first} trò chơi.\n${result.second.take(8).joinToString("\n")}"
    } }
    val manual=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) job {
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "Trò chơi" } ?: "Trò chơi"
        val detected=runCatching { detect(context,Doc(uri,name,false)) }.getOrNull()
        check(detected?.str("system")!="PS4") {"Định dạng trò chơi chưa được hỗ trợ"}
        val key=runCatching { documentKey(context,uri) }.getOrDefault("")
        withContext(Dispatchers.Main) { pending=buildJsonObject { put("document_key",key);put("uri",uri.toString());put("title",detected?.str("title") ?: name.substringBeforeLast('.'));put("system",detected?.str("system","PS1") ?: "PS1");put("serial",detected?.str("serial") ?: "") } }
        null
    } }
    val artPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> val g=game; if(uri!=null && g!=null) job { customArt(context,uri,g);"Đã lưu ảnh tùy chỉnh" } }
    val biosPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) job {
        val sourceName=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: error("Không đọc được tên tệp")
        val name=if(system=="PS1") sourceName.lowercase(Locale.ROOT) else sourceName
        check('/' !in name && '\\' !in name && name !in listOf(".","..")) { "Tên tệp không hợp lệ" }
        val dir=File(context.filesDir,if(system=="PS3") "system/$system" else "system").apply { mkdirs() }
        val temp=File.createTempFile("firmware-",".part",context.cacheDir)
        try {
            val digest=MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { out -> val buffer=ByteArray(65536);var total=0L;while(true) { val n=input.read(buffer);if(n<0)break;total+=n;check(total<=512_000_000) {"Tệp vượt quá 512 MB"};digest.update(buffer,0,n);out.write(buffer,0,n) } } } ?: error("Không đọc được tệp")
            val md5=digest.digest().joinToString("") { "%02x".format(it) }
            val status=station(context,"bios",obj("name" to name,"md5" to md5)).jsonObject
            check(status["known"]?.jsonPrimitive?.boolean!=true || status["valid"]?.jsonPrimitive?.boolean==true) { "MD5 không khớp; không cài tệp. MD5 nhận: $md5" }
            java.nio.file.Files.move(temp.toPath(),File(dir,name.lowercase().let { if(system=="PS3") "PS3UPDAT.PUP" else it }).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            "Đã nhập $name\nMD5: $md5\n" + if(status["valid"]?.jsonPrimitive?.boolean==true) "Khớp hash đã biết" else "Chưa có hash đối chiếu: không xác nhận tính hợp lệ. Emulator ngoài có thể yêu cầu bạn nhập lại tệp."
        } finally { temp.delete() }
    } }
    val emulator=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        emuPending=false
        result.data?.getStringExtra("error")?.let { message="Phiên giả lập kết thúc: $it" }
        if(result.resultCode!=android.app.Activity.RESULT_OK && result.data?.getStringExtra("error")==null) message="Core gặp lỗi hoặc phiên đã bị buộc thoát. Dữ liệu chưa lưu có thể mất."
    }
    fun launch(g: JsonObject) {
        val sys=g.str("system")
        if(sys=="PS3") {
            try { launchExternal(context,g,preferences(library).str("external_$sys")) } catch(e:Exception) { message="Chưa chọn emulator PS3 trong Cài đặt: ${e.message}" }
        } else job {
            val core=when(sys) {"PSP"->"ppsspp";"PS2"->"play";else->preferences(library).str("ps1core","pcsx_rearmed")}
            check(coreFile(context,core).isFile) { "Chưa tải lõi $core. Mở Cài đặt → Lõi giả lập để tải" }
            verifyCore(context,coreFile(context,core),core)
            if(core in listOf("swanstation","mednafen_psx")) check(listOf("scph5500.bin","scph5501.bin","scph5502.bin").any {File(context.filesDir,"system/$it").isFile}) {"Thiếu BIOS PS1. Mở Cài đặt → BIOS và firmware để nhập bản trích xuất của bạn"}
            withContext(Dispatchers.Main) { staging=StageProgress("Đang đọc thư mục nguồn…",0,0,0,0) }
            val rom=stageGame(context,g) { update -> scope.launch(Dispatchers.Main) { staging=update } }
            withContext(Dispatchers.Main) { heartbeatAt=android.os.SystemClock.elapsedRealtime();emuPending=true;emulator.launch(Intent(context,EmuActivity::class.java).putExtra("core",coreFile(context,core).path).putExtra("rom",rom.path)) }
            null
        }
    }
    BackHandler { when { staging!=null -> Unit;message!=null->message=null;pending!=null->pending=null;options->options=false;page!="home"->page="home";else->page="control" } }
    Box(Modifier.fillMaxSize().background(Night).onPreviewKeyEvent { event -> if(event.nativeKeyEvent.keyCode in listOf(android.view.KeyEvent.KEYCODE_MENU,android.view.KeyEvent.KEYCODE_BUTTON_START)) { if(event.nativeKeyEvent.action==android.view.KeyEvent.ACTION_DOWN)page="control";true } else false }) {
        AmbientBackground()
        if(page=="home" && game!=null) {
            StationImage(game.str("art"),Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night.copy(alpha=.45f),Night.copy(alpha=.6f),Night))))
        }
        Column(Modifier.fillMaxSize().padding(horizontal=42.dp,vertical=18.dp)) {
            if(page=="home") {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Trò chơi",fontSize=25.sp,fontWeight=FontWeight.Light,modifier=Modifier.weight(1f))
                    ConsoleButton("Quét thư mục",{ folderPicker.launch(null) },enabled=!busy,mark=Mark.Library)
                    ConsoleButton("Thêm tệp",{ manual.launch(arrayOf("*/*")) },enabled=!busy,mark=Mark.Add)
                    ConsoleButton("Cài đặt",{page="settings"},mark=Mark.Settings)
                    Text(clock,color=Soft)
                }
                Spacer(Modifier.height(24.dp))
                if(games.isEmpty()) {
                    Spacer(Modifier.height(80.dp));Text("Thư viện trống",fontSize=46.sp,fontWeight=FontWeight.Light)
                    Text("Chọn thư mục trò chơi của bạn để bắt đầu. Không có trò chơi mẫu được cài sẵn.",color=Soft)
                } else {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(18.dp),contentPadding=PaddingValues(8.dp)) {
                        items(games,key={it.str("uri")}) { g ->
                            var focused by remember { mutableStateOf(false) }
                            val scale by animateFloatAsState(if(focused) 1.07f else 1f,spring(),label="tile")
                            Column(Modifier.width(130.dp).scale(scale).clip(RoundedCornerShape(12.dp)).border(if(focused||g==game) 2.dp else 0.dp,Color.White,RoundedCornerShape(12.dp)).onFocusChanged { focused=it.isFocused;if(it.isFocused)selected=g.str("uri") }.clickable { selected=g.str("uri") }.padding(6.dp)) {
                                StationImage(g.str("art"),Modifier.fillMaxWidth().height(100.dp))
                                Text(g.str("system"),color=Ice,fontSize=12.sp)
                                Text(g.str("title"),maxLines=1,fontSize=12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(game?.str("title") ?: "",fontSize=36.sp,fontWeight=FontWeight.Light,maxLines=1)
                    Text("${game?.str("system")}  ·  ${game?.str("serial").orEmpty()}",color=Soft)
                    Row(Modifier.padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ConsoleButton("Chơi",{game?.let(::launch)},primary=true,mark=Mark.Play,enabled=!busy&&!emuPending)
                        ConsoleButton("Tùy chọn",{options=true},mark=Mark.More)
                        ConsoleButton("Trung tâm điều khiển",{page="control"},mark=Mark.Home)
                    }
                }
            } else if(page=="settings") {
                PageTitle("Cài đặt","HỆ THỐNG WINPORT",{page="home"})
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    MenuRow("Thư viện và thư mục","Quét lại, quản lý quyền truy cập SAF",Mark.Library) {page="folders"}
                    MenuRow("Lõi giả lập","PS1 · PSP · PS2, chỉ tải khi bạn yêu cầu",Mark.Chip) {page="cores"}
                    MenuRow("BIOS và firmware","Hướng dẫn, nhập tệp, đối chiếu MD5",Mark.Info) {page="bios"}
                    MenuRow("Tay cầm và điều khiển","Gán nút, vùng chết, bàn phím ảo",Mark.Tune) {page="controls"}
                    MenuRow("Emulator ngoài","Chọn ứng dụng cho PS3",Mark.Box) {page="external"}
                }
            } else if(page=="bios") {
                PageTitle("BIOS và firmware","CHỈ DÙNG BẢN SAO HỢP PHÁP CỦA BẠN",{page="settings"})
                Row(horizontalArrangement=Arrangement.spacedBy(25.dp)) {
                    Column(Modifier.width(160.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { systems.forEach { s -> ConsoleButton(s,{system=s},primary=system==s) } }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("$system · Phần mềm khởi động máy",fontSize=27.sp,fontWeight=FontWeight.Light)
                        Text("BIOS/firmware là phần mềm gốc giúp máy khởi động. Chỉ dùng bản do bạn tự trích xuất từ máy của mình (hoặc firmware được hãng cho phép cung cấp). Winport không tải BIOS, không kèm khóa hay trò chơi.",color=Soft)
                        Text(when(system) {
                            "PS1"->"PCSX ReARMed dùng HLE khi thiếu BIOS. SwanStation/Beetle cần scph5500.bin (Nhật), scph5501.bin (Mỹ), scph5502.bin (Âu) trong thư mục system. HLE mô phỏng chức năng BIOS, không phải tệp BIOS thật."
                            "PSP"->"PPSSPP dùng HLE, không cần BIOS Sony. Tải lõi sẽ tải bộ tài nguyên mã nguồn mở PPSSPP và phông thay thế."
                            "PS2"->"Play! dùng HLE nên không cần BIOS PS2. Những lõi khác như PCSX2 có thể cần ROM0/BIOS .bin do bạn tự trích xuất; tên/vùng/phiên bản tùy lõi. Winport hiện không chạy PCSX2."
                            "PS3"->"Cần emulator ngoài tương thích và firmware PS3UPDAT.PUP. Tham khảo trang hỗ trợ chính thức Sony. Tệp nhập ở đây không tự cài vào emulator ngoài."
                            else->"Hãy nhập firmware hợp pháp theo hướng dẫn của emulator."
                        })
                        if(system=="PS1") listOf("scph5500.bin","scph5501.bin","scph5502.bin").forEach { name ->
                            val file=File(context.filesDir,"system/$name")
                            var result by remember(file.lastModified()) { mutableStateOf("Đang kiểm tra…") }
                            LaunchedEffect(file.lastModified()) { result=withContext(Dispatchers.IO) { if(!file.exists()) "Thiếu · HLE với PCSX ReARMed" else { val hash=md5(file); val valid=station(context,"bios",obj("name" to name,"md5" to hash)).jsonObject["valid"]?.jsonPrimitive?.boolean==true; if(valid) "MD5 khớp" else "MD5 không khớp · không nên dùng" } } }
                            Text("$name · $result",color=Ice,fontSize=13.sp)
                        } else Text(if(system=="PSP"||system=="PS2") "Trạng thái: dùng HLE" else "Trạng thái: quản lý firmware trong emulator ngoài",color=Ice)
                        ConsoleButton("Nhập tệp BIOS / firmware",{biosPicker.launch(arrayOf("*/*"))},enabled=!busy)
                        if(system=="PS3") ConsoleButton("Trang firmware PS3 chính thức",{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.playstation.com/support/hardware/ps3/system-software/")))})
                        Text("Chỉ các hash PS1 đã biết được xác nhận. Tệp khác được ghi rõ là chưa xác minh.",fontSize=12.sp,color=Soft)
                    }
                }
            } else if(page=="controls") {
                ControlsScreen {page="settings"}
            } else if(page=="folders") {
                PageTitle("Thư mục trò chơi","QUYỀN ĐỌC ĐƯỢC GIỮ BẰNG SAF",{page="settings"})
                ConsoleButton("Chọn thư mục",{folderPicker.launch(null)},enabled=!busy)
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    (library["folders"] as? JsonArray)?.forEach { item -> val uri=item.jsonPrimitive.content
                        Text(Uri.decode(uri),fontSize=13.sp,color=Soft)
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            ConsoleButton("Quét lại",{job {val result=scan(context,Uri.parse(uri));"Đã nhận diện ${result.first} mục.\n${result.second.take(8).joinToString("\n")}"}},enabled=!busy)
                            ConsoleButton("Quên thư mục",{job { station(context,"forgetFolder",obj("uri" to uri));runCatching {context.contentResolver.releasePersistableUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION)};"Đã quên thư mục. Trò chơi vẫn giữ trong thư viện; bạn có thể xóa ở Tùy chọn." }},enabled=!busy)
                        }
                    }
                }
            } else if(page=="cores") {
                PageTitle("Lõi giả lập","TẢI VÀO BỘ NHỚ RIÊNG CỦA ỨNG DỤNG",{page="settings"})
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    listOf("pcsx_rearmed","swanstation","mednafen_psx","ppsspp","play").forEach { core ->
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("${when(core) {"ppsspp"->"PSP";"play"->"PS2";else->"PS1"}} · $core",Modifier.width(230.dp))
                            ConsoleButton(if(coreFile(context,core).exists()) "Tải lại" else "Tải lõi",{ job {downloadCore(context,core);"Đã tải $core"} },enabled=!busy)
                            if(core !in listOf("ppsspp","play")) ConsoleButton(if(preferences(library).str("ps1core","pcsx_rearmed")==core) "Đang dùng cho PS1" else "Chọn cho PS1",{job {station(context,"prefs",JsonObject(preferences(library)+mapOf("ps1core" to JsonPrimitive(core))));null}},enabled=!busy)
                        }
                    }
                    Text("Lõi được ghim SHA-256. Nếu nightly thay đổi, ứng dụng từ chối tải cho đến khi cập nhật manifest. Lõi chạy mã native với quyền ứng dụng. Tài nguyên PPSSPP không phải BIOS Sony.",color=Soft)
                }
            } else if(page=="external") {
                PageTitle("Emulator ngoài","KHẢ NĂNG CHẠY PHỤ THUỘC ỨNG DỤNG BÊN NGOÀI",{page="settings"})
                val sys="PS3"
                Text("$sys · ${preferences(library).str("external_$sys","Chưa chọn ứng dụng")}",color=Ice)
                val apps=remember { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).filter {it.activityInfo.packageName!=context.packageName}.distinctBy {it.activityInfo.packageName}.sortedBy {it.loadLabel(context.packageManager).toString()} }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConsoleButton("Không cấu hình",{job {station(context,"prefs",JsonObject(preferences(library)-"external_$sys"));null}},enabled=!busy)
                    apps.forEach { app -> MenuRow(app.loadLabel(context.packageManager).toString(),app.activityInfo.packageName,Mark.Box) {job {station(context,"prefs",JsonObject(preferences(library)+mapOf("external_$sys" to JsonPrimitive(app.activityInfo.packageName))));"Đã chọn ứng dụng cho $sys"}} }
                }
            } else {
                PageTitle("Trung tâm điều khiển","WINPORT",{page="home"})
                Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    ConsoleButton("Trang chủ",{page="home"},mark=Mark.Home)
                    ConsoleButton("Cài đặt",{page="settings"},mark=Mark.Settings)
                    ConsoleButton("Thoát ứng dụng",exit,mark=Mark.Power)
                }
            }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        staging?.let { status -> ModalShade({}) {
            Column(Modifier.align(Alignment.Center).widthIn(max=760.dp).background(Glass).padding(28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("Đang chuẩn bị thư mục trò chơi",fontSize=26.sp)
                Text(status.file,color=Soft)
                Text("${status.completed}/${status.count} tệp · ${status.bytes/1048576} / ${status.total/1048576} MB",color=Soft)
                LinearProgressIndicator(progress={ if(status.total>0) (status.bytes.toDouble()/status.total).toFloat() else 0f },modifier=Modifier.fillMaxWidth())
                Text("Sao chép và kiểm tra lại dữ liệu nguồn mỗi lần chơi. Chỉ mở lõi khi đã hoàn tất.",color=Soft)
            }
        } }
        if(options && game!=null) ModalShade({options=false}) {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(350.dp).background(Glass).padding(25.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(game.str("title"),fontSize=24.sp)
                ConsoleButton("Sửa tên / hệ máy",{pending=game;options=false})
                ConsoleButton("Tìm ảnh bìa",{val g=game;options=false;job {fetchArt(context,g);"Đã tải ảnh bìa từ libretro-thumbnails"}},enabled=!busy)
                ConsoleButton("Chọn ảnh riêng",{options=false;artPicker.launch(arrayOf("image/*"))})
                ConsoleButton("Xóa khỏi thư viện",{val g=game;options=false;job {station(context,"remove",obj("uri" to g.str("uri")));null}})
            }
        }
        pending?.let { data -> ModalShade({pending=null}) {
            var title by remember(data) {mutableStateOf(data.str("title"))};var sys by remember(data) {mutableStateOf(data.str("system","PS1"))}
            Column(Modifier.align(Alignment.Center).width(680.dp).background(Glass).padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Thông tin trò chơi",fontSize=30.sp,fontWeight=FontWeight.Light)
                OutlinedTextField(title,{title=it},label={Text("Tên trò chơi")},modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { systems.forEach { s->ConsoleButton(s,{sys=s},primary=sys==s) } }
                Text("Nếu định dạng nén/ISO chưa rõ, hãy chọn đúng hệ máy. Thư mục PS3 được thêm bằng Quét thư mục.",color=Soft,fontSize=12.sp)
                ConsoleButton("Lưu",{val g=JsonObject(data+mapOf("title" to JsonPrimitive(title),"system" to JsonPrimitive(sys)));pending=null;job {station(context,"upsert",g);null}},enabled=title.isNotBlank())
            }
        } }
        message?.let { text -> ModalShade({message=null}) { Column(Modifier.align(Alignment.Center).widthIn(max=760.dp).fillMaxHeight(.8f).background(Glass).padding(26.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(15.dp)) { Text("Thông báo",fontSize=28.sp);Text(text,color=Soft)
            if(text.contains("Core bị treo")) ConsoleButton("Buộc thoát phiên",{
                val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                if(manager.runningAppProcesses?.any {it.pid==emuPid && it.uid==android.os.Process.myUid() && it.processName=="${context.packageName}:emu"}==true) android.os.Process.killProcess(emuPid)
                emuPending=false;message=null
            })
            ConsoleButton("Đóng",{message=null},primary=true) } } }
    }
}
