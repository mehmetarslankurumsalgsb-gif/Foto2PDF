package com.mustafa.photo2pdf

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    private val photoFiles = mutableListOf<File>()
    private var pendingPhotoFile: File? = null

    private lateinit var statusText: TextView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var createPdfButton: Button

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                pendingPhotoFile?.let { file ->
                    photoFiles.add(file)
                    addThumbnail(file)
                    updateStatus()
                }
            } else {
                pendingPhotoFile?.delete()
                Toast.makeText(this, "Fotoğraf alınamadı.", Toast.LENGTH_SHORT).show()
            }
            pendingPhotoFile = null
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, "Kameraya izin vermeniz gerekiyor.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        thumbnailContainer = findViewById(R.id.thumbnailContainer)
        createPdfButton = findViewById(R.id.createPdfButton)

        findViewById<Button>(R.id.takePhotoButton).setOnClickListener {
            checkPermissionAndLaunchCamera()
        }

        createPdfButton.setOnClickListener {
            createPdf()
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            photoFiles.forEach { it.delete() }
            photoFiles.clear()
            thumbnailContainer.removeAllViews()
            updateStatus()
        }
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
        val fileName = "PHOTO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val file = File(photosDir, fileName)
        pendingPhotoFile = file

        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        takePictureLauncher.launch(uri)
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
        imageView.setImageBitmap(rotateIfNeeded(bitmap, file))

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
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun updateStatus() {
        statusText.text = if (photoFiles.isEmpty()) {
            "Henüz fotoğraf çekilmedi."
        } else {
            "${photoFiles.size} fotoğraf hazır."
        }
        createPdfButton.isEnabled = photoFiles.isNotEmpty()
    }

    private fun createPdf() {
        if (photoFiles.isEmpty()) return

        val document = PdfDocument()
        // A4 boyutu, 72 dpi noktasında yaklaşık 595 x 842
        val pageWidth = 595
        val pageHeight = 842

        for ((index, file) in photoFiles.withIndex()) {
            val exifOptions = BitmapFactory.Options()
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, exifOptions)
            bitmap = rotateIfNeeded(bitmap, file) ?: continue

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
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
        }

        val pdfDir = File(getExternalFilesDir(null), "pdfs").apply { mkdirs() }
        val pdfFile = File(pdfDir, "Foto2PDF_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf")

        try {
            FileOutputStream(pdfFile).use { out ->
                document.writeTo(out)
            }
            document.close()
            Toast.makeText(this, "PDF oluşturuldu: ${pdfFile.name}", Toast.LENGTH_LONG).show()
            sharePdf(pdfFile)
        } catch (e: Exception) {
            Toast.makeText(this, "PDF oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(shareIntent, "PDF'i paylaş"))
    }
}
