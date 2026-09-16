package com.mustafa.photo2pdf

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kullanıcının telefonun kendi kamera uygulamasına gitmeden, uygulama
 * içinden arka arkaya (her seferinde onay istemeden) fotoğraf çekmesini
 * sağlayan ekran. Çekilen her fotoğraf sol alttaki şeritte küçük bir
 * kare olarak sıralanır; her karenin üzerindeki çarpıya basarak o
 * fotoğraf anında silinebilir. "Bitti" butonuna basınca kalan tüm
 * fotoğrafların dosya yollarını MainActivity'ye geri döndürür.
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CAPTURED_PATHS = "captured_paths"
    }

    private var imageCapture: ImageCapture? = null
    private val capturedFiles = mutableListOf<File>()

    private lateinit var countText: TextView
    private lateinit var thumbnailStripContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        val previewView = findViewById<PreviewView>(R.id.previewView)
        countText = findViewById(R.id.countText)
        thumbnailStripContainer = findViewById(R.id.thumbnailStripContainer)
        val shutterButton = findViewById<Button>(R.id.shutterButton)
        val doneButton = findViewById<Button>(R.id.doneButton)

        startCamera(previewView)

        shutterButton.setOnClickListener { takePhoto() }
        doneButton.setOnClickListener { finishCapture() }

        updateCountText()
    }

    override fun onBackPressed() {
        finishCapture()
    }

    private fun startCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                imageCapture = capture

                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, capture)
            } catch (e: Exception) {
                Toast.makeText(this, "Kamera başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val photosDir = File(getExternalFilesDir(null), "photos").apply { mkdirs() }
        val fileName = "PHOTO_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        val file = File(photosDir, fileName)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    ImageUtils.compressFileInPlace(file)
                    capturedFiles.add(file)
                    updateCountText()
                    refreshThumbnailStrip()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@CaptureActivity,
                        "Fotoğraf çekilemedi: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun updateCountText() {
        countText.text = "${capturedFiles.size} fotoğraf çekildi"
    }

    // ---------- Sol alttaki küçük fotoğraf şeridi ----------

    private fun refreshThumbnailStrip() {
        thumbnailStripContainer.removeAllViews()
        for (file in capturedFiles) {
            thumbnailStripContainer.addView(buildThumbnailItem(file))
        }
    }

    private fun buildThumbnailItem(file: File): View {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, options))
            background = ContextCompat.getDrawable(this@CaptureActivity, R.drawable.bg_thumbnail_card)
        }
        frame.addView(imageView)

        val deleteBtn = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = (-4 * density).toInt()
                rightMargin = (-4 * density).toInt()
            }
            text = "✕"
            setTextColor(ContextCompat.getColor(this@CaptureActivity, android.R.color.white))
            textSize = 11f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@CaptureActivity, R.drawable.bg_icon_button)
            setOnClickListener { removeCapturedFile(file) }
        }
        frame.addView(deleteBtn)

        return frame
    }

    private fun removeCapturedFile(file: File) {
        capturedFiles.remove(file)
        file.delete()
        updateCountText()
        refreshThumbnailStrip()
    }

    private fun finishCapture() {
        val paths = ArrayList(capturedFiles.map { it.absolutePath })
        val resultIntent = Intent().putStringArrayListExtra(EXTRA_CAPTURED_PATHS, paths)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
