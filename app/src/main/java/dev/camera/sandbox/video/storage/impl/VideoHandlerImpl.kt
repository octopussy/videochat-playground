package dev.camera.sandbox.video.storage.impl

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.camera.sandbox.video.VideoProcessingState
import dev.camera.sandbox.video.VideoProcessor
import dev.camera.sandbox.video.storage.VideoState
import dev.camera.sandbox.video.storage.VideoHandler
import dev.camera.sandbox.video.storage.VideoStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

internal class VideoHandlerImpl private constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val storage: VideoStorage,
    private val originalUri: Uri,
    private var withAudio: Boolean
) : VideoHandler {

    companion object {
        fun withOrigin(
            context: Context,
            scope: CoroutineScope,
            storage: VideoStorage,
            originalUri: Uri,
            withAudio: Boolean
        ): VideoHandler {
            return VideoHandlerImpl(
                context,
                scope,
                storage,
                originalUri,
                withAudio
            )
        }
    }

    private var cachedThumb: Bitmap? = null

    override val state = MutableStateFlow<VideoState>(VideoState.Initial)

    init {
        retry()
    }

    override fun retry() {
        scope.launch {
            run()
        }
    }

    private suspend fun run() {
        val storedHash = storage.getStoredHashByOrigin(originalUri, withAudio)

        if (storedHash == null) {
            handleNewProcessing()
        } else {
            val file = storage.getStoredFile(storedHash)
            if (file == null) {
                handleNewProcessing()
            } else {
                state.value = VideoState.Ready(file)
            }
        }
    }

    private suspend fun handleNewProcessing() {
        state.value = VideoState.Initial
        runCatching {
            val outputFile = processFile()
            storage.storeProcessedFile(originalUri, withAudio, outputFile)
            simUpload()
            val storedHash = storage.getStoredHashByOrigin(originalUri, withAudio)
            checkNotNull(storedHash)
            storage.getStoredFile(storedHash)!!
        }.onFailure {
            Timber.e(it)
            state.value = VideoState.ProcessingError
        }.onSuccess { storedFile ->
            state.value = VideoState.Ready(storedFile)
        }
    }

    private suspend fun simUpload() {
        var progress = 0f
        while (progress < 1f) {
            delay(20)
            progress += 0.01f
            state.value = VideoState.Uploading(progress.coerceAtMost(1f), cachedThumb)
        }
    }

    private suspend fun processFile(): File {
        val outputFile = storage.getTempProcessingFile()
        val processor = VideoProcessor(
            context = context,
            scope = scope,
            inputUri = originalUri,
            outputFile = outputFile,
            withAudio = withAudio
        )

        var latestState: VideoProcessingState? = null
        processor.process()
            .flowOn(Dispatchers.Main) // TODO: do something with contexts
            .collect { processingState ->
                latestState = processingState
                when (processingState) {
                    is VideoProcessingState.Processing -> {
                        cachedThumb = processingState.thumbBitmap
                        state.value =
                            VideoState.Processing.InProgress(
                                processingState.progress,
                                processingState.thumbBitmap
                            )
                    }

                    VideoProcessingState.Done -> {

                    }
                }
            }

        return when (latestState) {
            VideoProcessingState.Done -> outputFile
            else -> {
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                throw RuntimeException("Error processing video")
            }
        }
    }

}