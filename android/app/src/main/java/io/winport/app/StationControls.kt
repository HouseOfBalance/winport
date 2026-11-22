package io.winport.app

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ControlCapture { var sink: ((KeyEvent)->Boolean)? = null }

@Composable
fun ControlsScreen(back: () -> Unit) {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("controls",android.content.Context.MODE_PRIVATE) }
    var waiting by remember { mutableStateOf<Int?>(null) }
    var last by remember { mutableStateOf("Chọn nút bên dưới rồi bấm nút trên tay cầm") }
    var deadzone by remember { mutableFloatStateOf(prefs.getFloat("deadzone",.15f)) }
    var opacity by remember { mutableFloatStateOf(prefs.getFloat("opacity",.6f)) }
    var size by remember { mutableFloatStateOf(prefs.getFloat("size",1f)) }
    var hide by remember { mutableStateOf(prefs.getBoolean("hide",true)) }
    DisposableEffect(waiting) {
        ControlCapture.sink={ event ->
            if(waiting!=null && event.keyCode!=KeyEvent.KEYCODE_BACK) {
                if(event.action==KeyEvent.ACTION_DOWN) { val id=waiting!!;prefs.edit().putInt("key_${event.keyCode}",id).apply();last="Đã gán ${KeyEvent.keyCodeToString(event.keyCode)} → nút $id";waiting=null }
                true
            } else false
        }
        onDispose { ControlCapture.sink=null }
    }
    Column {
        PageTitle("Tay cầm và điều khiển","BLUETOOTH / USB / BÀN PHÍM ẢO",back)
        Row(horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(waiting==null) last else "Đang chờ nút cho mã $waiting…",color=Ice)
                listOf(listOf("△" to RetroButtons.TRIANGLE,"○" to RetroButtons.CIRCLE,"×" to RetroButtons.CROSS,"□" to RetroButtons.SQUARE),listOf("L1" to 10,"R1" to 11,"L2" to 12,"R2" to 13),listOf("Lên" to 4,"Xuống" to 5,"Trái" to 6,"Phải" to 7),listOf("Chọn" to 2,"Bắt đầu" to 3,"L3" to 14,"R3" to 15)).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { row.forEach { (label,id)->ConsoleButton(label,{waiting=id},primary=waiting==id) } } }
                ConsoleButton("Khôi phục mặc định",{prefs.edit().clear().apply();deadzone=.15f;opacity=.6f;size=1f;hide=true;waiting=null;last="Đã khôi phục cấu hình mặc định"})
                Text("Ghép tay cầm trong Cài đặt Bluetooth của Android. Hai cần analog dùng các trục X/Y và Z/RZ. Vị trí nút hình học: △ trên, ○ phải, × dưới, □ trái.",color=Soft,fontSize=13.sp)
            }
            Column(Modifier.width(340.dp).verticalScroll(rememberScrollState())) {
                Text("Vùng chết analog · ${(deadzone*100).toInt()}%")
                Slider(deadzone,{deadzone=it;prefs.edit().putFloat("deadzone",it).apply()},valueRange=0f..0.5f)
                Text("Độ mờ phím ảo · ${(opacity*100).toInt()}%")
                Slider(opacity,{opacity=it;prefs.edit().putFloat("opacity",it).apply()},valueRange=.15f..1f)
                Text("Kích thước · ${(size*100).toInt()}%")
                Slider(size,{size=it;prefs.edit().putFloat("size",it).apply()},valueRange=.7f..1.4f)
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Ẩn phím ảo khi có tay cầm",Modifier.weight(1f));Switch(hide,{hide=it;prefs.edit().putBoolean("hide",it).apply()}) }
                Text("Cấu hình có hiệu lực ở lần mở trò chơi tiếp theo.",color=Soft,fontSize=12.sp)
            }
        }
    }
}

@Composable
fun AnalogPad(axis: Int, modifier: Modifier) {
    val prefs=LocalContext.current.getSharedPreferences("controls",android.content.Context.MODE_PRIVATE)
    var position by remember { mutableStateOf(Offset.Zero) }
    fun send(value: Offset) { position=value;RetroNative.input(0,axis,(value.x*32767).toInt().toShort());RetroNative.input(0,axis+1,(value.y*32767).toInt().toShort()) }
    Canvas(modifier.size((95*prefs.getFloat("size",1f)).dp).pointerInput(axis) {
        detectDragGestures(onDragStart={ val x=(it.x-size.width/2)/(size.width/2);val y=(it.y-size.height/2)/(size.height/2);send(Offset(x.coerceIn(-1f,1f),y.coerceIn(-1f,1f))) },onDragEnd={send(Offset.Zero)},onDragCancel={send(Offset.Zero)}) { change,_ -> change.consume();send(Offset(((change.position.x-size.width/2)/(size.width/2)).coerceIn(-1f,1f),((change.position.y-size.height/2)/(size.height/2)).coerceIn(-1f,1f))) }
    }) {
        val alpha=prefs.getFloat("opacity",.6f)
        drawCircle(Color.White.copy(alpha=alpha*.25f));drawCircle(Color.White.copy(alpha=alpha),style=Stroke(2f))
        drawCircle(Color.White.copy(alpha=alpha*.7f),size.minDimension*.18f,center+Offset(position.x,position.y)*(size.minDimension*.3f))
    }
}
