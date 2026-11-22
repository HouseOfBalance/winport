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
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class EmuActivity : ComponentActivity() {
    private var exiting = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    EmulationScreen(core, rom, requestExit = { leave(false) }) { error ->
                        if (error != null) setResult(RESULT_CANCELED, android.content.Intent().putExtra("error",error))
                        leave(true)
                    }
                }
            }
        }
    }
    private fun leave(closed: Boolean) {
        if (closed) {
            android.util.Log.i("WinportExit", "Native close completed pid=${Process.myPid()}")
            if (!isFinishing) finish()
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 150)
            return
        }
        if (exiting) return
        exiting = true
        android.util.Log.i("WinportExit", "Returning home; nativeClosed=$closed pid=${Process.myPid()}")
        // The UI never joins a native thread. Only this private process is terminated.
        Handler(Looper.getMainLooper()).postDelayed({
            android.util.Log.i("WinportExit", "Exit deadline; ending isolated process pid=${Process.myPid()}")
            finish()
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 150)
        }, if (closed) 0 else 2000)
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
        super.onDestroy()
        if (!isChangingConfigurations) Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 200)
    }
}
