package io.winport.app

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

internal data class StageFile(val source: String, val path: String, val size: Long)
data class StageProgress(val file: String, val completed: Int, val count: Int, val bytes: Long, val total: Long)

internal fun isolatedSlimEboot(treeId: String, paths: Collection<String>): Boolean =
    treeId.substringAfterLast('/').equals("SLIM EBOOT", ignoreCase=true) &&
        paths.size == 1 && paths.single().equals("EBOOT.PBP", ignoreCase=true)

internal object GameStaging {
    private fun child(root: File, path: String): File {
        check(path.isNotEmpty() && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Đường dẫn tệp đi kèm không an toàn: $path" }
        return File(root, path)
    }

    @Synchronized
    fun copy(target: File, files: List<StageFile>, entry: String, directories: List<String> = emptyList(),
             open: (String) -> InputStream, verifySource: () -> Unit = {}, progress: (StageProgress) -> Unit = {}): File {
        check(files.isNotEmpty() && files.any { it.path == entry }) { "Không tìm thấy tệp khởi động; hãy quét lại thư mục" }
        check(files.all { it.size in 0..16_000_000_000L }) { "Không xác minh được kích thước tệp hoặc tệp vượt quá 16 GB. Hãy tải đầy đủ về bộ nhớ máy rồi quét lại." }
        check(files.map { it.path.lowercase(java.util.Locale.ROOT) }.distinct().size == files.size) { "Thư mục có tên tệp trùng nhau khi bỏ qua chữ hoa/thường" }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Không tạo được thư mục lưu trò chơi" }
        val work=Files.createTempDirectory(target.parentFile!!.toPath(),"${target.name}.pending-").toFile()
        val backup=File(target.parentFile,"${target.name}.old-${UUID.randomUUID()}")
        var published=false
        try {
            directories.forEach { check(child(work,it).mkdirs() || child(work,it).isDirectory) { "Không tạo được thư mục $it" } }
            val total=files.sumOf { it.size }
            var bytes=0L
            var lastUpdate=0L
            files.forEachIndexed { index, file ->
                val out=child(work,file.path)
                check(out.parentFile!!.isDirectory || out.parentFile!!.mkdirs()) { "Không tạo được thư mục cho ${file.path}" }
                progress(StageProgress(file.path,index,files.size,bytes,total))
                var copied=0L
                open(file.source).use { input -> out.outputStream().use { dest ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        check(!Thread.currentThread().isInterrupted) { "Đã hủy sao chép trò chơi" }
                        val n=input.read(buffer)
                        if(n<0) break
                        check(n!=0) { "Nhà cung cấp tệp ngừng trả dữ liệu cho ${file.path}; hãy chép trò chơi vào bộ nhớ máy rồi thử lại" }
                        copied+=n
                        check(copied<=file.size) { "Kích thước ${file.path} đã thay đổi; hãy quét lại thư mục" }
                        dest.write(buffer,0,n)
                        bytes+=n
                        val now=System.nanoTime()
                        if(now-lastUpdate>=100_000_000L) { progress(StageProgress(file.path,index,files.size,bytes,total));lastUpdate=now }
                    }
                    dest.fd.sync()
                } }
                check(copied==file.size && out.length()==file.size) { "Sao chép thiếu ${file.path}: $copied/${file.size} byte. Kiểm tra dung lượng trống và quyền đọc rồi thử lại." }
                progress(StageProgress(file.path,index+1,files.size,bytes,total))
            }
            verifySource()
            check(child(work,entry).isFile) { "Thiếu tệp khởi động sau khi sao chép" }
            if(target.exists()) check(target.renameTo(backup)) { "Không thay được bản sao cũ; hãy thử lại" }
            if(!work.renameTo(target)) {
                check(!backup.exists() || backup.renameTo(target)) { "Không khôi phục được bản sao cũ. Hãy quét và sao chép lại thư mục." }
                error("Không hoàn tất bản sao trò chơi; không khởi động lõi")
            }
            published=true
            return child(target,entry)
        } finally {
            work.deleteRecursively()
            if(published) backup.deleteRecursively()
        }
    }
}
