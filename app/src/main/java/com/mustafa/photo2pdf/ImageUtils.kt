package com.mustafa.photo2pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Fotoğraf sıkıştırma, boyut küçültme ve döndürme işlemlerini tek bir yerde
 * toplayan yardımcı fonksiyonlar. Hem kamera ile çekilen hem galeriden
 * eklenen fotoğraflar bu fonksiyonları kullanır.
 */
object ImageUtils {

    // Fotoğrafların en uzun kenarı bu değeri geçmeyecek şekilde küçültülür.
    const val MAX_IMAGE_DIMENSION = 1600

    // JPEG sıkıştırma kalitesi (0-100). 75 iyi bir denge sağlar.
    const val JPEG_QUALITY = 75

    /**
     * Verilen dosyadaki fotoğrafı okur, EXIF yönünü uygular, en uzun kenarı
     * MAX_IMAGE_DIMENSION ile sınırlar ve JPEG olarak yeniden kaydeder.
     */
    fun compressFileInPlace(file: File) {
        try {
            val bitmap = loadDownsampledBitmap(file, MAX_IMAGE_DIMENSION) ?: return
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            // Sıkıştırma başarısız olursa dosya olduğu gibi kalır.
        }
    }

    /**
     * Dosyayı bellek dostu bir şekilde (gerekiyorsa örnekleme yaparak) okur,
     * EXIF dönüşünü uygular ve en uzun kenarı maxDim değerine indirir.
     */
    fun loadDownsampledBitmap(file: File, maxDim: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) return null

        var inSampleSize = 1
        while (width / (inSampleSize * 2) >= maxDim && height / (inSampleSize * 2) >= maxDim) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

        bitmap = rotateIfNeeded(bitmap, file) ?: bitmap

        val largerSide = maxOf(bitmap.width, bitmap.height)
        if (largerSide > maxDim) {
            val ratio = maxDim.toFloat() / largerSide
            val newWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        return bitmap
    }

    fun rotateIfNeeded(bitmap: Bitmap?, file: File): Bitmap? {
        if (bitmap == null) return null
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Dosyadaki fotoğrafı verilen açı kadar döndürüp aynı dosyaya kaydeder.
     * degrees: 90, -90, 180 gibi hazır değerler ya da elle seçilen 0-359 arası bir açı olabilir.
     */
    fun rotateFileBy(file: File, degrees: Float) {
        try {
            var bitmap = loadDownsampledBitmap(file, MAX_IMAGE_DIMENSION) ?: return
            if (degrees % 360f != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            // Döndürme başarısız olursa dosya olduğu gibi kalır.
        }
    }

    /**
     * Önizleme diyaloğu için küçük ve hızlı bir döndürülmüş bitmap üretir,
     * dosyaya kaydetmez.
     */
    fun previewRotated(file: File, degrees: Float, maxDim: Int = 400): Bitmap? {
        var bitmap = loadDownsampledBitmap(file, maxDim) ?: return null
        if (degrees % 360f == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }
}
