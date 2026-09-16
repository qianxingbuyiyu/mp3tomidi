package com.mp3tomidi.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * 转录引擎：封装 Basic Pitch ONNX 模型推理
 * 只走 CPU，线程数可调。不碰 NNAPI/NPU。
 */
class TranscribeEngine(
    private val modelBytes: ByteArray,
    private val threads: Int = 4
) : AutoCloseable {

    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession

    companion object {
        const val SAMPLE_RATE = 22050
        const val FFT_HOP = 256
        const val N_FRAMES_PER_WINDOW = 172
        const val N_SAMPLES = 43844
        const val OVERLAP_FRAMES = 30
        const val N_FREQ_NOTES = 88
        const val N_FREQ_CONTOURS = 264

        const val INPUT_NAME = "serving_default_input_2:0"
        const val OUT_NOTE = "StatefulPartitionedCall:1"
        const val OUT_ONSET = "StatefulPartitionedCall:2"
        const val OUT_CONTOUR = "StatefulPartitionedCall:0"
    }

    fun init() {
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(threads)
        session = env.createSession(modelBytes, opts)
    }

    /**
     * 推理一段 22050Hz mono 音频，返回 note/onset/contour 时间帧矩阵
     */
    fun predict(audio: FloatArray): ModelOutput {
        val overlapLen = OVERLAP_FRAMES * FFT_HOP
        val hopSize = N_SAMPLES - overlapLen

        val padded = FloatArray(audio.size + overlapLen / 2)
        System.arraycopy(audio, 0, padded, overlapLen / 2, audio.size)

        val noteChunks = ArrayList<Array<FloatArray>>()
        val onsetChunks = ArrayList<Array<FloatArray>>()
        val contourChunks = ArrayList<Array<FloatArray>>()

        var i = 0
        while (i < padded.size) {
            val window = FloatArray(N_SAMPLES)
            val end = minOf(i + N_SAMPLES, padded.size)
            System.arraycopy(padded, i, window, 0, end - i)
            for (j in (end - i) until N_SAMPLES) window[j] = 0f

            val r = runWindow(window)
            noteChunks.add(r.note)
            onsetChunks.add(r.onset)
            contourChunks.add(r.contour)
            i += hopSize
        }

        val framesPerWindow = N_FRAMES_PER_WINDOW - OVERLAP_FRAMES
        val halfOlap = OVERLAP_FRAMES / 2

        fun concat(chunks: ArrayList<Array<FloatArray>>): Array<FloatArray> {
            val nFreq = chunks[0][0].size
            val total = noteChunks.size * framesPerWindow
            val out = Array(total) { FloatArray(nFreq) }
            var idx = 0
            for (w in noteChunks.indices) {
                val chunk = chunks[w]
                for (f in halfOlap until N_FRAMES_PER_WINDOW - halfOlap) {
                    if (idx < total) { out[idx] = chunk[f]; idx++ }
                }
            }
            return out
        }

        return ModelOutput(
            note = concat(noteChunks),
            onset = concat(onsetChunks),
            contour = concat(contourChunks)
        )
    }

    private fun runWindow(window: FloatArray): ModelOutput {
        val shape = longArrayOf(1, N_SAMPLES.toLong(), 1)
        val buffer = FloatBuffer.wrap(window)
        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
            val result = session.run(mapOf(INPUT_NAME to tensor))
            result.use { res ->
                fun extract2D(key: String): Array<FloatArray> {
                    // res.get(key) 返回 Optional<OnnxValue>，必须 .get() 取出再强转
                    val onnxValue: Any = res.get(key).map { it }.orElseThrow {
                        RuntimeException("模型输出缺失: $key")
                    }
                    val value = (onnxValue as OnnxTensor).value
                    @Suppress("UNCHECKED_CAST")
                    return when (value) {
                        is Array<*> -> {
                            val first = value[0]
                            if (first is Array<*>) {
                                (first as Array<*>).map { (it as FloatArray).clone() }.toTypedArray()
                            } else {
                                (value as Array<FloatArray>).map { it.clone() }.toTypedArray()
                            }
                        }
                        else -> throw RuntimeException("意外的输出类型: $key")
                    }
                }
                return ModelOutput(
                    note = extract2D(OUT_NOTE),
                    onset = extract2D(OUT_ONSET),
                    contour = extract2D(OUT_CONTOUR)
                )
            }
        }
    }

    data class ModelOutput(
        val note: Array<FloatArray>,
        val onset: Array<FloatArray>,
        val contour: Array<FloatArray>
    )

    override fun close() {
        if (::session.isInitialized) session.close()
    }
}