package de.pdfwerkstatt.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        setContent {
            MaterialTheme {
                PdfWorkbenchScreen()
            }
        }
    }
}

private sealed interface PendingPlacement {
    data class Text(val text: String) : PendingPlacement
    data class Signature(val bitmap: Bitmap) : PendingPlacement
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfWorkbenchScreen(vm: PdfViewModel = viewModel()) {
    var showTextDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var pendingPlacement by remember { mutableStateOf<PendingPlacement?>(null) }
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }

    val openPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.openPdf(uri)
    }

    val mergePdfs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.mergePdfs(uris)
    }

    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val file = pendingSaveFile ?: vm.workingFile
            if (file != null) vm.saveFileAs(file, uri)
        }
        pendingSaveFile = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Werkstatt") }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { openPdf.launch(arrayOf("application/pdf")) }) {
                        Text("Öffnen")
                    }
                    Button(
                        enabled = vm.canUndo && !vm.busy,
                        onClick = vm::undo
                    ) {
                        Text("Rückgängig")
                    }
                    Button(
                        enabled = vm.workingFile != null && !vm.busy,
                        onClick = { showTextDialog = true }
                    ) {
                        Text("Text")
                    }
                    Button(
                        enabled = vm.workingFile != null && !vm.busy,
                        onClick = { showSignatureDialog = true }
                    ) {
                        Text("Unterschrift")
                    }
                    Button(
                        enabled = vm.workingFile != null && !vm.busy,
                        onClick = { mergePdfs.launch(arrayOf("application/pdf")) }
                    ) {
                        Text("Zusammenführen")
                    }
                    Button(
                        enabled = vm.workingFile != null && !vm.busy,
                        onClick = {
                            vm.exportCurrentPage { file ->
                                pendingSaveFile = file
                                savePdf.launch("seite_${vm.pageIndex + 1}.pdf")
                            }
                        }
                    ) {
                        Text("Seite trennen")
                    }
                    Button(
                        enabled = vm.workingFile != null && !vm.busy,
                        onClick = {
                            pendingSaveFile = null
                            savePdf.launch("bearbeitet.pdf")
                        }
                    ) {
                        Text("Speichern")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (vm.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = when {
                    pendingPlacement is PendingPlacement.Text ->
                        "Tippe auf die PDF-Seite, wo der Text eingefügt werden soll."
                    pendingPlacement is PendingPlacement.Signature ->
                        "Tippe auf die PDF-Seite, wo die Unterschrift eingefügt werden soll."
                    else -> vm.status
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )

            if (vm.pageBitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Öffne eine PDF-Datei, um mit der Bearbeitung zu beginnen.")
                }
            } else {
                PdfPage(
                    bitmap = vm.pageBitmap!!,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    onTap = { x, y ->
                        when (val placement = pendingPlacement) {
                            is PendingPlacement.Text -> {
                                vm.addText(placement.text, x, y)
                                pendingPlacement = null
                            }
                            is PendingPlacement.Signature -> {
                                vm.addSignature(placement.bitmap, x, y)
                                pendingPlacement = null
                            }
                            null -> Unit
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        enabled = vm.pageIndex > 0 && !vm.busy,
                        onClick = vm::previousPage
                    ) {
                        Text("←")
                    }
                    Text("Seite ${vm.pageIndex + 1} / ${vm.pageCount}")
                    OutlinedButton(
                        enabled = vm.pageIndex + 1 < vm.pageCount && !vm.busy,
                        onClick = vm::nextPage
                    ) {
                        Text("→")
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        AddTextDialog(
            onDismiss = { showTextDialog = false },
            onConfirm = { text ->
                showTextDialog = false
                pendingPlacement = PendingPlacement.Text(text)
            }
        )
    }

    if (showSignatureDialog) {
        SignatureDialog(
            onDismiss = { showSignatureDialog = false },
            onConfirm = { bitmap ->
                showSignatureDialog = false
                pendingPlacement = PendingPlacement.Signature(bitmap)
            }
        )
    }
}

@Composable
private fun PdfPage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onTap: (Float, Float) -> Unit
) {
    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "PDF-Seite",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .background(Color.White)
                .pointerInput(bitmap) {
                    detectTapGestures { offset ->
                        if (size.width > 0 && size.height > 0) {
                            val x = (offset.x / size.width).coerceIn(0f, 1f)
                            val y = (offset.y / size.height).coerceIn(0f, 1f)
                            onTap(x, y)
                        }
                    }
                }
        )
    }
}

@Composable
private fun AddTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text einfügen") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Text") },
                minLines = 2
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) }
            ) {
                Text("Platzieren")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
private fun SignatureDialog(
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val currentStroke = remember { mutableStateListOf<Offset>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unterschrift zeichnen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mit Finger oder Stift unterschreiben.")
                SignaturePad(
                    strokes = strokes,
                    currentStroke = currentStroke,
                    onSizeChanged = { padSize = it }
                )
                TextButton(
                    onClick = {
                        strokes.clear()
                        currentStroke.clear()
                    }
                ) {
                    Text("Löschen")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = strokes.isNotEmpty() || currentStroke.isNotEmpty(),
                onClick = {
                    val all = strokes.toList() +
                        if (currentStroke.isNotEmpty()) listOf(currentStroke.toList()) else emptyList()

                    val bitmap = signatureBitmap(
                        strokes = all,
                        sourceSize = padSize
                    )
                    onConfirm(bitmap)
                }
            ) {
                Text("Übernehmen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
private fun SignaturePad(
    strokes: SnapshotStateList<List<Offset>>,
    currentStroke: SnapshotStateList<Offset>,
    onSizeChanged: (IntSize) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color.White)
            .onSizeChanged(onSizeChanged)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        currentStroke.clear()
                        currentStroke.add(start)
                    },
                    onDrag = { change, _ ->
                        currentStroke.add(change.position)
                    },
                    onDragEnd = {
                        if (currentStroke.size > 1) {
                            strokes.add(currentStroke.toList())
                        }
                        currentStroke.clear()
                    },
                    onDragCancel = {
                        currentStroke.clear()
                    }
                )
            }
    ) {
        fun drawStroke(points: List<Offset>) {
            if (points.size < 2) return
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (point in points.drop(1)) {
                    lineTo(point.x, point.y)
                }
            }
            drawPath(
                path = path,
                color = Color.Black,
                style = Stroke(width = 5f)
            )
        }

        strokes.forEach(::drawStroke)
        drawStroke(currentStroke)
    }
}

private fun signatureBitmap(
    strokes: List<List<Offset>>,
    sourceSize: IntSize
): Bitmap {
    val width = 1200
    val height = 400
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    if (sourceSize.width <= 0 || sourceSize.height <= 0) return bitmap

    val scaleX = width.toFloat() / sourceSize.width.toFloat()
    val scaleY = height.toFloat() / sourceSize.height.toFloat()

    strokes.forEach { points ->
        if (points.size >= 2) {
            val path = AndroidPath()
            path.moveTo(points.first().x * scaleX, points.first().y * scaleY)
            for (point in points.drop(1)) {
                path.lineTo(point.x * scaleX, point.y * scaleY)
            }
            canvas.drawPath(path, paint)
        }
    }

    return bitmap
}
