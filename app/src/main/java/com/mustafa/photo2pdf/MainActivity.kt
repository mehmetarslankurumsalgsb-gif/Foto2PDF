package com.mustafa.photo2pdf

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val photoFiles = mutableListOf<File>()
    private var lastPdfFile: File? = null

    private var pendingCropSourceFile: File? = null
    private var pendingCropDestFile: File? = null

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

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val src = pendingCropSourceFile
            val dest = pendingCropDestFile
            pendingCropSourceFile = null
            pendingCropDestFile = null

            if (result.resultCode == RESULT_OK && src != null && dest != null && dest.exists()) {
                ImageUtils.compressFileInPlace(dest)
                val idx = photoFiles.indexOf(src)
                if (idx >= 0) {
                    photoFiles[idx] = dest
                } else {
                    photoFiles.add(dest)
                }
                src.delete()
                refreshThumbnails()
            } else {
                dest?.delete()
                if (result.resultCode != RESULT_CANCELED) {
                    Toast.makeText(this, "Kırpma tamamlanamadı.", Toast.LENGTH_SHORT).show()
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

    // ---------- Fotoğraf listesi / kartlar (2 sütunlu) ----------

    private fun refreshThumbnails() {
        thumbnailContainer.removeAllViews()
        val density = resources.displayMetrics.density
        var i = 0
        while (i < photoFiles.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (i == 0) 0 else (12 * density).toInt()
                }
            }

            row.addView(buildThumbnailCard(i, photoFiles[i]), rowCardParams(marginEnd = true))

            if (i + 1 < photoFiles.size) {
                row.addView(buildThumbnailCard(i + 1, photoFiles[i + 1]), rowCardParams(marginEnd = false))
            } else {
                // Tek sayıda fotoğraf varsa hizalamayı korumak için boş alan bırak.
                row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            }

            thumbnailContainer.addView(row)
            i += 2
        }
    }

    private fun rowCardParams(marginEnd: Boolean): LinearLayout.LayoutParams {
        val density = resources.displayMetrics.density
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (marginEnd) this.marginEnd = (6 * density).toInt() else this.marginStart = (6 * density).toInt()
        }
    }

    private fun buildThumbnailCard(index: Int, file: File): View {
        val density = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_thumbnail_card)
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val imageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (190 * density).toInt()
            )
        }

        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, options))
        }
        imageFrame.addView(imageView)

        val badge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = (8 * density).toInt()
                topMargin = (8 * density).toInt()
            }
            text = "${index + 1}"
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            textSize = 12f
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_badge)
        }
        imageFrame.addView(badge)

        val deleteBtn = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (30 * density).toInt(),
                (30 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                rightMargin = (8 * density).toInt()
                topMargin = (8 * density).toInt()
            }
            text = "✕"
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            textSize = 15f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button)
            setOnClickListener { removePhotoAt(index) }
        }
        imageFrame.addView(deleteBtn)

        // Basılı tutup sürükleyerek sıra değiştirme
        imageView.setOnLongClickListener {
            val clipData = ClipData.newPlainText("photo_index", index.toString())
            val shadow = View.DragShadowBuilder(card)
            card.startDragAndDrop(clipData, shadow, null, 0)
            true
        }
        card.setOnDragListener(cardDragListener(index))

        card.addView(imageFrame)

        val editButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            ).apply {
                topMargin = (8 * density).toInt()
            }
            text = "⋯ Düzenle"
            textSize = 13f
            setAllCaps(false)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_secondary)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.app_text_primary))
            setOnClickListener { showItemMenu(index, file) }
        }
        card.addView(editButton)

        return card
    }

    private fun cardDragListener(targetIndex: Int): View.OnDragListener {
        return View.OnDragListener { view, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    view.alpha = 0.6f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    view.alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    view.alpha = 1f
                    val sourceIndex = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull()
                    if (sourceIndex != null && sourceIndex != targetIndex) {
                        movePhoto(sourceIndex, targetIndex)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    view.alpha = 1f
                    true
                }
                else -> true
            }
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

    // ---------- Fotoğraf düzenleme menüsü (döndür / kırp / taşı) ----------

    private fun showItemMenu(index: Int, file: File) {
        val options = arrayOf(
            "Sağa Döndür (90°)",
            "Sola Döndür (90°)",
            "Ters Çevir (180°)",
            "Kırp",
            "Elle Döndür...",
            "Sola Taşı",
            "Sağa Taşı"
        )
        AlertDialog.Builder(this)
            .setTitle("${index + 1}. fotoğraf")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { ImageUtils.rotateFileBy(file, 90f); refreshThumbnails() }
                    1 -> { ImageUtils.rotateFileBy(file, -90f); refreshThumbnails() }
                    2 -> { ImageUtils.rotateFileBy(file, 180f); refreshThumbnails() }
                    3 -> startCrop(file)
                    4 -> showManualRotateDialog(file)
                    5 -> movePhoto(index, index - 1)
                    6 -> movePhoto(index, index + 1)
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

    // ---------- Kırpma (uCrop) ----------

    private fun startCrop(file: File) {
        val destFile = File(file.parentFile, "CROP_${System.currentTimeMillis()}_${file.name}")
        pendingCropSourceFile = file
        pendingCropDestFile = destFile

        val sourceUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val destUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)

        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setFreeStyleCropEnabled(true)
            setToolbarTitle("Kırp")
            setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.app_background))
            setStatusBarColor(ContextCompat.getColor(this@MainActivity, R.color.app_background))
            setToolbarWidgetColor(ContextCompat.getColor(this@MainActivity, R.color.app_text_primary))
            setActiveControlsWidgetColor(ContextCompat.getColor(this@MainActivity, R.color.app_primary))
        }

        val cropIntent = UCrop.of(sourceUri, destUri)
            .withOptions(options)
            .getIntent(this)
        cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        cropLauncher.launch(cropIntent)
    }

    // ---------- Durum metni ----------

    private fun updateStatus() {
        statusText.text = if (photoFiles.isEmpty()) {
            "Henüz fotoğraf eklenmedi."
        } else {
            "${photoFiles.size} fotoğraf hazır."
        }
        createPdfButton.isEnabled = photoFiles.isNotEmpty()
        createPdfButton.alpha = if (createPdfButton.isEnabled) 1f else 0.4f
    }

    private fun updatePdfButtons() {
        val hasPdf = lastPdfFile != null
        val alpha = if (hasPdf) 1f else 0.4f
        whatsappButton.isEnabled = hasPdf
        telegramButton.isEnabled = hasPdf
        shareButton.isEnabled = hasPdf
        whatsappButton.alpha = alpha
        telegramButton.alpha = alpha
        shareButton.alpha = alpha
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
