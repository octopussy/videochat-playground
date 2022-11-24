package dev.video.sandbox.video

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
import android.view.Surface
import grafika.gles.EglCore
import grafika.gles.FullFrameRectLetterbox
import grafika.gles.Texture2dProgram
import grafika.gles.WindowSurface
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger


data class VideoProcessingState(val progress: Float, val thumbBitmap: Bitmap?)

class VideoProcessor(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val inputUri: Uri? = null,
    private val inputFile: File? = null,
    private val outputFile: File,
    private val withAudio: Boolean
) {

    private companion object {
        private const val ENCODE_MIME = "video/avc"
        private const val ENCODE_WIDTH = 1280
        private const val ENCODE_HEIGHT = 720
        private const val ENCODE_BITRATE = 2_000_000
        private const val ENCODE_MAX_FRAME_RATE = 30
        private const val ENCODE_I_FRAME_INTERVAL = 2

        private const val TAG = "VideoProcessor"
        private const val DEBUG = true

        fun LOGE(th: Throwable, msg: String) {
            if (DEBUG) {
                Timber.tag(TAG).e(th, msg)
            }
        }

        fun LOGE(msg: String) {
            if (DEBUG) {
                Timber.tag(TAG).e(msg)
            }
        }

        fun LOGD(msg: String) {
            if (DEBUG) {
                Timber.tag(TAG).d(msg)
            }
        }
    }

    private val primaryDispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    private val extractor = MediaExtractor()
    private lateinit var decoder: MediaCodec
    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var isExtractorStarted = false
    private var isMuxerStarted = false
    private var isEncoderStarted = false
    private var isDecoderStarted = false

    private var eglCore: EglCore? = null
    private var frameBlit: FullFrameRectLetterbox? = null
    private var encoderSurface: Surface? = null
    private var encoderWindowSurface: WindowSurface? = null
    private var inputTexture: SurfaceTexture? = null
    private var inputTextureId: Int = -1
    private var inputSurface: Surface? = null
    private val tempMatrix = FloatArray(16)

    private var inputVideoRotationDeg: Int = 0

    private val takeSnapshotCallbackLock = Any()
    private var takeSnapshotCallback: ((Bitmap) -> Unit)? = null
    private var framesRendered = 0
    private var lastVideoFramePTU = 0L

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

    private var occurredError: Throwable? = null

    private val newFrameAvailableLock = Channel<Unit>()
    private val newDryFrameAvailableLock = Channel<Unit>()

    private var inputWidth = 0
    private var inputHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0

    @Volatile
    private var outputVideoFormat: MediaFormat? = null

    init {
        if (context != null && inputUri != null) {
            extractor.setDataSource(context, inputUri, null)
        } else {
            extractor.setDataSource(inputFile!!.absolutePath)
        }

        isExtractorStarted = true
    }

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!isDecoderStarted) return
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
            if (!isDecoderStarted) return
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
            occurredError = exception
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        }
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!isEncoderStarted) return
            val buffer = codec.getInputBuffer(index)
                ?: throw IllegalStateException("input buffer is null")

            buffer.clear()

            scope.launch {
                encoderInputBuffers.send(InputBufferEntry(codec, index, buffer))
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: BufferInfo
        ) {
            if (!isEncoderStarted) return
            val buffer = codec.getOutputBuffer(index)
                ?: throw IllegalStateException("output buffer is null")

            val frame = when {
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 -> {
                    EncodedVideoFrame.EndOfData
                }

                else -> {
                    EncodedVideoFrame.DataFrame(
                        buffer = buffer.deepCopyData(),
                        info = info
                    )
                }
            }

            scope.launch {
                encodedVideoFrames.send(frame)
            }

            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codex: MediaCodec, exception: MediaCodec.CodecException) {
            LOGE(exception, "ENCODER ERROR")
            occurredError = exception
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            LOGE("==== CSD captured ===")
            outputVideoFormat = format
        }
    }

    fun release() {
        LOGD("Releasing")
        if (::encoder.isInitialized && isEncoderStarted) {
            encoder.stop()
            encoder.release()
            isEncoderStarted = false
        }
        if (::decoder.isInitialized && isDecoderStarted) {
            decoder.stop()
            decoder.release()
            isDecoderStarted = false
        }

        if (isExtractorStarted) {
            extractor.release()
            isExtractorStarted = false
        }

        encoderWindowSurface?.release()
        encoderWindowSurface = null
        inputTexture?.release()
        inputTexture = null
        eglCore?.release()
        eglCore = null
    }

    fun process() = channelFlow {
        val startTime = System.currentTimeMillis()

        val videoInfo = extractor.pickMediaTrackInfo("video/")
            ?: throw IllegalStateException("Missing video track for '$inputUri'")

        val totalDurationUs = videoInfo.format.getLong(MediaFormat.KEY_DURATION)

        inputWidth = videoInfo.format.getInteger(MediaFormat.KEY_WIDTH)
        inputHeight = videoInfo.format.getInteger(MediaFormat.KEY_HEIGHT)
        inputVideoRotationDeg = videoInfo.format.getRotationDeg()

        calcOutputVideoParams()

        extractor.selectTrack(videoInfo.trackIndex)

        val audioInfo = if (withAudio) {
            extractor.pickMediaTrackInfo("audio/")?.also {
                extractor.selectTrack(it.trackIndex)
            }
        } else {
            null
        }

        var outAudioTrackIndex: Int? = null
        var outVideoTrackIndex: Int? = null

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
        encoder.configure(
            desiredOutputVideoFormat,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        encoderSurface = encoder.createInputSurface()
        encoderWindowSurface = WindowSurface(eglCore, encoderSurface, false)
        encoderWindowSurface!!.makeCurrent()
        encoder.start()
        isEncoderStarted = true

        frameBlit = FullFrameRectLetterbox(
            Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
        )

        inputTextureId = frameBlit!!.createTextureObject()
        inputTexture = SurfaceTexture(inputTextureId).apply {
            setOnFrameAvailableListener { surfaceTexture ->
                scope.launch(Dispatchers.Main) {
                    if (outputVideoFormat == null) {
                        LOGD("onInputFrameAvailable ${surfaceTexture.timestamp} NO FORMAT")
                        newDryFrameAvailableLock.send(Unit)
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
        isDecoderStarted = true

        val extractorBuffer = ByteBuffer.allocate(0xffffff)

        var videoFramesWritten = 0
        var bytesWritten = 0L
        var videoFramesRead = 0
        val audioFramesRead = AtomicInteger(0)
        val audioFramesWritten = AtomicInteger(0)

        var isEncoderFinished = false
        var isDecoderFinished = false

        val handler = CoroutineExceptionHandler { _, exception ->
            Timber.e("CoroutineExceptionHandler got $exception")
            occurredError = exception
        }

        val workerScope = CoroutineScope(Job() + handler)
        val workerJob = workerScope.launch {
            var thumbnailBitmap: Bitmap? = null
            takeSnapshotCallback = {
                thumbnailBitmap = it
            }

            // Feed decoder
            launch(Dispatchers.IO) {
                while (true) {
                    val dataSize = extractor.readSampleData(extractorBuffer, 0)
                    if (dataSize == -1) {
                        LOGD("[EXTRACTOR] COMPLETE")
                        sendFinishToDecoder()
                        break
                    }

                    val trackIndex = extractor.sampleTrackIndex
                    val presentationTimeUs = extractor.sampleTime

                    if (trackIndex == videoInfo.trackIndex) {
                        submitDataToDecoder(extractorBuffer, dataSize, presentationTimeUs)
                        ++videoFramesRead
                    } else if (trackIndex == audioInfo?.trackIndex) {
                        val audioBufferInfo = BufferInfo().apply {
                            this.presentationTimeUs = presentationTimeUs
                            this.size = dataSize
                        }
                        audioFramesRead.incrementAndGet()
                        audioFrames.send(
                            AudioFrame(
                                extractorBuffer.deepCopyData(),
                                audioBufferInfo
                            )
                        )
                    }

                    extractor.advance()
                }
            }

            // Main worker
            launch(Dispatchers.IO) {
                while (!isEncoderFinished) {
                    if (!isDecoderFinished) {
                        var awaitingDecodedVideoFrame = false
                        when (val decodedFrame = decodedVideoFrames.receive()) {
                            is DecodedVideoFrame.Data -> {
                                LOGD(
                                    "decodedFrame:  data = ${decodedFrame.info.size} " +
                                            "${decodedFrame.info.presentationTimeUs}"
                                )
                                // send it to renderer, awaiting via newFrameAvailableLock
                                decodedFrame.codec.releaseOutputBuffer(decodedFrame.index, true)
                                awaitingDecodedVideoFrame = true
                            }

                            DecodedVideoFrame.Finished -> {
                                LOGD("decodedFrame:  finish!")
                                isDecoderFinished = true
                                encoder.signalEndOfInputStream()
                            }
                        }

                        if (outputVideoFormat == null) {
                            LOGD("WAITING RESULT VIDEO FORMAT")
                            newDryFrameAvailableLock.receive()
                            withContext(primaryDispatcher) {
                                renderDecodedVideoFrameToEncoder()
                            }
                            continue
                        } else if (outputVideoFormat != null && outVideoTrackIndex == null) {
                            LOGD("[ENCODER] Config frame. Add output video track.")
                            outVideoTrackIndex = muxer.addTrack(outputVideoFormat!!)
                            if (audioInfo != null) {
                                outAudioTrackIndex = muxer.addTrack(audioInfo.format)
                            }

                            //muxer.setOrientationHint(inputVideoRotationDeg)
                            muxer.start()
                            isMuxerStarted = true
                        }

                        if (awaitingDecodedVideoFrame) {
                            newFrameAvailableLock.receive()
                            withContext(primaryDispatcher) {
                                renderDecodedVideoFrameToEncoder()
                            }
                        }

                        if (isMuxerStarted && outAudioTrackIndex != null) {
                            var audioFrame = audioFrames.tryReceive().getOrNull()
                            while (audioFrame != null) {
                                muxer.writeSampleData(
                                    outAudioTrackIndex!!,
                                    audioFrame.buffer,
                                    audioFrame.info
                                )
                                audioFramesWritten.incrementAndGet()
                                audioFrame = audioFrames.tryReceive().getOrNull()
                            }
                        }
                    }

                    // write all available encoded video frames at this time
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

                                    val percent = frame.info.presentationTimeUs / totalDurationUs.toFloat()
                                    send(
                                        VideoProcessingState(
                                            percent,
                                            thumbnailBitmap
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        workerJob.join()

        if (isDecoderFinished && isEncoderFinished && occurredError == null) {
            muxer.stop()
            muxer.release()
        }

        release()

        if (occurredError != null) {
            throw occurredError!!
        }

        Timber.d("Processing finished. ${outputFile.absolutePath}")

        Timber.d("Bytes written=$bytesWritten")
        Timber.d("Video frames read=$videoFramesRead")
        Timber.d("Video frames written=$videoFramesWritten")
        Timber.d("Audio frames read=${audioFramesRead.get()}")
        Timber.d("Audio frames written=${audioFramesWritten.get()}")

        val time = (System.currentTimeMillis() - startTime) / 1000f
        Timber.d("TIME = $time")
    }.flowOn(primaryDispatcher)

    private fun calcOutputVideoParams() {
        outputWidth = ENCODE_WIDTH
        outputHeight = ENCODE_HEIGHT

        // 0.45
        // 0.5625
        val (rotatedInputWidth, rotatedInputHeight) = when (inputVideoRotationDeg) {
            270, 90 -> {
                inputHeight to inputWidth
            }
            else -> inputWidth to inputHeight
        }

        val isOriginalInPortrait = rotatedInputHeight > rotatedInputWidth
        if (isOriginalInPortrait) {
            outputWidth = ENCODE_HEIGHT
            outputHeight = ENCODE_WIDTH
        } else {
            outputWidth = ENCODE_WIDTH
            outputHeight = ENCODE_HEIGHT
        }
    }

    private fun renderDecodedVideoFrameToEncoder() {
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

        frameBlit!!.drawFrameY(inputTextureId, tempMatrix, 0, 1f)
        encoderWindowSurface!!.setPresentationTime(inputTexture!!.timestamp)
        encoderWindowSurface!!.swapBuffers()

        if (framesRendered > 0) {
            synchronized(takeSnapshotCallbackLock) {
                if (takeSnapshotCallback != null) {
                    Thread.sleep(50)
                    val bmp =
                        makeBitmapFromGlPixels(outputWidth, outputHeight, 0)
                    takeSnapshotCallback?.invoke(bmp)
                    takeSnapshotCallback = null
                }
            }
        }

        ++framesRendered
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