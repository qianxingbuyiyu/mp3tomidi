package com.mp3tomidi.app

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * MIDI 文件导出（标准 MIDI 1.0，单轨钢琴）
 */
object MidiExporter {

    private const val TICKS_PER_QUARTER = 480
    private const val DEFAULT_TEMPO_BPM = 120

    fun export(notes: List<NoteProcessor.NoteEvent>, outFile: File, fps: Int = 86) {
        val secPerTick = 60.0 / DEFAULT_TEMPO_BPM / TICKS_PER_QUARTER
        val events = ArrayList<Pair<Long, ByteArray>>()
        for (n in notes) {
            val startTick = (n.startFrame.toDouble() / fps / secPerTick).toLong().coerceAtLeast(0)
            val endTick = (n.endFrame.toDouble() / fps / secPerTick).toLong().coerceAtLeast(startTick + 1)
            val velocity = (n.amplitude * 127).toInt().coerceIn(1, 127)
            events.add(startTick to byteArrayOf(0x90.toByte(), n.pitch.toByte(), velocity.toByte()))
            events.add(endTick to byteArrayOf(0x80.toByte(), n.pitch.toByte(), 0x40))
        }
        events.sortBy { it.first }

        val trackData = ByteArrayOutputStream()
        var lastTick = 0L

        // tempo meta
        trackData.write(writeVarLen(0))
        trackData.write(tempoMetaEvent(DEFAULT_TEMPO_BPM))

        for ((tick, ev) in events) {
            val delta = (tick - lastTick).coerceAtLeast(0)
            trackData.write(writeVarLen(delta))
            trackData.write(ev)
            lastTick = tick
        }
        trackData.write(writeVarLen(0))
        trackData.write(byteArrayOf(0xFF.toByte(), 0x2F.toByte(), 0x00))

        val trackBytes = trackData.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4D, 0x54, 0x68, 0x64))           // MThd
        out.write(int32(6))
        out.write(int16(0))                                       // format 0
        out.write(int16(1))                                       // 1 track
        out.write(int16(TICKS_PER_QUARTER))
        out.write(byteArrayOf(0x4D, 0x54, 0x72, 0x6B))            // MTrk
        out.write(int32(trackBytes.size))
        out.write(trackBytes)
        outFile.writeBytes(out.toByteArray())
    }

    private fun tempoMetaEvent(bpm: Int): ByteArray {
        val usPerQuarter = 60000000 / bpm
        return byteArrayOf(
            0xFF.toByte(), 0x51.toByte(), 0x03,
            ((usPerQuarter shr 16) and 0xFF).toByte(),
            ((usPerQuarter shr 8) and 0xFF).toByte(),
            (usPerQuarter and 0xFF).toByte()
        )
    }

    private fun writeVarLen(value: Long): ByteArray {
        var v = value
        val buf = ByteArrayOutputStream()
        var buffer = (v and 0x7F).toInt()
        while ((v shr 7) > 0) {
            v = v shr 7
            buffer = buffer shl 8
            buffer = buffer or ((v and 0x7F).toInt() or 0x80)
        }
        while (true) {
            buf.write(buffer and 0xFF)
            if ((buffer and 0x80) != 0) buffer = buffer ushr 8 else break
        }
        return buf.toByteArray()
    }

    private fun int16(v: Int): ByteArray =
        byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun int32(v: Int): ByteArray =
        byteArrayOf(
            ((v shr 24) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )
}