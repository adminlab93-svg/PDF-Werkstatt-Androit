package de.pdfwerkstatt.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val resolver get() = context.contentResolver

    var workingFile by mutableStateOf<File?>(null)
        private set

    var pageBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var pageIndex by mutableIntStateOf(0)
        private set

    var pageCount by mutableIntStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf("Bereit.")
        private set

    private val undoStack = ArrayDeque<File>()

    var canUndo by mutableStateOf(false)
        private set

    fun openPdf(uri: Uri) = runTask("PDF wird geöffnet …") {
        val file = newWorkingFile()
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        } ?: error("PDF konnte nicht gelesen werden.")

        clearUndoHistory()
        workingFile = file
        pageIndex = 0
        pageCount = countPages(file)
        pageBitmap = renderPage(file, pageIndex)
        status = "PDF geöffnet."
    }

    fun previousPage() {
        if (pageIndex <= 0 || workingFile == null) return
        pageIndex--
        refreshPage()
    }

    fun nextPage() {
        if (pageIndex + 1 >= pageCount || workingFile == null) return
        pageIndex++
        refreshPage()
    }

    fun addText(text: String, xFraction: Float, yFraction: Float) =
        mutateDocument("Text wird eingefügt …") { document ->
            val page = document.getPage(pageIndex)
            val box = page.mediaBox

            val fontSize = 16f
            val x = (box.width * xFraction).coerceIn(8f, box.width - 20f)
            val y = (box.height * (1f - yFraction)).coerceIn(20f, box.height - 20f)

            val safeText = text
                .replace("\n", " ")
                .replace("\r", " ")
                .take(500)

            PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, fontSize)
                stream.newLineAtOffset(x, y)
                stream.showText(safeText)
                stream.endText()
            }
        }

    fun addSignature(bitmap: Bitmap, xFraction: Float, yFraction: Float) =
        mutateDocument("Unterschrift wird eingefügt …") { document ->
            val page = document.getPage(pageIndex)
            val box = page.mediaBox
            val image = LosslessFactory.createFromImage(document, bitmap)

            val signatureWidth = box.width * 0.34f
            val signatureHeight = signatureWidth *
                (bitmap.height.toFloat() / bitmap.width.toFloat())

            val centerX = box.width * xFraction
            val centerYFromTop = box.height * yFraction

            val x = (centerX - signatureWidth / 2f)
                .coerceIn(0f, box.width - signatureWidth)

            val y = (box.height - centerYFromTop - signatureHeight / 2f)
                .coerceIn(0f, box.height - signatureHeight)

            PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { stream ->
                stream.drawImage(
                    image,
                    x,
                    y,
                    signatureWidth,
                    signatureHeight
                )
            }
        }

    fun mergePdfs(uris: List<Uri>) = runTask("PDFs werden zusammengeführt …") {
        val destination = File(context.cacheDir, "merged_${System.nanoTime()}.pdf")
        val merger = PDFMergerUtility()
        val sourceFiles = mutableListOf<File>()

        workingFile?.let { current ->
            pushUndoSnapshot(current)
            val copy = File(context.cacheDir, "merge_base_${System.nanoTime()}.pdf")
            current.copyTo(copy, overwrite = true)
            sourceFiles += copy
        }

        uris.forEachIndexed { index, uri ->
            val temp = File(context.cacheDir, "merge_${index}_${System.nanoTime()}.pdf")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: error("Eine ausgewählte PDF konnte nicht gelesen werden.")
            sourceFiles += temp
        }

        if (sourceFiles.isEmpty()) error("Keine PDF zum Zusammenführen ausgewählt.")

        sourceFiles.forEach { merger.addSource(it) }
        merger.destinationFileName = destination.absolutePath
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())

        sourceFiles.forEach { it.delete() }

        workingFile = destination
        pageIndex = 0
        pageCount = countPages(destination)
        pageBitmap = renderPage(destination, pageIndex)
        status = "PDFs zusammengeführt."
    }

    fun exportCurrentPage(onReady: (File) -> Unit) {
        val source = workingFile ?: return
        val index = pageIndex

        viewModelScope.launch {
            busy = true
            status = "Seite wird getrennt …"
            try {
                val export = withContext(Dispatchers.IO) {
                    val out = File(context.cacheDir, "seite_${index + 1}_${System.nanoTime()}.pdf")
                    PDDocument.load(source).use { document ->
                        val splitter = Splitter().apply {
                            startPage = index + 1
                            endPage = index + 1
                            splitAtPage = 1
                        }
                        val pages = splitter.split(document)
                        if (pages.isEmpty()) error("Seite konnte nicht exportiert werden.")
                        pages.first().use { single ->
                            single.save(out)
                        }
                    }
                    out
                }
                status = "Seite bereit zum Speichern."
                onReady(export)
            } catch (t: Throwable) {
                status = "Fehler: ${t.message ?: "Unbekannter Fehler"}"
            } finally {
                busy = false
            }
        }
    }

    fun saveFileAs(file: File, uri: Uri) = runTask("PDF wird gespeichert …") {
        resolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Zieldatei konnte nicht geöffnet werden.")
        status = "PDF gespeichert."
    }


    fun undo() {
        if (undoStack.isEmpty() || busy) return

        runTask("Letzte Änderung wird rückgängig gemacht …") {
            val previous = undoStack.removeLast()

            val restored = File(
                context.cacheDir,
                "undo_restored_${System.nanoTime()}.pdf"
            )
            previous.copyTo(restored, overwrite = true)
            previous.delete()

            workingFile = restored
            pageCount = countPages(restored)
            if (pageCount <= 0) {
                pageIndex = 0
                pageBitmap = null
            } else {
                pageIndex = pageIndex.coerceIn(0, pageCount - 1)
                pageBitmap = renderPage(restored, pageIndex)
            }

            canUndo = undoStack.isNotEmpty()
            status = "Letzte Änderung rückgängig gemacht."
        }
    }

    private fun pushUndoSnapshot(source: File) {
        val snapshot = File(
            context.cacheDir,
            "undo_${System.nanoTime()}.pdf"
        )
        source.copyTo(snapshot, overwrite = true)
        undoStack.addLast(snapshot)

        while (undoStack.size > 20) {
            undoStack.removeFirst().delete()
        }

        canUndo = true
    }

    private fun clearUndoHistory() {
        while (undoStack.isNotEmpty()) {
            undoStack.removeFirst().delete()
        }
        canUndo = false
    }

    private fun refreshPage() {
        val file = workingFile ?: return
        val index = pageIndex

        viewModelScope.launch {
            busy = true
            try {
                pageBitmap = withContext(Dispatchers.IO) {
                    renderPage(file, index)
                }
            } catch (t: Throwable) {
                status = "Fehler: ${t.message ?: "Seite konnte nicht geladen werden"}"
            } finally {
                busy = false
            }
        }
    }

    private fun mutateDocument(
        progress: String,
        block: (PDDocument) -> Unit
    ) {
        val source = workingFile ?: return

        runTask(progress) {
            pushUndoSnapshot(source)
            val next = File(context.cacheDir, "edited_${System.nanoTime()}.pdf")

            PDDocument.load(source).use { document ->
                block(document)
                document.save(next)
            }

            workingFile = next
            pageCount = countPages(next)
            pageBitmap = renderPage(next, pageIndex)
            status = "Änderung übernommen."
        }
    }

    private fun runTask(
        progress: String,
        block: suspend () -> Unit
    ) {
        viewModelScope.launch {
            busy = true
            status = progress
            try {
                withContext(Dispatchers.IO) {
                    block()
                }
            } catch (t: Throwable) {
                status = "Fehler: ${t.message ?: "Unbekannter Fehler"}"
            } finally {
                busy = false
            }
        }
    }

    private fun newWorkingFile(): File =
        File(context.cacheDir, "working_${System.nanoTime()}.pdf")

    private fun countPages(file: File): Int {
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        )
        return try {
            PdfRenderer(descriptor).use { renderer ->
                renderer.pageCount
            }
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private fun renderPage(file: File, index: Int): Bitmap {
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        return try {
            PdfRenderer(descriptor).use { renderer ->
                require(index in 0 until renderer.pageCount)

                renderer.openPage(index).use { page ->
                    val targetWidth = 1400
                    val scale = targetWidth.toFloat() / page.width.toFloat()
                    val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

                    Bitmap.createBitmap(
                        targetWidth,
                        targetHeight,
                        Bitmap.Config.ARGB_8888
                    ).also { bitmap ->
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                    }
                }
            }
        } finally {
            runCatching { descriptor.close() }
        }
    }
}
