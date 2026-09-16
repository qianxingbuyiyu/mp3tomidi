package com.mp3tomidi.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 音频解码：MediaCodec 原生解码 MP3/FLAC/WAV/M4A → 22050Hz mono float
 */
object AudioDecoder {

    fun decodeToMono22050(context: Context, uri: android.net.Uri, maxSeconds: Double? = null): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) throw RuntimeException("未找到音频轨道")
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

            var maxSamples = Int.MAX_VALUE
            if (maxSeconds != null) {
                maxSamples = (maxSeconds * 22050).roundToInt()
            }

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()

                val pcmChunks = ArrayList<ShortArray>()
                var totalSamples = 0
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var outputDone = false

                while (!outputDone) {
                    if (!sawInputEOS) {
                        val inIdx = codec.dequeueInputBuffer(10000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val sz = extractor.readSampleData(inBuf, 0)
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outIdx >= 0) {
                        if (bufferInfo.size > 0 &&
                            bufferInfo.flags.and(MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = ShortArray(bufferInfo.size / 2)
                            for (i in shorts.indices) shorts[i] = outBuf.short
                            pcmChunks.add(shorts)
                            totalSamples += shorts.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags.and(MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }

                codec.stop()

                val allShorts = ShortArray(totalSamples)
                var pos = 0
                for (chunk in pcmChunks) {
                    System.arraycopy(chunk, 0, allShorts, pos, chunk.size)
                    pos += chunk.size
                }
                return resampleToMono22050(allShorts, sampleRate, channels, maxSamples)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun resampleToMono22050(pcm: ShortArray, sampleRate: Int, channels: Int, maxSamples: Int): FloatArray {
        val nFrames = pcm.size / channels
        if (nFrames == 0) return FloatArray(0)

        val mono = FloatArray(nFrames)
        for (i in 0 until nFrames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += pcm[i * channels + c] / 32768f
            }
            mono[i] = sum / channels
        }

        val ratio = sampleRate.toDouble() / 22050.0
        val targetLen = min((nFrames / ratio).toInt(), maxSamples).coerceAtLeast(0)
        if (ratio == 1.0) {
            return mono.copyOf(min(targetLen, mono.size))
        }
        val out = FloatArray(targetLen)
        for (i in 0 until targetLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = min(i0 + 1, nFrames - 1)
            val frac = srcPos - i0
            out[i] = mono[i0] * (1f - frac.toFloat()) + mono[i1] * frac.toFloat()
        }
        return out
    }
}