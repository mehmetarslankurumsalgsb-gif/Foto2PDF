package com.mustafa.photo2pdf

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val photoFiles = mutableListOf<File>()
    private var lastPdfFile: File? = null

    private lateinit var statusText: TextView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var createPdfButton: Button
    private lateinit var whatsappButton: Button
    private lateinit var telegramButton: Button
    private lateinit var shareButton: Button

    private val captureActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val paths = result.data?.getStringArrayListExtra(CaptureActivity.EXTRA_CAPTURED_PATHS)
                if (!paths.isNullOrEmpty()) {
                    paths.forEach { photoFiles.add(File(it)) }
                    refreshThumbnails()
                    updateStatus()
                }
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openCaptureActivity()
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
                refreshThumbnails()
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
            checkPermissionAndOpenCapture()
        }

        findViewById<Button>(R.id.galleryButton).setOnClickListener {
            pickImagesLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        createPdfButton.setOnClickListener {
            promptForPdfNameAndCreate()
        }

        whatsappButton.setOnClickListener { shareToPackage("com.whatsapp", "WhatsApp") }
        telegramButton.setOnClickListener { shareToPackage("org.telegram.messenger", "Telegram") }
        shareButton.setOnClickListener { shareGeneric() }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            photoFiles.forEach { it.delete() }
            photoFiles.clear()
            lastPdfFile = null
            refreshThumbnails()
            updatePdfButtons()
            updateStatus()
        }

        updatePdfButtons()
        updateStatus()
    }

    // ---------- Fotoğraf çekme (uygulama içi, arka arkaya) ----------

    private fun checkPermissionAndOpenCapture() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> openCaptureActivity()
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCaptureActivity() {
        captureActivityLauncher.launch(Intent(this, CaptureActivity::class.java))
    }

    // ---------- Galeriden ekleme ----------

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

            ImageUtils.compressFileInPlace(destFile)

            if (!destFile.exists() || destFile.length() == 0L) {
                return false
            }

            photoFiles.add(destFile)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Fotoğraf listesi / küçük resimler ----------

    private fun refreshThumbnails() {
        thumbnailContainer.removeAllViews()
        for ((index, file) in photoFiles.withIndex()) {
            thumbnailContainer.addView(buildThumbnailItem(index, file))
        }
    }

    private fun buildThumbnailItem(index: Int, file: File): View {
        val density = resources.displayMetrics.density
        val itemWidth = (108 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(itemWidth, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = (8 * density).toInt()
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((100 * density).toInt(), (64 * density).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, options))
        }
        container.addView(imageView)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row1.addView(smallButton("⟲") { showRotateOptions(file) })
        row1.addView(smallButton("✕") { removePhotoAt(index) })
        container.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val leftBtn = smallButton("◀") { movePhoto(index, index - 1) }
        val rightBtn = smallButton("▶") { movePhoto(index, index + 1) }
        leftBtn.isEnabled = index > 0
        rightBtn.isEnabled = index < photoFiles.size - 1
        row2.addView(leftBtn)
        row2.addView(rightBtn)
        container.addView(row2)

        return container
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(2, 2, 2, 2)
            val density = resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                0,
                (36 * density).toInt(),
                1f
            ).apply {
                marginEnd = (2 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun removePhotoAt(index: Int) {
        if (index !in photoFiles.indices) return
        val file = photoFiles.removeAt(index)
        file.delete()
        refreshThumbnails()
        updateStatus()
    }

    private fun movePhoto(from: Int, to: Int) {
        if (from !in photoFiles.indices || to !in photoFiles.indices) return
        val item = photoFiles.removeAt(from)
        photoFiles.add(to, item)
        refreshThumbnails()
    }

    // ---------- Döndürme ----------

    private fun showRotateOptions(file: File) {
        val options = arrayOf("Sağa Döndür (90°)", "Sola Döndür (90°)", "Ters Çevir (180°)", "Elle Döndür...")
        AlertDialog.Builder(this)
            .setTitle("Döndür")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { ImageUtils.rotateFileBy(file, 90f); refreshThumbnails() }
                    1 -> { ImageUtils.rotateFileBy(file, -90f); refreshThumbnails() }
                    2 -> { ImageUtils.rotateFileBy(file, 180f); refreshThumbnails() }
                    3 -> showManualRotateDialog(file)
                }
            }
            .show()
    }

    private fun showManualRotateDialog(file: File) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val previewImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (220 * density).toInt()
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val degreeText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
        }
        val seekBar = SeekBar(this).apply {
            max = 359
            progress = 0
        }

        dialogLayout.addView(previewImage)
        dialogLayout.addView(degreeText)
        dialogLayout.addView(seekBar)

        fun updatePreview(degrees: Int) {
            degreeText.text = "$degrees°"
            previewImage.setImageBitmap(ImageUtils.previewRotated(file, degrees.toFloat()))
        }
        updatePreview(0)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePreview(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Elle Döndür")
            .setView(dialogLayout)
            .setPositiveButton("Uygula") { _, _ ->
                ImageUtils.rotateFileBy(file, seekBar.progress.toFloat())
                refreshThumbnails()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---------- Durum metni ----------

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

    // ---------- PDF oluşturma ----------

    private fun promptForPdfNameAndCreate() {
        if (photoFiles.isEmpty()) return

        val defaultName = "Foto2PDF_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val input = EditText(this).apply {
            setText(defaultName)
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("PDF adı")
            .setView(container)
            .setPositiveButton("Oluştur") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) name = defaultName
                createPdf(sanitizeFileName(name))
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9ğüşıöçĞÜŞİÖÇ _-]"), "_")
        return cleaned.ifBlank { "Foto2PDF" }
    }

    private fun createPdf(fileNameWithoutExtension: String) {
        if (photoFiles.isEmpty()) return

        val document = PdfDocument()
        // A4 boyutu, 72 dpi noktasında yaklaşık 595 x 842
        val pageWidth = 595
        val pageHeight = 842

        var pageCount = 0
        for (file in photoFiles) {
            val bitmap = ImageUtils.loadDownsampledBitmap(file, ImageUtils.MAX_IMAGE_DIMENSION) ?: continue

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

            val matrix = android.graphics.Matrix()
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
        var pdfFile = File(pdfDir, "$fileNameWithoutExtension.pdf")
        var counter = 1
        while (pdfFile.exists()) {
            pdfFile = File(pdfDir, "${fileNameWithoutExtension}_$counter.pdf")
            counter++
        }

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

    // ---------- Paylaşma ----------

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
