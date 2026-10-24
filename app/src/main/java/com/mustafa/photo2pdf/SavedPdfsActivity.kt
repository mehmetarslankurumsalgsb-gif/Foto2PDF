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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Telefonun İndirilenler/ArslanPDF klasöründeki PDF'lerin listesi.
 * Dosya yöneticisinden silinen PDF burada da görünmez.
 * Varsayılan sıralama en yeni tarih; butonla isme göre sıralanabilir.
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
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()

        val items = PdfStore.list(this).toMutableList()
        if (items.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE

        when (sortMode) {
            SortMode.DATE -> items.sortByDescending { it.modifiedMillis }
            SortMode.NAME -> items.sortBy { it.name.lowercase(Locale("tr")) }
        }

        val density = resources.displayMetrics.density
        for (item in items) {
            listContainer.addView(buildRow(item), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() })
        }
    }

    private fun buildRow(item: PdfStore.Item): View {
        val density = resources.displayMetrics.density

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@SavedPdfsActivity, R.drawable.bg_thumbnail_card)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { showItemMenu(item) }
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
            text = item.name.removeSuffix(".pdf")
            setTextColor(ContextCompat.getColor(this@SavedPdfsActivity, R.color.app_text_primary))
            textSize = 14f
            maxLines = 1
        }
        textColumn.addView(nameText)

        val dateFormat = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("tr"))
        val dateText = TextView(this).apply {
            text = dateFormat.format(item.modifiedMillis)
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
            setOnClickListener { showItemMenu(item) }
        }
        row.addView(menuBtn)

        return row
    }

    private fun showItemMenu(item: PdfStore.Item) {
        val options = arrayOf("Aç", "Paylaş", "WhatsApp", "Telegram", "Sil")
        AlertDialog.Builder(this)
            .setTitle(item.name.removeSuffix(".pdf"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPdf(item)
                    1 -> shareGeneric(item)
                    2 -> shareToPackage(item, "com.whatsapp", "WhatsApp")
                    3 -> shareToPackage(item, "org.telegram.messenger", "Telegram")
                    4 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun openPdf(item: PdfStore.Item) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Telefonda PDF açabilecek bir uygulama yok.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: PdfStore.Item) {
        AlertDialog.Builder(this)
            .setTitle("Silinsin mi?")
            .setMessage("${item.name.removeSuffix(".pdf")} telefondan kalıcı olarak silinecek.")
            .setPositiveButton("Sil") { _, _ ->
                if (!PdfStore.delete(this, item)) {
                    Toast.makeText(this, "Silinemedi.", Toast.LENGTH_SHORT).show()
                }
                refreshList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun shareGeneric(item: PdfStore.Item) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "PDF'i paylaş"))
    }

    private fun shareToPackage(item: PdfStore.Item, packageNameTarget: String, displayName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, item.uri)
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
