package com.mp3tomidi.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var transcriber: Transcriber

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transcriber = Transcriber(this)
        setContent {
            AppTheme {
                MainScreen(transcriber)
            }
        }
    }

    override fun onDestroy() {
        transcriber.close()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(transcriber: Transcriber) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var resultNotes by remember { mutableStateOf<List<NoteProcessor.NoteEvent>>(emptyList()) }
    var resultMidi by remember { mutableStateOf<File?>(null) }
    var duration by remember { mutableStateOf(0.0) }
    var threads by remember { mutableStateOf(4) }
    var cleanMode by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            status = ""
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("MP3 转 MIDI", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(12.dp))

            // 文件选择卡片
            ElevatedCard(
                onClick = { if (!isBusy) filePicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (selectedUri == null) "选择音频文件" else "已选择音频",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "MP3 / FLAC / WAV / M4A",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 设置区
            Text("设置", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("线程数  $threads", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = threads.toFloat(),
                        onValueChange = { threads = it.toInt() },
                        valueRange = 1f..8f,
                        steps = 6
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("干净模式", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = cleanMode,
                            onCheckedChange = { cleanMode = it }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 转录按钮
            Button(
                onClick = {
                    if (selectedUri == null || isBusy) return@Button
                    isBusy = true
                    status = ""
                    resultNotes = emptyList()
                    resultMidi = null
                    scope.launch {
                        try {
                            val cfg = Transcriber.Config(
                                threads = threads,
                                cleanMode = cleanMode
                            )
                            val res = transcriber.transcribe(selectedUri!!, cfg) { msg -> status = msg }
                            resultNotes = res.notes
                            resultMidi = res.midiFile
                            duration = res.durationSec
                        } catch (e: Exception) {
                            status = e.message ?: "出错了"
                        } finally {
                            isBusy = false
                        }
                    }
                },
                enabled = selectedUri != null && !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("转录中")
                } else {
                    Icon(Icons.Default.GraphicEq, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始转录")
                }
            }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 结果区
            if (resultNotes.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("结果", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            Text("音符数", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("${resultNotes.size}", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text("时长", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("%.1f 秒".format(duration), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                        PianoRoll(notes = resultNotes, fps = 86)
                    }
                }

                Spacer(Modifier.height(12.dp))
                resultMidi?.let { midiFile ->
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "audio/midi"
                                putExtra(
                                    android.content.Intent.EXTRA_STREAM,
                                    androidx.core.content.FileProvider.getUriForFile(
                                        ctx, "com.mp3tomidi.app.fileprovider", midiFile
                                    )
                                )
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(
                                android.content.Intent.createChooser(intent, "导出 MIDI")
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("导出 MIDI")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun PianoRoll(notes: List<NoteProcessor.NoteEvent>, fps: Int = 86) {
    if (notes.isEmpty()) return
    val minPitch = notes.minOfOrNull { it.pitch }?.minus(1) ?: 60
    val maxPitch = notes.maxOfOrNull { it.pitch }?.plus(1) ?: 70
    val maxTime = notes.maxOfOrNull { it.endFrame / fps.toFloat() }?.plus(0.5f) ?: 10f
    val pitchRange = (maxPitch - minPitch).coerceAtLeast(1)

    val noteColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // 网格
        for (p in minPitch..maxPitch) {
            val y = size.height - (p - minPitch) / pitchRange.toFloat() * size.height
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        // 音符
        notes.forEach { n ->
            val y = size.height - (n.pitch - minPitch) / pitchRange.toFloat() * size.height
            val x = n.startFrame / fps.toFloat() / maxTime * size.width
            val w = (n.endFrame - n.startFrame) / fps.toFloat() / maxTime * size.width
            if (w > 0.5f) {
                drawLine(
                    color = noteColor,
                    start = Offset(x, y),
                    end = Offset(x + w, y),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // 音高标签
        for (p in minPitch..maxPitch) {
            val y = size.height - (p - minPitch) / pitchRange.toFloat() * size.height
            val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            drawContext.canvas.nativeCanvas.apply {
                drawText(
                    names[p % 12],
                    4.dp.toPx(),
                    y - 3.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = labelColor.copy(alpha = 0.8f).toArgb()
                        textSize = 9.sp.toPx()
                    }
                )
            }
        }
    }
}