package org.kineticos.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a picked image into app storage (downscaled, PNG keeps logo transparency) and returns the file path. */
object ImageStore {
    suspend fun import(ctx: Context, uri: Uri, kind: String, maxPx: Int = 1600, png: Boolean = false): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
            val scale = maxPx.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val sb = if (scale < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val dir = File(ctx.filesDir, "design").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("$kind-") }?.forEach { it.delete() }   // keep only the current one
            val out = dir.resolve("$kind-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
            out.outputStream().use { sb.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 88, it) }
            out.absolutePath
        }.getOrNull()
    }
    fun delete(path: String?) { path?.let { runCatching { File(it).delete() } } }
}
