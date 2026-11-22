package io.winport.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object NativeCore {
    init { System.loadLibrary("winport_core") }
    external fun call(method: String, payload: String): String
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val prettyJson = Json { prettyPrint = true }
    fun request(method: String, payload: JsonElement = buildJsonObject {}): String = call(method, payload.toString())
    fun id(value: String) = buildJsonObject { put("id", value) }
}

@Serializable data class Component(val id: String, val kind: String, val version: String, val path: String)
@Serializable data class Container(val id: String, val name: String, val wine_component: String, val dir: String)
@Serializable data class Profile(
    val id: String = "", val name: String = "Custom", val bigblock: Int = 2,
    val strongmem: Int = 1, val safeflags: Int = 1, val dxvk_hud: Boolean = false,
    val fps_limit: Int = 60, val width: Int = 1280, val height: Int = 720,
    val cpu_cores: List<Int> = emptyList()
)
@Serializable data class Game(
    val id: String = "", val name: String = "", val exe_path: String = "",
    val container_id: String = "", val profile_id: String = "balanced",
    val extra_args: List<String> = emptyList()
)
@Serializable data class Snapshot(
    val components: List<Component> = emptyList(), val containers: List<Container> = emptyList(),
    val profiles: List<Profile> = emptyList(), val games: List<Game> = emptyList()
)
