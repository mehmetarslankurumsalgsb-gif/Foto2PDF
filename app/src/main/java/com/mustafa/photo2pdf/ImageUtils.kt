package com.mustafa.photo2pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
    const val MAX_IMAGE_DIMENSION = 2200

    // JPEG sıkıştırma kalitesi (0-100). Belge/yazı netliği için yüksek tutuldu.
    const val JPEG_QUALITY = 90

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

    // ---------- Görüntü filtreleri ----------

    enum class Filter { DOCUMENT, GRAY, ENHANCE }

    /**
     * Dosyadaki fotoğrafa filtre uygular ve aynı dosyaya kaydeder.
     * DOCUMENT: gölgeleri temizler, kağıdı beyaz, yazıyı koyu yapar (fotokopi gibi).
     * GRAY: gri tonlama. ENHANCE: kontrast ve renkleri canlandırır.
     */
    fun applyFilter(file: File, filter: Filter): Boolean {
        return try {
            val source = loadDownsampledBitmap(file, MAX_IMAGE_DIMENSION) ?: return false
            val result = when (filter) {
                Filter.DOCUMENT -> documentFilter(source)
                Filter.GRAY -> colorMatrixFilter(source, ColorMatrix().apply { setSaturation(0f) })
                Filter.ENHANCE -> colorMatrixFilter(source, enhanceMatrix())
            }
            if (result != source) source.recycle()
            FileOutputStream(file).use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            result.recycle()
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun colorMatrixFilter(source: Bitmap, matrix: ColorMatrix): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun enhanceMatrix(): ColorMatrix {
        val contrast = 1.25f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 8f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val saturation = ColorMatrix().apply { setSaturation(1.3f) }
        contrastMatrix.postConcat(saturation)
        return contrastMatrix
    }

    /**
     * Belge filtresi: her bölgenin kağıt rengini (arka plan) tahmin eder,
     * fotoğrafı buna bölerek gölgeleri yok eder, sonra kontrastı artırır.
     */
    private fun documentFilter(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) Gri ton
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // 2) Arka plan tahmini: bloklara bölüp her bloğun en parlak kısmına yakın değeri al
        val block = 32
        val bw = (w + block - 1) / block
        val bh = (h + block - 1) / block
        val small = IntArray(bw * bh)
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                val x0 = bx * block
                val y0 = by * block
                val x1 = minOf(x0 + block, w)
                val y1 = minOf(y0 + block, h)
                val hist = IntArray(256)
                var count = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        hist[gray[y * w + x]]++
                        count++
                        x += 2
                    }
                    y += 2
                }
                // Parlaklık sıralamasında %90'lık değer = kağıdın rengi
                val target = (count * 0.9f).toInt()
                var acc = 0
                var value = 255
                for (v in 0..255) {
                    acc += hist[v]
                    if (acc >= target) { value = v; break }
                }
                val gv = value.coerceAtLeast(1)
                small[by * bw + bx] = (0xFF shl 24) or (gv shl 16) or (gv shl 8) or gv
            }
        }
        val smallBitmap = Bitmap.createBitmap(small, bw, bh, Bitmap.Config.ARGB_8888)
        val bgBitmap = Bitmap.createScaledBitmap(smallBitmap, w, h, true)
        smallBitmap.recycle()
        val background = IntArray(w * h)
        bgBitmap.getPixels(background, 0, w, 0, 0, w, h)
        bgBitmap.recycle()

        // 3) Normalize + kontrast
        val low = 70
        val high = 225
        for (i in pixels.indices) {
            val bg = (background[i] and 0xFF).coerceAtLeast(1)
            var v = gray[i] * 255 / bg
            v = (v - low) * 255 / (high - low)
            if (v < 0) v = 0
            if (v > 255) v = 255
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
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
