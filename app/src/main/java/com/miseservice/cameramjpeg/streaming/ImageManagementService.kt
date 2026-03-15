package com.miseservice.cameramjpeg.streaming

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageManagementService(rootDir: File) {

    private val imageDir: File = File(rootDir, "captures").apply { mkdirs() }
    private val tsFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    data class SavedImage(
        val name: String,
        val sizeBytes: Long,
        val modifiedAt: Long
    )

    fun saveLatest(frameStore: FrameStore): SavedImage? {
        val frame = frameStore.latest() ?: return null
        val filename = "img_${tsFormat.format(Date())}.jpg"
        val file = File(imageDir, filename)
        file.writeBytes(frame)
        return SavedImage(file.name, file.length(), file.lastModified())
    }

    fun list(): List<SavedImage> {
        val files = imageDir.listFiles()?.toList().orEmpty()
        return files
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .map { SavedImage(it.name, it.length(), it.lastModified()) }
    }

    fun count(): Int = list().size

    fun delete(name: String): Boolean {
        if (!name.endsWith(".jpg", ignoreCase = true)) return false
        if (name.contains("/") || name.contains("\\")) return false
        return File(imageDir, name).takeIf { it.exists() && it.isFile }?.delete() ?: false
    }

    fun clear(): Int {
        val files = list()
        var deleted = 0
        files.forEach {
            if (File(imageDir, it.name).delete()) deleted += 1
        }
        return deleted
    }
}

