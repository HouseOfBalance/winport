package io.winport.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class UiState(
    val data: Snapshot = Snapshot(), val busy: Boolean = true,
    val error: String? = null, val message: String? = null,
    val running: Map<String, Int> = emptyMap()
)

class CoreViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()

    init {
        work {
            NativeCore.request("init", buildJsonObject { put("data_dir", File(application.filesDir, "core").absolutePath) })
        }
    }

    private fun work(action: suspend () -> Unit) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, error = null, message = null)
            try {
                withContext(Dispatchers.IO) { action() }
                val snapshot = withContext(Dispatchers.IO) {
                    NativeCore.json.decodeFromString<Snapshot>(NativeCore.request("snapshot"))
                }
                mutable.value = mutable.value.copy(data = snapshot)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(error = error.message ?: "Operation failed")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun dismiss() { mutable.value = mutable.value.copy(error = null, message = null) }
    fun mutate(method: String, payload: JsonElement) = work { NativeCore.request(method, payload) }
    fun remove(kind: String, id: String) = mutate("remove$kind", NativeCore.id(id))
    fun saveGame(game: Game) = mutate(if (game.id.isEmpty()) "addGame" else "updateGame", NativeCore.json.parseToJsonElement(NativeCore.json.encodeToString(game)))
    fun saveProfile(profile: Profile) = mutate(if (profile.id.isEmpty()) "addProfile" else "updateProfile", NativeCore.json.parseToJsonElement(NativeCore.json.encodeToString(profile)))
    fun saveContainer(id: String, name: String, wine: String) = mutate(if (id.isEmpty()) "addContainer" else "updateContainer", buildJsonObject {
        put("id", id); put("name", name); put("wine_component", wine)
    })
    fun launch(game: Game) = work {
        val response = NativeCore.json.parseToJsonElement(NativeCore.request("launch", NativeCore.id(game.id)))
        val pid = response.jsonObject.getValue("pid").jsonPrimitive.content.toInt()
        withContext(Dispatchers.Main) {
            mutable.value = mutable.value.copy(running = mutable.value.running + (game.id to pid), message = "Started process $pid")
        }
    }
    fun stop(game: Game) {
        val pid = mutable.value.running[game.id] ?: return
        work {
            NativeCore.request("stop", buildJsonObject { put("pid", pid) })
            withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(running = mutable.value.running - game.id) }
        }
    }
    suspend fun preview(gameId: String): String = withContext(Dispatchers.IO) {
        try {
            val response = NativeCore.request("buildLaunchPlan", NativeCore.id(gameId))
            NativeCore.prettyJson.encodeToString(NativeCore.json.parseToJsonElement(response))
        } catch (error: Exception) { error.message ?: "Preview unavailable" }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).substringAfterLast('/').substringAfterLast('\\')
        }
        error("The provider did not supply a filename")
    }

    fun importComponent(uri: Uri, kind: String) = work {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "import-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val name = displayName(uri)
            require(name !in listOf(".", "..") && name.isNotBlank()) { "Invalid archive name" }
            val target = File(dir, name)
            app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("Cannot read archive")
            NativeCore.request("installComponent", buildJsonObject { put("archive_path", target.absolutePath); put("kind", kind) })
        } finally { dir.deleteRecursively() }
    }

    fun importExe(uri: Uri, containerId: String, complete: (String) -> Unit) = work {
        val container = mutable.value.data.containers.firstOrNull { it.id == containerId } ?: error("Select a container first")
        val name = displayName(uri)
        require(name.endsWith(".exe", ignoreCase = true)) { "Please select a Windows .exe file" }
        val relative = "drive_c/imports/${java.util.UUID.randomUUID()}/$name"
        val target = File(container.dir, relative)
        target.parentFile?.mkdirs()
        try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("Cannot read executable")
        } catch (error: Exception) { target.parentFile?.deleteRecursively(); throw error }
        withContext(Dispatchers.Main) { complete(relative) }
    }
}
