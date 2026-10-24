package com.mustafa.photo2pdf

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * PDF'lerin tek bir yerde tutulmasını sağlar: telefonun
 * İndirilenler/ArslanPDF klasörü. Kaydedilenler ekranı da burayı gösterir;
 * dosya yöneticisinden silinen PDF uygulamadan da kaybolur.
 */
object PdfStore {

    const val FOLDER = "ArslanPDF"

    data class Item(
        val name: String,
        val uri: Uri,
        val modifiedMillis: Long,
        val file: File? // Sadece Android 9 ve altında dolu
    )

    private val usesMediaStore: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @Suppress("DEPRECATION")
    private fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    private fun relativePath(): String = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"

    /** Hazırlanmış PDF dosyasını İndirilenler/ArslanPDF klasörüne kaydeder. */
    fun save(context: Context, source: File, fileName: String): Item {
        return if (usesMediaStore) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Dosya oluşturulamadı")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Dosya açılamadı")
                out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            // Aynı isimde dosya varsa Android ismi kendisi değiştirir; gerçek ismi oku.
            val realName = queryName(context, uri) ?: fileName
            Item(realName, uri, System.currentTimeMillis(), null)
        } else {
            val dir = legacyDir().apply { mkdirs() }
            val base = fileName.removeSuffix(".pdf")
            var target = File(dir, "$base.pdf")
            var counter = 1
            while (target.exists()) {
                target = File(dir, "${base}_$counter.pdf")
                counter++
            }
            source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            Item(target.name, fileUri(context, target), target.lastModified(), target)
        }
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** ArslanPDF klasöründeki PDF'leri listeler. */
    fun list(context: Context): List<Item> {
        val items = mutableListOf<Item>()
        if (usesMediaStore) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER%", "%.pdf")
            try {
                context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        val modified = cursor.getLong(2) * 1000L
                        items.add(Item(name, ContentUris.withAppendedId(collection, id), modified, null))
                    }
                }
            } catch (e: Exception) {
                // Liste okunamazsa boş liste döner.
            }
        } else {
            legacyDir().listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
                ?.forEach { f -> items.add(Item(f.name, fileUri(context, f), f.lastModified(), f)) }
        }
        return items
    }

    fun delete(context: Context, item: Item): Boolean {
        return try {
            if (item.file != null) item.file.delete()
            else context.contentResolver.delete(item.uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Eski sürümde uygulamanın kendi gizli klasörüne kaydedilen PDF'leri
     * İndirilenler/ArslanPDF klasörüne taşır (bir kerelik).
     */
    fun migrateOldFiles(context: Context) {
        val oldDir = File(context.getExternalFilesDir(null), "pdfs")
        val oldFiles = oldDir.listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
            ?: return
        for (file in oldFiles) {
            try {
                save(context, file, file.name)
                file.delete()
            } catch (e: Exception) {
                // Taşınamayan dosya yerinde kalır, bir sonraki açılışta tekrar denenir.
            }
        }
    }
}
