package io.winport.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.io.InputStream
import java.io.ByteArrayOutputStream

private fun InputStream.bounded(count: Int): ByteArray {
    val out=ByteArrayOutputStream();val buffer=ByteArray(65536)
    while(out.size()<count) { val n=read(buffer,0,minOf(buffer.size,count-out.size()));if(n<0)break;if(n==0)continue;out.write(buffer,0,n) }
    return out.toByteArray()
}

object StationNative {
    init { System.loadLibrary("winport_core") }
    external fun call(root: String, action: String, payload: String): String
    external fun probeFd(fd: Int, name: String): String
}
fun station(context: Context, action: String, payload: JsonElement = buildJsonObject {}): JsonElement =
    Json.parseToJsonElement(StationNative.call(context.filesDir.path, action, payload.toString()))
fun JsonObject.str(key: String, fallback: String = "") = this[key]?.jsonPrimitive?.contentOrNull ?: fallback
fun obj(vararg values: Pair<String, String>) = buildJsonObject { values.forEach { put(it.first,it.second) } }
data class Doc(val uri: Uri, val path: String, val directory: Boolean, val size: Long = -1)

private fun documentKey(context: Context, uri: Uri, size: Long): String = station(context,"documentKey",buildJsonObject {
    put("authority",uri.authority.orEmpty());put("id",if(uri.pathSegments.contains("document")) Docs.getDocumentId(uri) else Docs.getTreeDocumentId(uri));put("size",size)
}).jsonPrimitive.content

fun documentKey(context: Context, uri: Uri): String {
    val size=context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE,Docs.Document.COLUMN_MIME_TYPE),null,null,null)?.use {
        if(it.moveToFirst() && !it.isNull(0) && it.getString(1)!=Docs.Document.MIME_TYPE_DIR) it.getLong(0) else -1L
    } ?: -1L
    return documentKey(context,uri,size)
}

fun documents(context: Context, tree: Uri): List<Doc> {
    val result = mutableListOf<Doc>()
    val visited = mutableSetOf<String>()
    fun walk(id: String, parent: String, depth: Int) {
        check(depth < 32) { "Thư mục quá sâu (tối đa 31 cấp)" }
        check(visited.add(id)) { "Nhà cung cấp trả về vòng lặp thư mục" }
        val children = Docs.buildChildDocumentsUriUsingTree(tree, id)
        context.contentResolver.query(children,arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_MIME_TYPE,Docs.Document.COLUMN_SIZE),null,null,null)?.use { c ->
            val dirs = mutableListOf<Pair<String,String>>()
            while (c.moveToNext()) {
                check(result.size < 20000) { "Thư mục có hơn 20.000 mục; hãy chọn thư mục nhỏ hơn" }
                val child=c.getString(0); val name=c.getString(1)
                check(name != "." && name != ".." && '/' !in name && '\\' !in name) { "Tên tệp không an toàn" }
                val path=if(parent.isEmpty()) name else "$parent/$name"
                val directory=c.getString(2)==Docs.Document.MIME_TYPE_DIR
                result.add(Doc(Docs.buildDocumentUriUsingTree(tree,child),path,directory,if(c.isNull(3)) -1 else c.getLong(3)))
                if(directory) dirs.add(child to path)
            }
            dirs.forEach { walk(it.first,it.second,depth+1) }
        } ?: error("Không thể đọc thư mục; hãy cấp lại quyền SAF")
    }
    walk(Docs.getTreeDocumentId(tree),"",0)
    return result
}

