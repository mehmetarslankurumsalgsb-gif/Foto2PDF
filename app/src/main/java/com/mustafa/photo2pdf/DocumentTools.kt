package com.mustafa.photo2pdf

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * OCR (yazı tanıma) ve PDF oluşturma işlemleri.
 * Buradaki fonksiyonlar uzun sürer; mutlaka arka planda (Thread içinde) çağrılmalıdır.
 */
object DocumentTools {

    data class PdfOptions(
        val highQuality: Boolean,
        val searchable: Boolean,
        val password: String?
    )

    private const val PAGE_WIDTH = 595f // A4 genişliği (pt)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun recognize(bitmap: Bitmap): Text =
        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))

    // ---------- OCR: fotoğraflardaki yazıyı okuma ----------

    fun readText(files: List<File>, onProgress: (Int) -> Unit): String {
        val result = StringBuilder()
        files.forEachIndexed { index, file ->
            onProgress(index + 1)
            val bitmap = ImageUtils.loadDownsampledBitmap(file, ImageUtils.MAX_IMAGE_DIMENSION)
                ?: return@forEachIndexed
            try {
                val text = recognize(bitmap).text.trim()
                if (text.isNotEmpty()) {
                    if (files.size > 1) result.append("--- Sayfa ").append(index + 1).append(" ---\n")
                    result.append(text).append("\n\n")
                }
            } finally {
                bitmap.recycle()
            }
        }
        return result.toString().trim()
    }

    // ---------- PDF oluşturma (kalite, aranabilir, şifre) ----------

    /** Oluşturulan sayfa sayısını döndürür. 0 ise hiçbir fotoğraf okunamamıştır. */
    fun buildPdf(
        context: Context,
        files: List<File>,
        output: File,
        options: PdfOptions,
        onProgress: (Int) -> Unit
    ): Int {
        PDFBoxResourceLoader.init(context.applicationContext)

        val maxDim = if (options.highQuality) 2200 else 1400
        val jpegQuality = if (options.highQuality) 90 else 60
        var pageCount = 0

        val document = PDDocument()
        try {
            val font: PDFont? = if (options.searchable) loadFont(context, document) else null

            files.forEachIndexed { index, file ->
                onProgress(index + 1)
                val bitmap = ImageUtils.loadDownsampledBitmap(file, maxDim) ?: return@forEachIndexed
                try {
                    val pageHeight = PAGE_WIDTH * bitmap.height / bitmap.width
                    val page = PDPage(PDRectangle(PAGE_WIDTH, pageHeight))
                    document.addPage(page)

                    val bytes = ByteArrayOutputStream().also {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)
                    }.toByteArray()
                    val image = JPEGFactory.createFromStream(document, ByteArrayInputStream(bytes))

                    val stream = PDPageContentStream(document, page)
                    try {
                        stream.drawImage(image, 0f, 0f, PAGE_WIDTH, pageHeight)
                        if (font != null) {
                            val text = try { recognize(bitmap) } catch (e: Exception) { null }
                            if (text != null) {
                                addInvisibleText(stream, font, text, PAGE_WIDTH / bitmap.width, pageHeight)
                            }
                        }
                    } finally {
                        stream.close()
                    }
                    pageCount++
                } finally {
                    bitmap.recycle()
                }
            }

            if (pageCount == 0) return 0

            val password = options.password
            if (!password.isNullOrEmpty()) {
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 128
                document.protect(policy)
            }

            document.save(output)
        } finally {
            document.close()
        }
        return pageCount
    }

    /** Türkçe karakterleri destekleyen yazı tipi; olmazsa standart yazı tipi. */
    private fun loadFont(context: Context, document: PDDocument): PDFont {
        return try {
            val input = context.assets.open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            PDType0Font.load(document, input)
        } catch (e: Exception) {
            PDType1Font.HELVETICA
        }
    }

    /**
     * OCR ile bulunan her satırı, fotoğraftaki yerine görünmez yazı olarak koyar.
     * Böylece PDF'te kelime aranabilir ve yazı seçilip kopyalanabilir.
     */
    private fun addInvisibleText(
        stream: PDPageContentStream,
        font: PDFont,
        text: Text,
        scale: Float,
        pageHeight: Float
    ) {
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val content = makeWritable(font, line.text) ?: continue

                val boxWidth = box.width() * scale
                val boxHeight = box.height() * scale
                if (boxWidth <= 0f || boxHeight <= 0f) continue

                val fontSize = boxHeight * 0.85f
                val textWidth = try {
                    font.getStringWidth(content) / 1000f * fontSize
                } catch (e: Exception) {
                    continue
                }
                if (textWidth <= 0f) continue

                val horizontalScale = boxWidth / textWidth
                val x = box.left * scale
                val y = pageHeight - box.bottom * scale + boxHeight * 0.2f

                stream.beginText()
                stream.setRenderingMode(RenderingMode.NEITHER)
                stream.setFont(font, fontSize)
                stream.setTextMatrix(Matrix(horizontalScale, 0f, 0f, 1f, x, y))
                stream.showText(content)
                stream.endText()
            }
        }
    }

    /** Yazı tipinin yazamayacağı karakterleri temizler. Yazılabilir bir şey kalmazsa null döner. */
    private fun makeWritable(font: PDFont, raw: String): String? {
        val cleaned = raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        if (cleaned.isEmpty()) return null
        if (canWrite(font, cleaned)) return cleaned

        val builder = StringBuilder()
        for (ch in cleaned) {
            val single = ch.toString()
            when {
                canWrite(font, single) -> builder.append(ch)
                canWrite(font, asciiOf(ch)) -> builder.append(asciiOf(ch))
                else -> builder.append(' ')
            }
        }
        val result = builder.toString().trim()
        return if (result.isNotEmpty() && canWrite(font, result)) result else null
    }

    private fun canWrite(font: PDFont, value: String): Boolean {
        return try {
            font.encode(value)
            font.getStringWidth(value)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun asciiOf(ch: Char): String = when (ch) {
        'ç' -> "c"; 'Ç' -> "C"
        'ğ' -> "g"; 'Ğ' -> "G"
        'ı' -> "i"; 'İ' -> "I"
        'ö' -> "o"; 'Ö' -> "O"
        'ş' -> "s"; 'Ş' -> "S"
        'ü' -> "u"; 'Ü' -> "U"
        else -> " "
    }
}
