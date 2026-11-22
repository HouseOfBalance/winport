package io.winport.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

val Night = Color(0xFF080D18)
val Glass = Color(0xFF171F30)
val Soft = Color(0xFFADB8CB)
val Ice = Color(0xFFA9CAEE)

enum class Mark { Search, Settings, User, Library, Add, Home, Bell, Download, Sound, Network, Access, Power, Back, More, Box, Tune, Chip, Info, Play, Close, Image, Sort }

@Composable
fun Glyph(mark: Mark, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, p(x, y), p(a, b), 1.4f * u, StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r * u, p(x, y), style = Stroke(1.4f * u))
        fun path(vararg xy: Float) {
            val shape = Path().apply { moveTo(xy[0] * u, xy[1] * u); for (i in 2 until xy.size step 2) lineTo(xy[i] * u, xy[i + 1] * u) }
            drawPath(shape, color, style = Stroke(1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        when (mark) {
            Mark.Search -> { circle(10f, 10f, 6f); line(15f, 15f, 21f, 21f) }
            Mark.Settings -> { circle(12f, 12f, 4f); repeat(8) { val a = it * Math.PI / 4; line((12 + cos(a) * 8).toFloat(), (12 + sin(a) * 8).toFloat(), (12 + cos(a) * 10).toFloat(), (12 + sin(a) * 10).toFloat()) }; circle(12f, 12f, 8f) }
            Mark.User -> { circle(12f, 8f, 3.5f); drawArc(color, 180f, 180f, false, p(4f, 14f), Size(16 * u, 13 * u), style = Stroke(1.4f * u)) }
            Mark.Library -> { path(3f, 5f, 3f, 21f, 18f, 21f); path(7f, 2f, 21f, 2f, 21f, 17f, 7f, 17f, 7f, 2f); line(11f, 7f, 17f, 7f); line(11f, 11f, 16f, 11f) }
            Mark.Add -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            Mark.Home -> { path(3f, 11f, 12f, 3f, 21f, 11f); path(6f, 10f, 6f, 21f, 18f, 21f, 18f, 10f); path(10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f) }
            Mark.Bell -> { path(5f, 17f, 7f, 14f, 7f, 9f, 9f, 5f, 15f, 5f, 17f, 9f, 17f, 14f, 19f, 17f, 5f, 17f); line(10f, 21f, 14f, 21f) }
            Mark.Download -> { line(12f, 2f, 12f, 15f); path(7f, 11f, 12f, 16f, 17f, 11f); path(4f, 17f, 4f, 21f, 20f, 21f, 20f, 17f) }
            Mark.Sound -> { path(3f, 9f, 7f, 9f, 12f, 4f, 12f, 20f, 7f, 15f, 3f, 15f, 3f, 9f); drawArc(color, -55f, 110f, false, p(9f, 5f), Size(12*u, 14*u), style = Stroke(1.4f*u)) }
            Mark.Network -> { repeat(3) { val r = 5f + it * 6f; drawArc(color, 225f, 90f, false, p(12-r, 20-r), Size(r*2*u, r*2*u), style = Stroke(1.4f*u)) }; drawCircle(color, u, p(12f, 20f)) }
            Mark.Access -> { circle(12f, 4f, 2f); path(3f, 9f, 12f, 11f, 21f, 9f); path(7f, 22f, 12f, 12f, 17f, 22f) }
            Mark.Power -> { line(12f, 2f, 12f, 11f); drawArc(color, -55f, 290f, false, p(3f, 4f), Size(18*u, 18*u), style = Stroke(1.4f*u)) }
            Mark.Back -> { path(14f, 4f, 6f, 12f, 14f, 20f); line(6f, 12f, 22f, 12f) }
            Mark.More -> repeat(3) { drawCircle(color, 1.4f*u, p(5f + it*7, 12f)) }
            Mark.Box -> { path(3f, 7f, 12f, 2f, 21f, 7f, 21f, 18f, 12f, 23f, 3f, 18f, 3f, 7f, 12f, 12f, 21f, 7f); line(12f, 12f, 12f, 23f) }
            Mark.Tune -> { repeat(3) { val x = 5f + it*7; line(x, 2f, x, 22f); drawCircle(Night, 3*u, p(x, 7f + it*4)); circle(x, 7f + it*4, 3f) } }
            Mark.Chip -> { drawRoundRect(color, p(6f, 6f), Size(12*u, 12*u), style = Stroke(1.4f*u)); repeat(4) { val v = 7f+it*3.5f; line(v, 2f, v, 5f); line(v, 19f, v, 22f); line(2f, v, 5f, v); line(19f, v, 22f, v) } }
            Mark.Info -> { circle(12f, 12f, 10f); line(12f, 10f, 12f, 18f); drawCircle(color, u, p(12f, 6f)) }
            Mark.Play -> path(7f, 3f, 21f, 12f, 7f, 21f, 7f, 3f)
            Mark.Close -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
            Mark.Image -> { path(2f, 3f, 22f, 3f, 22f, 21f, 2f, 21f, 2f, 3f); path(3f, 18f, 9f, 11f, 14f, 16f, 18f, 12f, 22f, 17f); circle(16f, 8f, 2f) }
            Mark.Sort -> { line(3f, 5f, 21f, 5f); line(6f, 12f, 18f, 12f); line(9f, 19f, 15f, 19f) }
        }
    }
}

@Composable
fun ConsoleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, mark: Mark? = null, primary: Boolean = false, enabled: Boolean = true, onFocus: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, spring(dampingRatio = .72f, stiffness = 480f), label = "button focus")
    val shape = RoundedCornerShape(50)
    val light = primary || focused
    Row(modifier.scale(scale).clip(shape).background(if (light) Color.White else Color.White.copy(alpha = .07f))
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
        .semantics { if (label.isEmpty()) contentDescription = if (mark == Mark.More) "Game options" else mark?.name ?: "Action" }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = if (label.isEmpty()) 12.dp else 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val color = if (!enabled) Soft.copy(alpha = .35f) else if (light) Night else Color.White
        if (mark != null) { Glyph(mark, Modifier.size(18.dp), color); if (label.isNotEmpty()) Spacer(Modifier.width(9.dp)) }
        if (label.isNotEmpty()) Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun ConsoleIcon(mark: Mark, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(40.dp).clip(CircleShape).background(if (focused) Color.White.copy(alpha = .22f) else Color.Transparent)
        .border(if (focused) 1.dp else 0.dp, if (focused) Color.White else Color.Transparent, CircleShape)
        .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }.clickable(onClick = onClick), contentAlignment = Alignment.Center) { Glyph(mark, Modifier.size(21.dp)) }
}

