package com.mustafa.photo2pdf

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
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
 * sağlayan ekran. "Bitti" butonuna basınca çekilen tüm fotoğrafların
 * dosya yollarını MainActivity'ye geri döndürür.
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CAPTURED_PATHS = "captured_paths"
    }

    private var imageCapture: ImageCapture? = null
    private val capturedPaths = ArrayList<String>()

    private lateinit var countText: TextView
    private lateinit var lastThumbnail: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        val previewView = findViewById<PreviewView>(R.id.previewView)
        countText = findViewById(R.id.countText)
        lastThumbnail = findViewById(R.id.lastThumbnail)
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
                val capture = ImageCapture.Builder().build()
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
                    capturedPaths.add(file.absolutePath)
                    updateCountText()
                    updateLastThumbnail(file)
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
        countText.text = "${capturedPaths.size} fotoğraf çekildi"
    }

    private fun updateLastThumbnail(file: File) {
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            lastThumbnail.setImageBitmap(bitmap)
        } catch (e: Exception) {
            // Önizleme başarısız olsa bile çekilen fotoğraf listede kalır.
        }
    }

    private fun finishCapture() {
        val resultIntent = Intent().putStringArrayListExtra(EXTRA_CAPTURED_PATHS, capturedPaths)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