fun readRange(context: Context, uri: Uri, offset: Long = 0, count: Int = 131072): ByteArray {
    require(offset >= 0 && count in 0..262144)
    return context.contentResolver.openInputStream(uri)?.use { input ->
        var remaining=offset
        while(remaining>0) { val skipped=input.skip(remaining); if(skipped>0) remaining-=skipped else { check(input.read()!=-1) { "Tệp bị cắt khi đọc header" }; remaining-- } }
        input.bounded(count)
    } ?: error("Không mở được nội dung")
}
fun detect(context: Context, doc: Doc): JsonObject? {
    try {
        context.contentResolver.openFileDescriptor(doc.uri,"r")?.use { fd ->
            val result=Json.parseToJsonElement(StationNative.probeFd(fd.fd,doc.path))
            return if(result is JsonNull) null else result.jsonObject
        }
    } catch(e: Exception) {
        if(doc.path.substringAfterLast('.').lowercase() in listOf("chd","cso")) throw IllegalStateException("Không đọc được ảnh nén: ${e.message}. Có thể thêm thủ công và chọn hệ máy.",e)
    }
    val head=readRange(context,doc.uri)
    val chunks=mutableListOf<JsonElement>()
    repeat(4) {
        val result=station(context,"probe",buildJsonObject { put("name",doc.path); put("bytes",JsonArray(head.map { JsonPrimitive(it.toInt() and 255) })); put("chunks",JsonArray(chunks)) })
        if(result is JsonNull) return null
        val value=result.jsonObject
        val need=value["need"]?.jsonObject ?: return value
        val offset=need.getValue("offset").jsonPrimitive.long
        val bytes=readRange(context,doc.uri,offset,need.getValue("length").jsonPrimitive.int)
        chunks.add(buildJsonObject { put("offset",offset); put("bytes",JsonArray(bytes.map { JsonPrimitive(it.toInt() and 255) })) })
    }
    error("Định dạng đĩa cần quá nhiều lượt đọc")
}
private fun cacheImage(context: Context, uri: Uri): String {
    val bytes=context.contentResolver.openInputStream(uri)?.use { it.bounded(16_000_001) } ?: error("Không đọc được ảnh")
    return cacheImage(context,bytes)
}
private fun cacheImage(context: Context, bytes: ByteArray): String {
    val dir=File(context.filesDir,"art").apply { mkdirs() }
    check(bytes.size<=16_000_000) { "Ảnh vượt quá 16 MB" }
    val target=File(dir,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".img")
    target.writeBytes(bytes)
    return target.path
}
fun customArt(context: Context, uri: Uri, game: JsonObject): JsonElement {
    val art=cacheImage(context,uri)
    return station(context,"upsert",JsonObject(game+mapOf("art" to JsonPrimitive(art))))
}
fun scan(context: Context, tree: Uri): Pair<Int,List<String>> {
    val all=documents(context,tree)
    val legacy=station(context,"load").jsonObject["games"]!!.jsonArray.mapNotNull { entry ->
        val g=entry.jsonObject
        if(g.str("document_key").isNotEmpty()) null else runCatching { JsonObject(g+mapOf("document_key" to JsonPrimitive(documentKey(context,Uri.parse(g.str("uri")))))) }.getOrNull()
    }
    if(legacy.isNotEmpty()) station(context,"batch",JsonArray(legacy))
    var count=0
    val errors=mutableListOf<String>()
    val discovered=mutableListOf<JsonObject>()
    val folders=mutableSetOf<String>()
    val ordered=all.filter { !it.directory }.sortedBy { if(it.path.endsWith("PARAM.SFO",true)) 0 else 1 }
    for(doc in ordered) {
        val ext=doc.path.substringAfterLast('.').lowercase()
        if(ext !in setOf("sfo","cue","bin","chd","pbp","m3u","exe","iso","cso","elf","pkg")) continue
        if(folders.any { it.isEmpty() || doc.path.startsWith("$it/") }) continue
        try {
            val detected=detect(context,doc) ?: continue
            if(detected.str("system")=="PS4") {
                if(ext=="sfo") folders.add(doc.path.substringBeforeLast('/',"").let {if(it.endsWith("sce_sys",true)) it.substringBeforeLast('/',"") else it})
                continue
            }
            if(detected["system"]==null) { if(detected["ambiguous"]!=null) errors.add("${doc.path}: ${detected.str("ambiguous")}"); continue }
            var uri=doc.uri; var art=""
            detected["icon"]?.jsonObject?.let { icon ->
                art=runCatching { cacheImage(context,readRange(context,doc.uri,icon.getValue("offset").jsonPrimitive.long,icon.getValue("length").jsonPrimitive.int)) }.getOrDefault("")
            }
            val isSfo=ext=="sfo"
            val folder=if(isSfo) doc.path.substringBeforeLast('/',"").let { if(it.endsWith("PS3_GAME",true)||it.endsWith("sce_sys",true)) it.substringBeforeLast('/',"") else it } else ""
            if(isSfo) {
                uri=all.firstOrNull { it.directory && it.path==folder }?.uri ?: tree
                val prefix=if(folder.isEmpty()) "" else "$folder/"
                if(detected.str("system")=="PSP") uri=all.firstOrNull { !it.directory && (it.path.equals("${prefix}EBOOT.PBP",true)||it.path.equals("${prefix}EBOOT.ELF",true)) }?.uri ?: continue
                folders.add(folder)
                val parent=doc.path.substringBeforeLast('/',"").let { if(it.isEmpty()) "" else "$it/" }
                val image=all.firstOrNull { !it.directory && (it.path.equals("${parent}PIC1.PNG",true)||it.path.equals("${parent}ICON0.PNG",true)) }
                if(image!=null) art=cacheImage(context,image.uri)
            }
            discovered.add(JsonObject(detected + mapOf("document_key" to JsonPrimitive(documentKey(context,uri,if(isSfo) -1 else doc.size)),"uri" to JsonPrimitive(uri.toString()),"art" to JsonPrimitive(art),"folder" to JsonPrimitive(tree.toString()),"path" to JsonPrimitive(if(isSfo) folder else doc.path))))
            count++
        } catch(e: Exception) { errors.add("${doc.path}: ${e.message}") }
    }
    station(context,"batch",JsonArray(discovered))
    return count to errors
}
fun launchExternal(context: Context, game: JsonObject, packageName: String) {
    check(packageName.isNotBlank()) { "Chưa chọn ứng dụng giả lập ngoài" }
    val uri=Uri.parse(game.str("uri"))
    val view=Intent(Intent.ACTION_VIEW).setPackage(packageName).setDataAndType(uri,"application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    view.clipData=ClipData.newRawUri(game.str("title"),uri)
    try { context.startActivity(view) } catch(_: Exception) {
        val launch=context.packageManager.getLaunchIntentForPackage(packageName) ?: error("Ứng dụng đã bị gỡ hoặc không thể mở")
        context.startActivity(launch)
        Toast.makeText(context,"Ứng dụng không nhận tệp này; đã mở ứng dụng để bạn chọn trò chơi",Toast.LENGTH_LONG).show()
    }
}
fun stageGame(context: Context, game: JsonObject): File {
    val uri=Uri.parse(game.str("uri"))
    val path=game.str("path")
    val pspFolder=game.str("system")=="PSP" && path.endsWith(".pbp",true)
    if(path.endsWith(".cue",true)||path.endsWith(".m3u",true)||pspFolder) {
        check(game.str("folder").isNotBlank()) { "PBP/CUE/M3U cần quét thư mục để có quyền đọc các tệp đi kèm" }
        val docs=documents(context,Uri.parse(game.str("folder"))).filter { !it.directory }.associateBy {it.path}
        val required=linkedSetOf<String>()
        fun collect(name: String) {
            check(required.size<128) {"Danh sách đĩa quá lớn"}
            if(!required.add(name)) return
            val doc=docs[name] ?: error("Thiếu tệp đi kèm: $name")
            if(name.endsWith(".cue",true)||name.endsWith(".m3u",true)) {
                val refs=station(context,"dependencies",obj("name" to name,"text" to readRange(context,doc.uri).toString(Charsets.UTF_8))).jsonArray
                refs.forEach { ref ->
                    val resolved=java.nio.file.Paths.get(name).parent?.resolve(ref.jsonPrimitive.content)?.normalize() ?: java.nio.file.Paths.get(ref.jsonPrimitive.content).normalize()
                    check(!resolved.isAbsolute && !resolved.startsWith("..")) {"Tệp đi kèm nằm ngoài thư mục được chọn"}
                    collect(resolved.toString())
                }
            }
        }
        if(pspFolder) {
            val files=station(context,"pspFiles",buildJsonObject { put("path",path);put("files",JsonArray(docs.keys.map(::JsonPrimitive))) }).jsonArray
            files.forEach { required.add(it.jsonPrimitive.content) }
        } else collect(path)
        val key=MessageDigest.getInstance("SHA-256").digest(game.str("uri").toByteArray()).joinToString("") {"%02x".format(it)}
        val dir=File(context.filesDir,"roms/set-$key").apply {mkdirs()}
        for(name in required) {
            val out=File(dir,name);out.parentFile!!.mkdirs()
            val temp=File(out.path+".part")
            try {
                context.contentResolver.openInputStream(docs.getValue(name).uri)?.use { input -> temp.outputStream().use { dest ->
                    val buffer=ByteArray(65536);var total=0L
                    while(true) { val n=input.read(buffer);if(n<0)break;total+=n;check(total<=16_000_000_000L) {"Đĩa vượt quá 16 GB"};dest.write(buffer,0,n) }
                } } ?: error("Không đọc được $name")
                java.nio.file.Files.move(temp.toPath(),out.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        return File(dir,path)
    }
    return importRom(context,uri)
}
fun fetchArt(context: Context, game: JsonObject): JsonElement {
    val repo=when(game.str("system")) {"PS1"->"Sony_-_PlayStation";"PSP"->"Sony_-_PlayStation_Portable";"PS2"->"Sony_-_PlayStation_2";else->error("Hệ máy này dùng ảnh trong thư mục hoặc ảnh tùy chọn")}
    val cache=File(context.cacheDir,"art-index-$repo.json")
    fun get(url: String, max: Int): ByteArray {
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=15000;c.readTimeout=20000;c.setRequestProperty("User-Agent","Winport")
        try { check(c.responseCode==200) { "Máy chủ ảnh trả mã ${c.responseCode}" }; return c.inputStream.use { val b=it.bounded(max+1);check(b.size<=max) {"Dữ liệu ảnh quá lớn"};b } } finally { c.disconnect() }
    }
    val tree=if(cache.isFile && System.currentTimeMillis()-cache.lastModified()<86400000) cache.readText() else get("https://api.github.com/repos/libretro-thumbnails/$repo/git/trees/master?recursive=1",16_000_000).toString(Charsets.UTF_8).also { cache.writeText(it) }
    val index=Json.parseToJsonElement(tree).jsonObject
    check(index["truncated"]?.jsonPrimitive?.booleanOrNull!=true) { "Danh sách ảnh quá lớn; hãy chọn ảnh tùy chỉnh" }
    val names=index.getValue("tree").jsonArray.mapNotNull { it.jsonObject.str("path").takeIf { p->p.startsWith("Named_Boxarts/")&&p.endsWith(".png") }?.removePrefix("Named_Boxarts/") }
    val match=station(context,"matchArt",buildJsonObject { put("title",game.str("title"));put("names",JsonArray(names.map(::JsonPrimitive))) })
    check(match !is JsonNull) { "Không tìm được ảnh gần đúng; hãy sửa tên hoặc chọn ảnh riêng" }
    val filename=URLEncoder.encode(match.jsonPrimitive.content,"UTF-8").replace("+","%20")
    val bytes=get("https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/Named_Boxarts/$filename",16_000_000)
    val dir=File(context.filesDir,"art").apply { mkdirs() }
    val target=File(dir,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "-box.png");target.writeBytes(bytes)
    return station(context,"upsert",JsonObject(game+mapOf("art" to JsonPrimitive(target.path))))
}
