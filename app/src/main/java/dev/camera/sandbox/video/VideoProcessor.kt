package dev.camera.sandbox.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMuxer.OutputFormat
import android.net.Uri
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import grafika.gles.EglCore
import grafika.gles.FullFrameRectLetterbox
import grafika.gles.Texture2dProgram
import grafika.gles.WindowSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer


sealed interface VideoProcessingState {
    data class Processing(val progress: Float, val thumbBitmap: Bitmap?) : VideoProcessingState
    object Done : VideoProcessingState
}

class VideoProcessor(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val inputUri: Uri,
    private val outputFile: File,
    private val withAudio: Boolean
) {

    private companion object {
        private const val ENCODE_MIME = "video/avc"
        private const val ENCODE_WIDTH = 1280
        private const val ENCODE_HEIGHT = 720
        private const val ENCODE_BITRATE = 2_000_000
        private const val ENCODE_MAX_FRAME_RATE = 30
        private const val ENCODE_I_FRAME_INTERVAL = 10

        private const val TAG = "VideoProcessor"
        private const val DEBUG = true

        private val globalRenderFrameLock = Any()

        @SuppressLint("LogNotTimber")
        fun LOGE(th: Throwable, msg: String) {
            if (DEBUG) {
                Log.e(TAG, msg, th)
            }
        }

        @SuppressLint("LogNotTimber")
        fun LOGE(msg: String) {
            if (DEBUG) {
                Log.e(TAG, msg)
            }
        }

        @SuppressLint("LogNotTimber")
        fun LOGD(msg: String) {
            if (DEBUG) {
                Log.d(TAG, msg)
            }
        }
    }

    private val extractor = MediaExtractor()
    private lateinit var decoder: MediaCodec
    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var isMuxerStarted = false

    private var eglCore: EglCore? = null
    private var frameBlit: FullFrameRectLetterbox? = null
    private var encoderSurface: Surface? = null
    private var encoderWindowSurface: WindowSurface? = null
    private var inputTexture: SurfaceTexture? = null
    private var inputTextureId: Int = -1
    private var inputSurface: Surface? = null
    private val tempMatrix = FloatArray(16)

    private var videoRotationDeg: Int = 0

    private val takeSnapshotCallbackLock = Any()
    private var takeSnapshotCallback: ((Bitmap) -> Unit)? = null
    private var framesRendered = 0

    private data class InputBufferEntry(
        val codec: MediaCodec,
        val index: Int,
        val buffer: ByteBuffer
    )

    private data class AudioFrame(
        val buffer: ByteBuffer,
        val info: BufferInfo
    )

    private sealed interface DecodedVideoFrame {
        data class Data(
            val codec: MediaCodec,
            val index: Int,
            val buffer: ByteBuffer,
            val info: BufferInfo
        ) : DecodedVideoFrame

        object Finished : DecodedVideoFrame
    }

    private sealed interface EncodedVideoFrame {
        data class DataFrame(
            val buffer: ByteBuffer,
            val info: BufferInfo
        ) : EncodedVideoFrame {
            override fun toString(): String {
                return "DATA ${info.size} ${info.presentationTimeUs}"
            }
        }

        object EndOfData : EncodedVideoFrame {
            override fun toString(): String {
                return "EOD"
            }
        }
    }

    private val audioFrames = Channel<AudioFrame>(
        Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val decodedVideoFrames = Channel<DecodedVideoFrame>(
        Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val encodedVideoFrames = Channel<EncodedVideoFrame>(
        Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val decoderInputBuffers = Channel<InputBufferEntry>(
        Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val encoderInputBuffers = Channel<InputBufferEntry>(
        Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val newFrameAvailableLock = Channel<Unit>()

    private var inputWidth = 0
    private var inputHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0

    @Volatile
    private var outputVideoFormat: MediaFormat? = null

    init {
        extractor.setDataSource(context, inputUri, null)
    }

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            val buffer = codec.getInputBuffer(index)
                ?: throw IllegalStateException("input buffer is null")

            buffer.clear()

            scope.launch {
                decoderInputBuffers.send(InputBufferEntry(codec, index, buffer))
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: BufferInfo
        ) {
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isFinished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

            if (isConfig) {
                return
            }

            val result = if (!isFinished) {
                val buffer = codec.getOutputBuffer(index)
                    ?: throw IllegalStateException("output buffer is null")
                DecodedVideoFrame.Data(codec, index, buffer, info)
            } else {
                DecodedVideoFrame.Finished
            }
            scope.launch {
                decodedVideoFrames.send(result)
            }
        }

        override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
            LOGE(exception, "DECODER ERROR")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        }
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            val buffer = codec.getInputBuffer(index)
                ?: throw IllegalStateException("input buffer is null")

            buffer.clear()

            LOGD("ENCODER input buffer ready \tid=$index")

            scope.launch {
                encoderInputBuffers.send(InputBufferEntry(codec, index, buffer))
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: BufferInfo
        ) {
            val buffer = codec.getOutputBuffer(index)
                ?: throw IllegalStateException("output buffer is null")

            val frame = when {
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 -> {
                    EncodedVideoFrame.EndOfData
                }

                else -> {
                    LOGD("ENCODER DATA FRAME ${info.presentationTimeUs}")
                    EncodedVideoFrame.DataFrame(
                        buffer = buffer.deepCopyData(),
                        info = info
                    )
                }
            }

            scope.launch {
                encodedVideoFrames.send(frame)
            }

            LOGD("ENCODER frame ready: \tid=$index size=${info.size} ptu=${info.presentationTimeUs}")
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codex: MediaCodec, exception: MediaCodec.CodecException) {
            LOGE(exception, "ENCODER ERROR")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            LOGE("==== CSD captured ===")
            outputVideoFormat = format
        }
    }

    fun release() {
        // TODO
    }

    fun process() = channelFlow<VideoProcessingState> {
        val startTime = System.currentTimeMillis()

        val videoInfo = extractor.pickMediaTrackInfo("video/")
            ?: throw IllegalStateException("Missing video track for '$inputUri'")

        inputWidth = videoInfo.format.getInteger(MediaFormat.KEY_WIDTH)
        inputHeight = videoInfo.format.getInteger(MediaFormat.KEY_HEIGHT)
        outputWidth = ENCODE_WIDTH
        outputHeight = ENCODE_HEIGHT

        val totalDurationUs = videoInfo.format.getLong(MediaFormat.KEY_DURATION)

        videoRotationDeg = videoInfo.format.getRotationDeg()

        extractor.selectTrack(videoInfo.trackIndex)

        val audioInfo = if (withAudio) {
            extractor.pickMediaTrackInfo("audio/")?.also {
                extractor.selectTrack(it.trackIndex)
            } ?: throw IllegalStateException("Missing audio track for '$inputUri'")
        } else {
            null
        }

        var outAudioTrackIndex: Int? = null
        var outVideoTrackIndex: Int? = null
        if (audioInfo != null) {
            outAudioTrackIndex = muxer.addTrack(audioInfo.format)
        }

        val desiredOutputVideoFormat =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outputWidth,
                outputHeight
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, ENCODE_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, ENCODE_MAX_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENCODE_I_FRAME_INTERVAL)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            }

        muxer = MediaMuxer(outputFile.absolutePath, OutputFormat.MUXER_OUTPUT_MPEG_4)
        encoder = MediaCodec.createByCodecName(selectCodec(ENCODE_MIME)!!.name)

        eglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
        encoder.setCallback(encoderCallback)
        encoder.configure(desiredOutputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = encoder.createInputSurface()
        encoderWindowSurface = WindowSurface(eglCore, encoderSurface, false)
        encoderWindowSurface!!.makeCurrent()
        encoder.start()

        frameBlit = FullFrameRectLetterbox(
            Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
        )

        inputTextureId = frameBlit!!.createTextureObject()
        inputTexture = SurfaceTexture(inputTextureId).apply {
            setOnFrameAvailableListener { surfaceTexture ->
                scope.launch(Dispatchers.Main) {
                    if (outputVideoFormat == null) {
                        LOGE("onInputFrameAvailable ${surfaceTexture.timestamp} NO FORMAT")
                        renderVideoFrame()
                    } else {
                        newFrameAvailableLock.send(Unit)
                    }
                }
            }
            setDefaultBufferSize(outputWidth, outputHeight)
        }

        inputSurface = Surface(inputTexture)
        decoder = MediaCodec.createDecoderByType(videoInfo.mime)
        decoder.setCallback(decoderCallback)
        decoder.configure(videoInfo.format, inputSurface, null, 0)
        decoder.start()

        val buffer = ByteBuffer.allocate(0xffffff)

        var videoFramesWritten = 0
        var bytesWritten = 0L
        var videoFramesRead = 0
        var audioFramesRead = 0
        var audioFramesWritten = 0

        var isEncoderFinished = false
        var isDecoderFinished = false

        coroutineScope {
            var thumbnailBitmap: Bitmap? = null
            takeSnapshotCallback = {
                thumbnailBitmap = it
            }

            // Feed decoder
            launch(Dispatchers.IO) {
                while (true) {
                    val dataSize = extractor.readSampleData(buffer, 0)
                    if (dataSize == -1) {
                        LOGD("[EXTRACTOR] COMPLETE")
                        sendFinishToDecoder()
                        break
                    }

                    val trackIndex = extractor.sampleTrackIndex
                    val presentationTimeUs = extractor.sampleTime

                    if (trackIndex == videoInfo.trackIndex) {
                        submitDataToDecoder(buffer, dataSize, presentationTimeUs)
                        ++videoFramesRead
                    } else if (audioInfo != null && outAudioTrackIndex != null && trackIndex == audioInfo.trackIndex) {
                        val audioBufferInfo = BufferInfo().apply {
                            this.presentationTimeUs = presentationTimeUs
                            this.size = dataSize
                        }
                        ++audioFramesRead
                        audioFrames.send(AudioFrame(buffer.deepCopyData(), audioBufferInfo))
                    }

                    extractor.advance()
                }
            }

            // Write audio
            if (withAudio) {
                launch(Dispatchers.IO) {
                    while (!isDecoderFinished) {
                        val audioFrame = audioFrames.tryReceive().getOrNull()
                        if (audioFrame != null && isMuxerStarted && outAudioTrackIndex != null) {
                            muxer.writeSampleData(
                                outAudioTrackIndex,
                                audioFrame.buffer,
                                audioFrame.info
                            )
                            ++audioFramesWritten
                        } else {
                            LOGE("AUDIO DELAY")
                            delay(10)
                        }
                    }
                }
            }

            // Main worker
            launch(Dispatchers.IO) {
                while (!isEncoderFinished) {
                    if (!isDecoderFinished) {
                        var doRender = false
                        when (val decodedFrame = decodedVideoFrames.receive()) {
                            is DecodedVideoFrame.Data -> {
                                LOGD(
                                    "decodedFrame:  data = ${decodedFrame.info.size} " +
                                            "${decodedFrame.info.presentationTimeUs}"
                                )
                                decodedFrame.codec.releaseOutputBuffer(decodedFrame.index, true)
                                doRender = true
                            }

                            DecodedVideoFrame.Finished -> {
                                LOGD("decodedFrame:  finish!")
                                isDecoderFinished = true
                                encoder.signalEndOfInputStream()
                            }
                        }

                        if (outputVideoFormat == null) {
                            LOGD("WAITING RESULT VIDEO FORMAT")
                            delay(100)
                            continue
                        } else if (outVideoTrackIndex == null) {
                            LOGD("[ENCODER] Config frame. Add output video track.")
                            outVideoTrackIndex = muxer.addTrack(outputVideoFormat!!)
                            muxer.setOrientationHint(videoRotationDeg)
                            muxer.start()
                            isMuxerStarted = true
                        }

                        if (doRender) {
                            newFrameAvailableLock.receive()
                            withContext(Dispatchers.Main) {
                                renderVideoFrame()
                            }
                        }
                    }

                    while (true) {
                        val frame = encodedVideoFrames.tryReceive().getOrNull() ?: break
                        LOGE("ENCODED FRAME: $frame")
                        when (frame) {
                            EncodedVideoFrame.EndOfData -> {
                                isEncoderFinished = true
                                LOGD("[ENCODER-MUXER] COMPLETE")
                            }

                            is EncodedVideoFrame.DataFrame -> {
                                val bi = BufferInfo().apply {
                                    presentationTimeUs = frame.info.presentationTimeUs
                                    size = frame.info.size
                                    flags = frame.info.flags
                                    offset = frame.info.offset
                                }
                                val trackIndex = outVideoTrackIndex
                                if (trackIndex != null) {
                                    muxer.writeSampleData(trackIndex, frame.buffer, bi)
                                    LOGD("[ENCODER] -> [MUXER] ${frame.info.size} ${frame.info.offset} ${frame.info.presentationTimeUs}")
                                    bytesWritten += frame.info.size
                                    ++videoFramesWritten

                                    val percent =
                                        frame.info.presentationTimeUs / totalDurationUs.toFloat()
                                    send(VideoProcessingState.Processing(percent, thumbnailBitmap))
                                }
                            }
                        }
                    }
                }
            }
        }

        send(VideoProcessingState.Done)

        encoder.release()
        decoder.release()

        muxer.stop()
        muxer.release()

        LOGD("Processing finished. ${outputFile.absolutePath}")

        LOGD("Bytes written=$bytesWritten")
        LOGD("Video frames read=$videoFramesRead")
        LOGD("Video frames written=$videoFramesWritten")
        LOGD("Audio frames read=$audioFramesRead")
        LOGD("Audio frames written=$audioFramesWritten")

        val time = (System.currentTimeMillis() - startTime) / 1000f
        LOGD("TIME = $time")
    }

    private fun renderVideoFrame() {
        synchronized(globalRenderFrameLock) {
            if (eglCore == null) {
                LOGE("eglCore is not initialized. Skip frame!")
                return
            }

            inputTexture!!.apply {
                updateTexImage()
                getTransformMatrix(tempMatrix)
            }

            encoderWindowSurface!!.makeCurrent()
            GLES20.glViewport(0, 0, outputWidth, outputHeight)

            frameBlit!!.drawFrameY(inputTextureId, tempMatrix, videoRotationDeg, 1f)
            encoderWindowSurface!!.setPresentationTime(inputTexture!!.timestamp)
            encoderWindowSurface!!.swapBuffers()

            if (framesRendered > 0) {
                synchronized(takeSnapshotCallbackLock) {
                    if (takeSnapshotCallback != null) {
                        Thread.sleep(50)
                        val bmp =
                            makeBitmapFromGlPixels(outputWidth, outputHeight, videoRotationDeg)
                        takeSnapshotCallback?.invoke(bmp)
                        takeSnapshotCallback = null
                    }
                }
            }

            ++framesRendered
        }
    }

    private suspend fun sendFinishToDecoder() {
        val entry = decoderInputBuffers.receive()
        entry.codec.queueInputBuffer(entry.index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private suspend fun submitDataToDecoder(data: ByteBuffer, size: Int, presentationTimeUs: Long) {
        val entry = decoderInputBuffers.receive()

        entry.buffer.put(data)
        entry.codec.queueInputBuffer(entry.index, 0, size, presentationTimeUs, 0)
    }
}