@Composable
fun FocusPanel(modifier: Modifier = Modifier, onClick: () -> Unit = {}, onFocus: () -> Unit = {}, content: @Composable BoxScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, spring(.75f, 420f), label = "tile focus")
    Box(modifier.scale(scale).border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
        .padding(4.dp).clip(RoundedCornerShape(7.dp)).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clickable(onClick = onClick)) { content(focused) }
}

class ConsoleMemory(context: Context) {
    private val prefs = context.getSharedPreferences("console_presentation", Context.MODE_PRIVATE)
    private fun key(game: Game) = "${game.container_id}|${game.exe_path}"
    fun art(game: Game): String? = prefs.getString("art:${key(game)}", null)
    fun setArt(game: Game, uri: String?) { prefs.edit().putString("art:${key(game)}", uri).apply() }
    fun played(game: Game) = prefs.getLong("played:${game.id}", 0)
    fun markPlayed(game: Game) { prefs.edit().putLong("played:${game.id}", System.currentTimeMillis()).apply() }
}

@Composable
fun GameArt(game: Game?, modifier: Modifier = Modifier, art: String? = null, hero: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, art) {
        value = withContext(Dispatchers.IO) {
            try {
                if (art == null) null else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(Uri.parse(art))?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1600).coerceAtLeast(1) }
                    context.contentResolver.openInputStream(Uri.parse(art))?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
                }
            } catch (_: Exception) { null }
        }
    }
    val hash = game?.name?.hashCode() ?: 8211
    val hue = ((hash.toLong() and 0x7fffffff) % 360).toFloat()
    val first = Color.hsv(hue, .48f, if (hero) .25f else .43f)
    val second = Color.hsv((hue + 52) % 360, .62f, if (hero) .12f else .2f)
    Box(modifier.background(Brush.linearGradient(listOf(first, second, Night)))) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply { moveTo(size.width * .24f, 0f); lineTo(size.width, size.height*.18f); lineTo(size.width*.73f, size.height); lineTo(0f, size.height*.81f); close() }
            drawPath(path, Brush.linearGradient(listOf(Color.White.copy(alpha=.12f), Color.Transparent)))
            repeat(4) { drawCircle(Color.White.copy(alpha = .025f), size.maxDimension * (.22f + it * .1f), Offset(size.width*.85f, size.height*.28f), style = Stroke(size.minDimension*.012f)) }
        }
        if (hero) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Night.copy(alpha=.8f), Night.copy(alpha=.22f)))))
    }
}

@Composable
fun AmbientBackground() {
    val transition = rememberInfiniteTransition(label = "ambient")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Canvas(Modifier.fillMaxSize().background(Night)) {
        drawRect(Brush.radialGradient(listOf(Color(0xFF183250).copy(alpha=.65f), Color.Transparent), Offset(size.width*(.65f+phase*.15f), size.height*(.25f+phase*.2f)), size.width*.7f))
        repeat(36) { i ->
            val x = ((i*137.37f + phase*25) % 1000) / 1000 * size.width
            val y = ((i*83.13f + phase*17) % 500) / 500 * size.height
            drawCircle(Color(0xFFD5E6FF).copy(alpha = .07f + (i%4)*.025f), if (i%5==0) 1.5f else .7f, Offset(x,y))
        }
    }
}

@Composable
fun PageTitle(title: String, subtitle: String = "", back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        ConsoleIcon(Mark.Back, "Quay lại", back)
        Spacer(Modifier.width(15.dp))
        Column { Text(title, fontSize = 30.sp, fontWeight = FontWeight.Light); if (subtitle.isNotBlank()) Text(subtitle, color = Soft, fontSize = 11.sp) }
    }
}

@Composable
fun DetailLabel(label: String, value: String) {
    Text(label.uppercase(), color = Soft, fontSize = 10.sp, letterSpacing = 1.5.sp)
    Text(value, fontSize = 15.sp, fontWeight = FontWeight.Light, maxLines = 4, overflow = TextOverflow.Ellipsis)
}
