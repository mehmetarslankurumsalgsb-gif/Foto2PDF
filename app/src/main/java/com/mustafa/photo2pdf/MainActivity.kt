package com.mustafa.photo2pdf

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val photoFiles = mutableListOf<File>()
    private var lastPdfFile: File? = null

    private var pendingCropSourceFile: File? = null
    private var pendingCropDestFile: File? = null
    private var pendingStorageAction: (() -> Unit)? = null

    private lateinit var statusText: TextView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var createPdfButton: Button
    private lateinit var ocrButton: Button
    private lateinit var whatsappButton: Button
    private lateinit var telegramButton: Button
    private lateinit var shareButton: Button
    private lateinit var saveToDeviceButton: Button

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
            if (uris.isNotEmpty()) importUris(uris)
        }

    private val documentScannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val uris = scanResult?.pages?.map { it.imageUri } ?: emptyList()
                if (uris.isNotEmpty()) importUris(uris)
            }
        }

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(this, "Kaydetmek için depolama izni vermeniz gerekiyor.", Toast.LENGTH_SHORT).show()
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
        ocrButton = findViewById(R.id.ocrButton)
        whatsappButton = findViewById(R.id.whatsappButton)
        telegramButton = findViewById(R.id.telegramButton)
        shareButton = findViewById(R.id.shareButton)
        saveToDeviceButton = findViewById(R.id.saveToDeviceButton)

        findViewById<Button>(R.id.scanDocumentButton).setOnClickListener {
            startDocumentScan()
        }

        findViewById<Button>(R.id.takePhotoButton).setOnClickListener {
            checkPermissionAndOpenCapture()
        }

        findViewById<Button>(R.id.galleryButton).setOnClickListener {
            pickImagesLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        createPdfButton.setOnClickListener { showPdfOptionsDialog() }
        ocrButton.setOnClickListener { startOcr() }

        whatsappButton.setOnClickListener { shareToPackage("com.whatsapp", "WhatsApp") }
        telegramButton.setOnClickListener { shareToPackage("org.telegram.messenger", "Telegram") }
        shareButton.setOnClickListener { shareGeneric() }
        saveToDeviceButton.setOnClickListener { withStoragePermission { saveToDevice() } }

        findViewById<Button>(R.id.savedPdfsButton).setOnClickListener {
            startActivity(Intent(this, SavedPdfsActivity::class.java))
        }

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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isAlive(): Boolean = !isFinishing && !isDestroyed

    // ---------- Bekleme penceresi ----------

    private fun showProgress(message: String): Pair<AlertDialog, TextView> {
        val pad = dp(20)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(this).apply {
            text = message
            textSize = 15f
            setPadding(pad, 0, 0, 0)
        }
        layout.addView(ProgressBar(this))
        layout.addView(label)
        val dialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
        dialog.show()
        return dialog to label
    }

    // ---------- Otomatik belge tarama (kenar bulma, kesme, düzeltme) ----------

    private fun startDocumentScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                documentScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Belge tarayıcı açılamadı. İlk kullanımda internet gerekebilir; " +
                        "telefonda Google Play Hizmetleri olmalı.",
                    Toast.LENGTH_LONG
                ).show()
            }
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

    // ---------- Galeriden / tarayıcıdan ekleme ----------

    private fun importUris(uris: List<Uri>) {
        val (dialog, label) = showProgress("Fotoğraflar ekleniyor...")
        Thread {
            val added = mutableListOf<File>()
            uris.forEachIndexed { index, uri ->
                runOnUiThread { label.text = "Fotoğraflar ekleniyor... (${index + 1}/${uris.size})" }
                importImageFromUri(uri)?.let { added.add(it) }
            }
            runOnUiThread {
                if (!isAlive()) return@runOnUiThread
                dialog.dismiss()
                photoFiles.addAll(added)
                refreshThumbnails()
                updateStatus()
                if (added.size < uris.size) {
                    Toast.makeText(this, "Bazı fotoğraflar eklenemedi.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Bir görseli uygulamanın kendi klasörüne kopyalar, boyutunu küçültüp
     * sıkıştırır. Başarılıysa oluşan dosyayı döndürür. Arka planda çağrılır.
     */
    private fun importImageFromUri(uri: Uri): File? {
        return try {
            val photosDir = File(getExternalFilesDir(null), "photos").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val destFile = File(photosDir, "IMPORT_${stamp}_${System.nanoTime()}.jpg")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: return null

            ImageUtils.compressFileInPlace(destFile)

            if (!destFile.exists() || destFile.length() == 0L) null else destFile
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Fotoğraf listesi / kartlar (2 sütunlu) ----------

    private fun refreshThumbnails() {
        thumbnailContainer.removeAllViews()
        var i = 0
        while (i < photoFiles.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (i == 0) 0 else dp(12)
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
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (marginEnd) this.marginEnd = dp(6) else this.marginStart = dp(6)
        }
    }

    private fun buildThumbnailCard(index: Int, file: File): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_thumbnail_card)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val imageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
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
                leftMargin = dp(8)
                topMargin = dp(8)
            }
            text = "${index + 1}"
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            textSize = 12f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_badge)
        }
        imageFrame.addView(badge)

        val deleteBtn = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(8)
                topMargin = dp(8)
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
                dp(42)
            ).apply {
                topMargin = dp(8)
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

    // ---------- Fotoğraf düzenleme menüsü ----------

    private fun showItemMenu(index: Int, file: File) {
        val options = arrayOf(
            "Sağa Döndür (90°)",
            "Sola Döndür (90°)",
            "Ters Çevir (180°)",
            "Kırp",
            "Filtre Uygula...",
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
                    4 -> showFilterDialog(file)
                    5 -> showManualRotateDialog(file)
                    6 -> movePhoto(index, index - 1)
                    7 -> movePhoto(index, index + 1)
                }
            }
            .show()
    }

    // ---------- Filtreler ----------

    private fun showFilterDialog(file: File) {
        val filterNames = arrayOf(
            "Belge (beyaz kağıt, koyu yazı, gölgesiz)",
            "Gri tonlama",
            "Renkleri canlandır"
        )
        val filters = arrayOf(
            ImageUtils.Filter.DOCUMENT,
            ImageUtils.Filter.GRAY,
            ImageUtils.Filter.ENHANCE
        )
        var selected = 0

        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val applyAll = CheckBox(this).apply { text = "Tüm fotoğraflara uygula" }
        val note = TextView(this).apply {
            text = "Not: Filtre geri alınamaz."
            textSize = 12f
        }
        extra.addView(applyAll)
        extra.addView(note)

        AlertDialog.Builder(this)
            .setTitle("Filtre seç")
            .setSingleChoiceItems(filterNames, 0) { _, which -> selected = which }
            .setView(extra)
            .setPositiveButton("Uygula") { _, _ ->
                val targets = if (applyAll.isChecked) photoFiles.toList() else listOf(file)
                applyFilterInBackground(targets, filters[selected])
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun applyFilterInBackground(files: List<File>, filter: ImageUtils.Filter) {
        val (dialog, label) = showProgress("Filtre uygulanıyor...")
        Thread {
            var failed = 0
            files.forEachIndexed { index, file ->
                runOnUiThread { label.text = "Filtre uygulanıyor... (${index + 1}/${files.size})" }
                if (!ImageUtils.applyFilter(file, filter)) failed++
            }
            runOnUiThread {
                if (!isAlive()) return@runOnUiThread
                dialog.dismiss()
                refreshThumbnails()
                if (failed > 0) {
                    Toast.makeText(this, "$failed fotoğrafa filtre uygulanamadı.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showManualRotateDialog(file: File) {
        val pad = dp(16)

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val previewImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)
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

    // ---------- Durum ve butonlar ----------

    private fun updateStatus() {
        statusText.text = if (photoFiles.isEmpty()) {
            "Henüz fotoğraf eklenmedi."
        } else {
            "${photoFiles.size} fotoğraf hazır."
        }
        val hasPhotos = photoFiles.isNotEmpty()
        createPdfButton.isEnabled = hasPhotos
        createPdfButton.alpha = if (hasPhotos) 1f else 0.4f
        ocrButton.isEnabled = hasPhotos
        ocrButton.alpha = if (hasPhotos) 1f else 0.4f
    }

    private fun updatePdfButtons() {
        val hasPdf = lastPdfFile != null
        val alpha = if (hasPdf) 1f else 0.4f
        whatsappButton.isEnabled = hasPdf
        telegramButton.isEnabled = hasPdf
        shareButton.isEnabled = hasPdf
        saveToDeviceButton.isEnabled = hasPdf
        whatsappButton.alpha = alpha
        telegramButton.alpha = alpha
        shareButton.alpha = alpha
        saveToDeviceButton.alpha = alpha
    }

    // ---------- OCR: yazıyı okuma ----------

    private fun startOcr() {
        if (photoFiles.isEmpty()) return
        val files = photoFiles.toList()
        val (dialog, label) = showProgress("Yazılar okunuyor...")
        Thread {
            val text: String? = try {
                DocumentTools.readText(files) { page ->
                    runOnUiThread { label.text = "Yazılar okunuyor... ($page/${files.size})" }
                }
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (!isAlive()) return@runOnUiThread
                dialog.dismiss()
                when {
                    text == null -> Toast.makeText(this, "Yazı okunamadı, tekrar deneyin.", Toast.LENGTH_LONG).show()
                    text.isBlank() -> Toast.makeText(this, "Fotoğraflarda yazı bulunamadı.", Toast.LENGTH_LONG).show()
                    else -> showOcrResult(text)
                }
            }
        }.start()
    }

    private fun showOcrResult(text: String) {
        val pad = dp(16)
        val textView = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(textView) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Okunan Yazı")
            .setView(scroll)
            .setPositiveButton("Kopyala", null)
            .setNeutralButton("TXT Kaydet", null)
            .setNegativeButton("Kapat", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Okunan yazı", text))
                Toast.makeText(this, "Yazı kopyalandı.", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                withStoragePermission { saveTextToDevice(text) }
            }
        }
        dialog.show()
    }

    private fun saveTextToDevice(text: String) {
        val stamp = SimpleDateFormat("dd.MM.yyyy HH.mm.ss", Locale("tr")).format(Date())
        val fileName = "Okunan Yazı $stamp.txt"
        try {
            writeToDownloads(fileName, "text/plain") { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "\"$fileName\" İndirilenler klasörüne kaydedildi.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Telefona kaydetme (İndirilenler klasörü) ----------

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // Android 10 ve üzeri MediaStore kullanır, ekstra izin gerekmez.
            action()
        } else {
            pendingStorageAction = action
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun writeToDownloads(fileName: String, mimeType: String, writer: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Dosya oluşturulamadı")
            val out = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Dosya açılamadı")
            out.use { writer(it) }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            FileOutputStream(File(downloadsDir, fileName)).use { writer(it) }
        }
    }

    private fun saveToDevice() {
        val file = lastPdfFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Önce PDF oluşturman gerekiyor.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            writeToDownloads(file.name, "application/pdf") { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            Toast.makeText(this, "PDF, telefonunun İndirilenler klasörüne kaydedildi.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- PDF oluşturma ----------

    private fun showPdfOptionsDialog() {
        if (photoFiles.isEmpty()) return

        val defaultName = "ArslanPDF_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val pad = dp(20)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }

        val nameInput = EditText(this).apply {
            setText(defaultName)
            setSelection(text.length)
            setSingleLine(true)
        }

        val qualityGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val highQuality = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Yüksek kalite (net, dosya büyük)"
        }
        val smallSize = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Küçük boyut (WhatsApp için ideal)"
        }
        qualityGroup.addView(highQuality)
        qualityGroup.addView(smallSize)
        qualityGroup.check(highQuality.id)

        val searchableBox = CheckBox(this).apply {
            text = "Aranabilir PDF (yazılar seçilebilir ve aranabilir)"
        }

        val passwordBox = CheckBox(this).apply { text = "PDF'e şifre koy" }
        val passwordInput = EditText(this).apply {
            hint = "Şifre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            visibility = View.GONE
        }
        passwordBox.setOnCheckedChangeListener { _, checked ->
            passwordInput.visibility = if (checked) View.VISIBLE else View.GONE
        }

        layout.addView(label("Dosya adı"))
        layout.addView(nameInput)
        layout.addView(label("Kalite"))
        layout.addView(qualityGroup)
        layout.addView(label("Seçenekler"))
        layout.addView(searchableBox)
        layout.addView(passwordBox)
        layout.addView(passwordInput)

        val scroll = ScrollView(this).apply { addView(layout) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("PDF Oluştur")
            .setView(scroll)
            .setPositiveButton("Oluştur", null)
            .setNegativeButton("İptal", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordInput.text.toString()
                if (passwordBox.isChecked && password.isEmpty()) {
                    Toast.makeText(this, "Lütfen bir şifre yaz.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                var name = nameInput.text.toString().trim()
                if (name.isEmpty()) name = defaultName
                val options = DocumentTools.PdfOptions(
                    highQuality = qualityGroup.checkedRadioButtonId == highQuality.id,
                    searchable = searchableBox.isChecked,
                    password = if (passwordBox.isChecked) password else null
                )
                dialog.dismiss()
                createPdf(sanitizeFileName(name), options)
            }
        }
        dialog.show()
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9ğüşıöçĞÜŞİÖÇ _-]"), "_")
        return cleaned.ifBlank { "ArslanPDF" }
    }

    private fun createPdf(fileNameWithoutExtension: String, options: DocumentTools.PdfOptions) {
        if (photoFiles.isEmpty()) return

        val files = photoFiles.toList()
        val pdfDir = File(getExternalFilesDir(null), "pdfs").apply { mkdirs() }
        var pdfFile = File(pdfDir, "$fileNameWithoutExtension.pdf")
        var counter = 1
        while (pdfFile.exists()) {
            pdfFile = File(pdfDir, "${fileNameWithoutExtension}_$counter.pdf")
            counter++
        }
        val target = pdfFile

        val baseMessage = if (options.searchable) "PDF hazırlanıyor, yazılar okunuyor..." else "PDF hazırlanıyor..."
        val (dialog, label) = showProgress(baseMessage)

        Thread {
            var pages = 0
            var error: String? = null
            try {
                pages = DocumentTools.buildPdf(applicationContext, files, target, options) { page ->
                    runOnUiThread { label.text = "$baseMessage ($page/${files.size})" }
                }
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                target.delete()
            }
            val finalError = error
            val finalPages = pages
            runOnUiThread {
                if (!isAlive()) return@runOnUiThread
                dialog.dismiss()
                when {
                    finalError != null ->
                        Toast.makeText(this, "PDF oluşturulamadı: $finalError", Toast.LENGTH_LONG).show()
                    finalPages == 0 ->
                        Toast.makeText(this, "PDF oluşturulamadı, fotoğraflar okunamadı.", Toast.LENGTH_LONG).show()
                    else -> {
                        lastPdfFile = target
                        updatePdfButtons()
                        val lock = if (options.password != null) " (şifreli)" else ""
                        Toast.makeText(
                            this,
                            "PDF oluşturuldu$lock: ${target.name}. Şimdi paylaşabilirsin.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
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
