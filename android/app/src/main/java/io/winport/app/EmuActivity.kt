package io.winport.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class EmuActivity : ComponentActivity() {
    private var exiting = false
    private var failure by mutableStateOf<String?>(null)
    private var stalled by mutableStateOf(false)
    private val monitor=Handler(Looper.getMainLooper())
    private val heartbeat=object: Runnable {
        override fun run() {
            val age=android.os.SystemClock.elapsedRealtime()-EmuProgress.changed
            stalled=EmuProgress.phase!="idle" && age>5000
            sendBroadcast(android.content.Intent("io.winport.app.EMU_HEARTBEAT").setPackage(packageName).putExtra("pid",Process.myPid()).putExtra("phase",EmuProgress.phase).putExtra("stalled",stalled).putExtra("age",age))
            if(stalled) android.util.Log.e("WinportHeartbeat","Core bị treo phase=${EmuProgress.phase} age=$age")
            monitor.postDelayed(this,1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmuProgress.enter("starting")
        EmuProgress.teardownReady={ leave(false) }
        monitor.post(heartbeat)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val core = intent.getStringExtra("core")?.let(::File)
        val rom = intent.getStringExtra("rom")?.let(::File)
        if (core == null || rom == null || !core.isFile || !rom.isFile) { leave(true); return }
        RetroInput.configure(getSharedPreferences("controls", MODE_PRIVATE))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, background = Night, surface = Glass, onSurface = Color.White)) {
                Surface(color = Night, contentColor = Color.White) {
                    EmulationScreen(core, rom, requestExit = {}) { error ->
                        EmuProgress.enter("idle")
                        if (error != null) failure="Không khởi chạy được: $error" else leave(true)
                    }
                    if(stalled || failure!=null) AlertDialog(onDismissRequest={},title={Text(if(stalled) "Core bị treo…" else "Không thể chạy trò chơi")},text={Text(failure ?: "Lõi không phản hồi quá 5 giây. Dữ liệu chưa được xác nhận lưu có thể mất khi buộc thoát.")},confirmButton={TextButton({finish();Process.killProcess(Process.myPid())}){Text("Buộc thoát phiên")}},dismissButton={TextButton({failure=null;stalled=false}){Text("Chờ thêm")}})
                }
            }
        }
    }
    private fun leave(closed: Boolean) {
        if (closed) {
            setResult(RESULT_OK)
            android.util.Log.i("WinportExit", "Native close completed pid=${Process.myPid()}")
            if (!isFinishing) finish()
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 150)
            return
        }
        if (exiting) return
        exiting = true
        android.util.Log.i("WinportExit", "Returning home; nativeClosed=$closed pid=${Process.myPid()}")
        Thread({
            Thread.sleep(2000)
            android.util.Log.i("WinportExit", "Exit deadline; ending isolated process pid=${Process.myPid()}")
            runOnUiThread {setResult(RESULT_OK);finish()}
            Thread.sleep(150)
            Process.killProcess(Process.myPid())
        },"emu-durable-exit").start()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RetroInput.active && event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE)) return super.dispatchKeyEvent(KeyEvent(event.downTime,event.eventTime,event.action,KeyEvent.KEYCODE_BACK,event.repeatCount))
        if (RetroInput.key(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) return super.dispatchKeyEvent(KeyEvent(event.downTime,event.eventTime,event.action,KeyEvent.KEYCODE_DPAD_CENTER,event.repeatCount))
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) return super.dispatchKeyEvent(KeyEvent(event.downTime,event.eventTime,event.action,KeyEvent.KEYCODE_BACK,event.repeatCount))
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = RetroInput.motion(event) || super.dispatchGenericMotionEvent(event)
    override fun onDestroy() {
        monitor.removeCallbacks(heartbeat)
        EmuProgress.teardownReady=null
        super.onDestroy()
    }
    override fun onPause() { EmuProgress.checkpoint?.invoke();super.onPause() }
    override fun onStop() { EmuProgress.checkpoint?.invoke();super.onStop() }
}
