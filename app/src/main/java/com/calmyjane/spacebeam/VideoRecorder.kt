package com.calmyjane.spacebeam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import java.io.File

class VideoRecorder(private val context: Context, val rawWidth: Int, val rawHeight: Int, val file: File) {

    private var muxer: MediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var muxerStarted = false

    private var videoEncoder: MediaCodec
    val inputSurface: Surface
    private var videoTrackIndex = -1

    val isPortrait: Boolean = rawWidth < rawHeight
    val width: Int = if (isPortrait) 1080 else 1920
    val height: Int = if (isPortrait) 1920 else 1080

    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrackIndex = -1
    private var audioThread: Thread? = null
    @Volatile private var isRecording = true // read by the audio thread

    private val sampleRate = 44100
    private val channelCount = 1
    private val audioBitRate = 128000

    init {
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Increased Bitrate for high-detail kaleidoscope movement
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            // Optimization: Use Main Profile for better compression/quality ratio
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            videoEncoder.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()
        } catch (e: Exception) {
            videoEncoder.release(); muxer.release()
            throw e
        }

        setupAudio()
    }

    private fun setupAudio() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = minBufferSize * 4

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }

                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    audioEncoder?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    audioEncoder?.start()

                    isRecording = true
                    audioRecord?.startRecording()
                    audioThread = Thread { audioLoop() }
                    audioThread?.start()
                }
            }
        } catch (e: Exception) {
            Log.e("VideoRecorder", "Audio setup failed", e)
        }
    }

    fun drain(endOfStream: Boolean) {
        if (endOfStream) {
            try { videoEncoder.signalEndOfInputStream() } catch (e: Exception) { Log.w("SpaceBeam", "signalEndOfInputStream failed", e) }
            // Wait for the frames still inside the encoder, otherwise the last ones are lost
            drainEncoder(videoEncoder, isVideo = true, timeoutUs = 10_000L, untilEos = true)
        } else {
            // Use 0 timeout for video to ensure we don't block the GL thread
            drainEncoder(videoEncoder, isVideo = true, timeoutUs = 0L)
        }
    }

    private fun audioLoop() {
        val buffer = ByteArray(2048)
        var totalBytesRead = 0L

        while (isRecording && audioEncoder != null && audioRecord != null) {
            val readBytes = audioRecord!!.read(buffer, 0, buffer.size)
            if (readBytes > 0) {
                totalBytesRead += readBytes
                try {
                    val inputBufferIndex = audioEncoder!!.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = audioEncoder!!.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(buffer, 0, readBytes)
                        val pts = (totalBytesRead * 1_000_000L) / (sampleRate * 2)
                        audioEncoder!!.queueInputBuffer(inputBufferIndex, 0, readBytes, pts, 0)
                    }
                    drainEncoder(audioEncoder!!, isVideo = false, timeoutUs = 10000L)
                } catch (e: Exception) { Log.w("SpaceBeam", "Audio encode error", e) }
            }
        }
    }

    private val videoBufferInfo = MediaCodec.BufferInfo()
    private val audioBufferInfo = MediaCodec.BufferInfo()

    // Video written before the first keyframe is undecodable (green smear at the start)
    private var sawVideoKeyframe = false

    private fun drainEncoder(encoder: MediaCodec, isVideo: Boolean, timeoutUs: Long, untilEos: Boolean = false) {
        val bufferInfo = if (isVideo) videoBufferInfo else audioBufferInfo
        val deadline = System.nanoTime() + 1_000_000_000L // untilEos: give up after 1 s

        while (true) {
            val idx = try { encoder.dequeueOutputBuffer(bufferInfo, timeoutUs) } catch (e: Exception) { Log.w("SpaceBeam", "dequeueOutputBuffer failed", e); MediaCodec.INFO_TRY_AGAIN_LATER }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (untilEos && System.nanoTime() < deadline) continue
                break
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(this) {
                    if (!muxerStarted) {
                        val newFormat = encoder.outputFormat
                        if (isVideo) videoTrackIndex = muxer.addTrack(newFormat)
                        else audioTrackIndex = muxer.addTrack(newFormat)
                        startMuxerIfReady()
                    }
                }
            } else if (idx >= 0) {
                val encodedData = encoder.getOutputBuffer(idx) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0

                if (bufferInfo.size != 0) {
                    synchronized(this) {
                        if (isVideo && muxerStarted && !sawVideoKeyframe) {
                            sawVideoKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        }
                        if (muxerStarted && (!isVideo || sawVideoKeyframe)) {
                            val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                            if (trackIndex >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(idx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun startMuxerIfReady() {
        val audioReady = (audioEncoder == null) || (audioTrackIndex >= 0)
        val videoReady = (videoTrackIndex >= 0)
        if (videoReady && audioReady && !muxerStarted) {
            muxer.start()
            muxerStarted = true
            // Frames encoded while waiting for the audio track were dropped (incl. the first keyframe): ask for a new one
            try { videoEncoder.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (e: Exception) {}
        }
    }

    /** Stops the audio thread and frees everything; each step on its own so one failure can't keep the mic open.
     *  Returns true if the MP4 was finalized (playable). */
    fun release(): Boolean {
        isRecording = false
        step("audioRecord.stop") { audioRecord?.stop() } // also unblocks a pending read()
        try { audioThread?.join(1000) } catch (e: Exception) {}
        var finalized = false
        // Under the muxer lock: if join() timed out the audio thread may still be writing samples
        step("muxer.stop") { synchronized(this) { if (muxerStarted) { muxerStarted = false; muxer.stop(); finalized = true } } }
        step("muxer.release") { muxer.release() }
        step("videoEncoder.stop") { videoEncoder.stop() }
        step("videoEncoder.release") { videoEncoder.release() }
        step("inputSurface.release") { inputSurface.release() }
        step("audioEncoder.stop") { audioEncoder?.stop() }
        step("audioEncoder.release") { audioEncoder?.release() }
        step("audioRecord.release") { audioRecord?.release() }
        return finalized
    }

    private inline fun step(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w("SpaceBeam", "VideoRecorder $what failed", e) }
    }
}
