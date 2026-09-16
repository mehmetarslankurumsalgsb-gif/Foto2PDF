package com.mustafa.photo2pdf

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Daha önce oluşturulmuş tüm PDF'lerin listelendiği ekran. Varsayılan
 * olarak en yeni tarihe göre sıralanır; "Tarihe Göre" / "İsme Göre"
 * butonuna basarak sıralama değiştirilebilir. Her satıra dokununca
 * paylaşma veya silme seçenekleri açılır.
 */
class SavedPdfsActivity : AppCompatActivity() {

    private enum class SortMode { DATE, NAME }

    private var sortMode = SortMode.DATE
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var sortButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_pdfs)

        listContainer = findViewById(R.id.savedListContainer)
        emptyText = findViewById(R.id.emptyText)
        sortButton = findViewById(R.id.sortButton)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        sortButton.setOnClickListener {
            sortMode = if (sortMode == SortMode.DATE) SortMode.NAME else SortMode.DATE
            sortButton.text = if (sortMode == SortMode.DATE) "Tarihe Göre" else "İsme Göre"
            refreshList()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun pdfDir(): File = File(getExternalFilesDir(null), "pdfs")

    private fun refreshList() {
        listContainer.removeAllViews()

        val files = pdfDir().listFiles { file -> file.isFile && file.name.endsWith(".pdf") }?.toMutableList()
            ?: mutableListOf()

        if (files.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE

        when (sortMode) {
            SortMode.DATE -> files.sortByDescending { it.lastModified() }
            SortMode.NAME -> files.sortBy { it.name.lowercase(Locale.getDefault()) }
        }

        val density = resources.displayMetrics.density
        for (file in files) {
            listContainer.addView(buildRow(file), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() })
        }
    }

    private fun buildRow(file: File): View {
        val density = resources.displayMetrics.density

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@SavedPdfsActivity, R.drawable.bg_thumbnail_card)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { showItemMenu(file) }
        }

        val icon = TextView(this).apply {
            text = "📄"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (12 * density).toInt() }
        }
        row.addView(icon)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(this).apply {
            text = file.nameWithoutExtension
            setTextColor(ContextCompat.getColor(this@SavedPdfsActivity, R.color.app_text_primary))
            textSize = 14f
            maxLines = 1
        }
        textColumn.addView(nameText)

        val dateFormat = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("tr"))
        val dateText = TextView(this).apply {
            text = dateFormat.format(file.lastModified())
            setTextColor(ContextCompat.getColor(this@SavedPdfsActivity, R.color.app_text_secondary))
            textSize = 12f
        }
        textColumn.addView(dateText)

        row.addView(textColumn)

        val menuBtn = TextView(this).apply {
            text = "⋯"
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@SavedPdfsActivity, R.color.app_text_primary))
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setOnClickListener { showItemMenu(file) }
        }
        row.addView(menuBtn)

        return row
    }

    private fun showItemMenu(file: File) {
        val options = arrayOf("Paylaş", "WhatsApp", "Telegram", "Sil")
        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareGeneric(file)
                    1 -> shareToPackage(file, "com.whatsapp", "WhatsApp")
                    2 -> shareToPackage(file, "org.telegram.messenger", "Telegram")
                    3 -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Silinsin mi?")
            .setMessage("${file.nameWithoutExtension} kalıcı olarak silinecek.")
            .setPositiveButton("Sil") { _, _ ->
                file.delete()
                refreshList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun shareGeneric(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "PDF'i paylaş"))
    }

    private fun shareToPackage(file: File, packageNameTarget: String, displayName: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(packageNameTarget)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "$displayName telefonunda yüklü değil.", Toast.LENGTH_SHORT).show()
        }
    }
}
