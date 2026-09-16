package com.mp3tomidi.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 转录协调器：解码 → 推理 → 后处理 → 导出 MIDI
 */
class Transcriber(private val context: Context) {

    data class Config(
        val threads: Int = 4,
        val maxSeconds: Double? = null,
        val onsetThreshold: Float = 0.5f,
        val frameThreshold: Float = 0.3f,
        val minNoteLenMs: Float = 127.7f,
        val minFreq: Float? = null,
        val maxFreq: Float? = null,
        val cleanMode: Boolean = true
    )

    data class Result(
        val midiFile: File,
        val notes: List<NoteProcessor.NoteEvent>,
        val durationSec: Double
    )

    private var engine: TranscribeEngine? = null

    suspend fun transcribe(uri: Uri, config: Config, onProgress: (String) -> Unit): Result =
        withContext(Dispatchers.Default) {
            onProgress("正在读取音频")
            val audio = AudioDecoder.decodeToMono22050(context, uri, config.maxSeconds)
            onProgress("音频就绪，AI 转录中")

            val eng = engine ?: TranscribeEngine(
                modelBytes = context.assets.open("nmp.onnx").use { it.readBytes() },
                threads = config.threads
            ).also { it.init(); engine = it }

            val output = eng.predict(audio)
            onProgress("转录完成，整理音符")

            val fps = 86
            var notes = NoteProcessor.outputToNotes(
                frames = output.note,
                onsets = output.onset,
                onsetThresh = config.onsetThreshold,
                frameThresh = config.frameThreshold,
                minNoteLen = (config.minNoteLenMs / 1000f * fps).toInt().coerceAtLeast(1),
                minFreq = config.minFreq,
                maxFreq = config.maxFreq
            )
            notes = NoteProcessor.mergeAdjacentSamePitch(notes)
            if (config.cleanMode) {
                notes = NoteProcessor.simplifyChords(notes, maxSimultaneous = 2)
            }

            val outDir = File(context.getExternalFilesDir(null), "midi")
            outDir.mkdirs()
            val midiFile = File(outDir, "transcription_${System.currentTimeMillis()}.mid")
            MidiExporter.export(notes, midiFile, fps)

            onProgress("完成，共 ${notes.size} 个音符")
            Result(midiFile, notes, audio.size / 22050.0)
        }

    fun close() {
        engine?.close()
        engine = null
    }
}