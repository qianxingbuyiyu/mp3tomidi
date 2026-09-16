package com.mp3tomidi.app

import kotlin.math.*

/**
 * 音符后处理：模型输出 → 音符事件 → 合并去噪
 */
object NoteProcessor {

    data class NoteEvent(
        var startFrame: Int,
        var endFrame: Int,
        var pitch: Int,
        var amplitude: Float
    )

    private const val MIDI_OFFSET = 21
    private const val MAX_FREQ_IDX = 87
    private const val ENERGY_TOLERANCE = 11

    fun outputToNotes(
        frames: Array<FloatArray>,
        onsets: Array<FloatArray>,
        onsetThresh: Float,
        frameThresh: Float,
        minNoteLen: Int,
        minFreq: Float? = null,
        maxFreq: Float? = null
    ): List<NoteEvent> {
        val nFrames = frames.size
        val nFreq = frames[0].size
        if (nFrames == 0 || nFreq == 0) return emptyList()

        // 频率约束
        var minIdx = 0
        var maxIdx = nFreq
        if (minFreq != null) minIdx = ((hzToMidi(minFreq) - MIDI_OFFSET).roundToInt()).coerceIn(0, nFreq)
        if (maxFreq != null) maxIdx = ((hzToMidi(maxFreq) - MIDI_OFFSET).roundToInt()).coerceIn(0, nFreq)

        val onsets2: Array<FloatArray>
        val frames2: Array<FloatArray>
        if (minIdx > 0 || maxIdx < nFreq) {
            onsets2 = constrain(onsets, minIdx, maxIdx)
            frames2 = constrain(frames, minIdx, maxIdx)
        } else {
            onsets2 = onsets
            frames2 = frames
        }

        // 峰值检测
        val peakMat = Array(nFrames) { FloatArray(nFreq) }
        for (f in 1 until nFrames - 1) {
            for (b in 0 until nFreq) {
                val v = onsets2[f][b]
                if (v >= onsetThresh && v >= onsets2[f - 1][b] && v >= onsets2[f + 1][b]) {
                    peakMat[f][b] = v
                }
            }
        }

        val remaining = frames2.map { it.copyOf() }.toTypedArray()
        val notes = ArrayList<NoteEvent>()

        val onsetsList = ArrayList<Pair<Int, Int>>()
        for (f in nFrames - 1 downTo 0) {
            for (b in 0 until nFreq) {
                if (peakMat[f][b] > 0f) onsetsList.add(f to b)
            }
        }

        for ((noteStart, freqIdx) in onsetsList) {
            if (noteStart >= nFrames - 1) continue
            var i = noteStart + 1
            var k = 0
            while (i < nFrames - 1 && k < ENERGY_TOLERANCE) {
                if (remaining[i][freqIdx] < frameThresh) k++ else k = 0
                i++
            }
            i -= k
            if (i - noteStart <= minNoteLen) continue

            for (t in noteStart until i) {
                remaining[t][freqIdx] = 0f
                if (freqIdx < MAX_FREQ_IDX) remaining[t][freqIdx + 1] = 0f
                if (freqIdx > 0) remaining[t][freqIdx - 1] = 0f
            }
            var ampSum = 0f
            for (t in noteStart until i) ampSum += frames2[t][freqIdx]
            notes.add(NoteEvent(noteStart, i, freqIdx + MIDI_OFFSET, ampSum / (i - noteStart)))
        }

        // melodia trick：提取剩余能量中的长音
        while (true) {
            var maxV = 0f
            var maxPos = -1
            for (t in 0 until nFrames) {
                for (b in 0 until nFreq) {
                    if (remaining[t][b] > maxV) { maxV = remaining[t][b]; maxPos = t * nFreq + b }
                }
            }
            if (maxV <= frameThresh) break
            val iMid = maxPos / nFreq
            val freqIdx = maxPos % nFreq
            remaining[iMid][freqIdx] = 0f

            var i = iMid + 1
            var k = 0
            while (i < nFrames - 1 && k < ENERGY_TOLERANCE) {
                if (remaining[i][freqIdx] < frameThresh) k++ else k = 0
                remaining[i][freqIdx] = 0f
                if (freqIdx < MAX_FREQ_IDX) remaining[i][freqIdx + 1] = 0f
                if (freqIdx > 0) remaining[i][freqIdx - 1] = 0f
                i++
            }
            val iEnd = i - 1 - k

            i = iMid - 1
            k = 0
            while (i > 0 && k < ENERGY_TOLERANCE) {
                if (remaining[i][freqIdx] < frameThresh) k++ else k = 0
                remaining[i][freqIdx] = 0f
                if (freqIdx < MAX_FREQ_IDX) remaining[i][freqIdx + 1] = 0f
                if (freqIdx > 0) remaining[i][freqIdx - 1] = 0f
                i--
            }
            val iStart = i + 1 + k

            if (iEnd - iStart <= minNoteLen) continue
            var ampSum = 0f
            for (t in iStart until iEnd) ampSum += frames2[t][freqIdx]
            notes.add(NoteEvent(iStart, iEnd, freqIdx + MIDI_OFFSET, ampSum / (iEnd - iStart)))
        }

        return notes
    }

    private fun constrain(src: Array<FloatArray>, minIdx: Int, maxIdx: Int): Array<FloatArray> =
        src.map { row ->
            val r = row.copyOf()
            for (i in 0 until minIdx) r[i] = 0f
            for (i in maxIdx until r.size) r[i] = 0f
            r
        }.toTypedArray()

    /** 合并同一音高、首尾相接的音符（消除拆音） */
    fun mergeAdjacentSamePitch(notes: List<NoteEvent>, gapFrames: Int = 2): List<NoteEvent> {
        if (notes.isEmpty()) return notes
        val sorted = notes.sortedBy { it.pitch.toLong() * 100000L + it.startFrame }
        val merged = ArrayList<NoteEvent>()
        for (n in sorted) {
            if (merged.isNotEmpty()) {
                val last = merged.last()
                if (last.pitch == n.pitch && n.startFrame - last.endFrame <= gapFrames) {
                    last.endFrame = max(last.endFrame, n.endFrame)
                    last.amplitude = max(last.amplitude, n.amplitude)
                    continue
                }
            }
            merged.add(NoteEvent(n.startFrame, n.endFrame, n.pitch, n.amplitude))
        }
        return merged
    }

    /** 和弦简化：同一时刻最多保留 N 个音 */
    fun simplifyChords(notes: List<NoteEvent>, maxSimultaneous: Int = 2, timeTolFrames: Int = 3): List<NoteEvent> {
        if (notes.isEmpty()) return notes
        val sorted = notes.sortedBy { it.startFrame }
        val clusters = ArrayList<MutableList<NoteEvent>>()
        var cur = ArrayList<NoteEvent>()
        cur.add(sorted[0])
        for (n in sorted.drop(1)) {
            if (n.startFrame - cur.last().startFrame <= timeTolFrames) cur.add(n)
            else { clusters.add(cur); cur = ArrayList(); cur.add(n) }
        }
        clusters.add(cur)

        val out = ArrayList<NoteEvent>()
        for (cl in clusters) {
            if (cl.size <= maxSimultaneous) out.addAll(cl)
            else {
                cl.sortByDescending { it.amplitude }
                out.addAll(cl.take(maxSimultaneous))
            }
        }
        return out
    }

    private fun hzToMidi(hz: Float): Float = 69f + 12f * log2(hz / 440f)
}