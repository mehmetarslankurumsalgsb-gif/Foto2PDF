package com.mustafa.photo2pdf

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // Fotoğrafların en uzun kenarı bu değeri geçmeyecek şekilde küçültülür.
        private const val MAX_IMAGE_DIMENSION = 1600
        // JPEG sıkıştırma kalitesi (0-100). 75 iyi bir denge sağlar.
        private const val JPEG_QUALITY = 75
    }

    private val photoFiles = mutableListOf<File>()
    private var pendingPhotoFile: File? = null
    private var lastPdfFile: File? = null

    private lateinit var statusText: TextView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var createPdfButton: Button
    private lateinit var whatsappButton: Button
    private lateinit var telegramButton: Button
    private lateinit var shareButton: Button

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingPhotoFile
            pendingPhotoFile = null
            if (success && file != null) {
                compressFileInPlace(file)
                photoFiles.add(file)
                addThumbnail(file)
                updateStatus()
            } else {
                file?.delete()
                if (!success) {
                    Toast.makeText(this, "Fotoğraf alınamadı.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, "Kameraya izin vermeniz gerekiyor.", Toast.LENGTH_SHORT).show()
            }
        }

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) {
                var addedCount = 0
                for (uri in uris) {
                    if (importImageFromUri(uri)) addedCount++
                }
                updateStatus()
                if (addedCount < uris.size) {
                    Toast.makeText(this, "Bazı fotoğraflar eklenemedi.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        thumbnailContainer = findViewById(R.id.thumbnailContainer)
        createPdfButton = findViewById(R.id.createPdfButton)
        whatsappButton = findViewById(R.id.whatsappButton)
        telegramButton = findViewById(R.id.telegramButton)
        shareButton = findViewById(R.id.shareButton)

        findViewById<Button>(R.id.takePhotoButton).setOnClickListener {
            checkPermissionAndLaunchCamera()
        }

        findViewById<Button>(R.id.galleryButton).setOnClickListener {
            pickImagesLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        createPdfButton.setOnClickListener {
            createPdf()
        }

        whatsappButton.setOnClickListener { shareToPackage("com.whatsapp", "WhatsApp") }
        telegramButton.setOnClickListener { shareToPackage("org.telegram.messenger", "Telegram") }
        shareButton.setOnClickListener { shareGeneric() }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            photoFiles.forEach { it.delete() }
            photoFiles.clear()
            thumbnailContainer.removeAllViews()
            lastPdfFile = null
            updatePdfButtons()
            updateStatus()
        }

        updatePdfButtons()
    }

    private fun checkPermissionAndLaunchCamera() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> launchCamera()
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val photosDir = File(getExternalFilesDir(null), "photos").apply { mkdirs() }
        val fileName = "PHOTO_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        val file = File(photosDir, fileName)
        pendingPhotoFile = file

        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        takePictureLauncher.launch(uri)
    }

    /**
     * Galeriden seçilen bir görseli uygulamanın kendi klasörüne kopyalar,
     * boyutunu küçültüp sıkıştırır ve listeye ekler. Başarılıysa true döner.
     */
    private fun importImageFromUri(uri: Uri): Boolean {
        return try {
            val photosDir = File(getExternalFilesDir(null), "photos").apply { mkdirs() }
            val fileName = "GALLERY_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
            val destFile = File(photosDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: return false

            compressFileInPlace(destFile)

            if (!destFile.exists() || destFile.length() == 0L) {
                return false
            }

            photoFiles.add(destFile)
            addThumbnail(destFile)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verilen dosyadaki fotoğrafı okur, EXIF yönünü uygular, en uzun kenarı
     * MAX_IMAGE_DIMENSION ile sınırlar ve JPEG olarak yeniden kaydederek
     * dosya boyutunu küçültür. Bu sayede PDF çıktısı da küçük kalır.
     */
    private fun compressFileInPlace(file: File) {
        try {
            val bitmap = loadDownsampledBitmap(file, MAX_IMAGE_DIMENSION) ?: return
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            // Sıkıştırma başarısız olursa orijinal dosya olduğu gibi kalır.
        }
    }

    /**
     * Dosyayı bellek dostu bir şekilde (gerekiyorsa örnekleme yaparak) okur,
     * EXIF dönüşünü uygular ve en uzun kenarı maxDim değerine indirir.
     */
    private fun loadDownsampledBitmap(file: File, maxDim: Int): Bitmap? {
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

    private fun addThumbnail(file: File) {
        val imageView = ImageView(this)
        val sizePx = (100 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(sizePx, sizePx)
        params.marginEnd = (8 * resources.displayMetrics.density).toInt()
        imageView.layoutParams = params
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        imageView.setImageBitmap(bitmap)

        thumbnailContainer.addView(imageView)
    }

    private fun rotateIfNeeded(bitmap: Bitmap?, file: File): Bitmap? {
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

    private fun updateStatus() {
        statusText.text = if (photoFiles.isEmpty()) {
            "Henüz fotoğraf eklenmedi."
        } else {
            "${photoFiles.size} fotoğraf hazır."
        }
        createPdfButton.isEnabled = photoFiles.isNotEmpty()
    }

    private fun updatePdfButtons() {
        val hasPdf = lastPdfFile != null
        whatsappButton.isEnabled = hasPdf
        telegramButton.isEnabled = hasPdf
        shareButton.isEnabled = hasPdf
    }

    private fun createPdf() {
        if (photoFiles.isEmpty()) return

        val document = PdfDocument()
        // A4 boyutu, 72 dpi noktasında yaklaşık 595 x 842
        val pageWidth = 595
        val pageHeight = 842

        var pageCount = 0
        for (file in photoFiles) {
            // Fotoğraflar zaten çekilirken/eklenirken küçültülüp sıkıştırıldı,
            // burada tekrar tüm çözünürlüğü okumamak için aynı sınırla açıyoruz.
            val bitmap = loadDownsampledBitmap(file, MAX_IMAGE_DIMENSION) ?: continue

            pageCount++
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageCount).create()
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val scale = minOf(
                pageWidth.toFloat() / bitmap.width,
                pageHeight.toFloat() / bitmap.height
            )
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val left = (pageWidth - scaledWidth) / 2f
            val top = (pageHeight - scaledHeight) / 2f

            val matrix = Matrix()
            matrix.postScale(scale, scale)
            matrix.postTranslate(left, top)
            canvas.drawBitmap(bitmap, matrix, null)

            document.finishPage(page)
            bitmap.recycle()
        }

        if (pageCount == 0) {
            document.close()
            Toast.makeText(this, "PDF oluşturulamadı, fotoğraflar okunamadı.", Toast.LENGTH_LONG).show()
            return
        }

        val pdfDir = File(getExternalFilesDir(null), "pdfs").apply { mkdirs() }
        val pdfFile = File(pdfDir, "Foto2PDF_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf")

        try {
            FileOutputStream(pdfFile).use { out ->
                document.writeTo(out)
            }
            document.close()
            lastPdfFile = pdfFile
            updatePdfButtons()
            Toast.makeText(
                this,
                "PDF oluşturuldu: ${pdfFile.name}. Şimdi paylaşabilirsin.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            document.close()
            Toast.makeText(this, "PDF oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareToPackage(packageName: String, displayName: String) {
        val file = lastPdfFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Önce PDF oluşturman gerekiyor.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${this.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "$displayName telefonunda yüklü değil.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareGeneric() {
        val file = lastPdfFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Önce PDF oluşturman gerekiyor.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "PDF'i paylaş"))
    }
}
