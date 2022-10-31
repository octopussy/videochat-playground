package dev.camera.sandbox.video.storage

import android.content.Context
import android.net.Uri
import dev.camera.sandbox.video.storage.impl.VideoHandlerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class VideoDescriptor(
    val t: String
)

class VideoManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val storage = VideoStorage(
        context = context,
        tempProcessingDir = File(context.cacheDir, "video/processing"),
        storageDir = File(context.filesDir, "video/storage")
    )

    private val handlers = ConcurrentHashMap<VideoDescriptor, VideoHandler>()

    fun getVideoHandler(descriptor: VideoDescriptor): VideoHandler {
        val handler = handlers[descriptor]
            ?: throw IllegalStateException("Handler for video not found '$descriptor'")

        return handler
    }

    fun getDescriptorByStoredHash(hash: String): VideoDescriptor {
        TODO()
    }

    fun initNewVideoByUri(uri: Uri, withAudio: Boolean): VideoDescriptor {
        val descriptor = generateDescriptor()
        val handler = VideoHandlerImpl.withOrigin(
            context = context,
            scope = scope,
            storage = storage,
            originalUri = uri,
            withAudio = withAudio
        )
        handlers[descriptor] = handler
        return descriptor
    }

    fun clearStorage() {
        storage.clearAll()
    }

    private fun generateDescriptor(): VideoDescriptor {
        return VideoDescriptor(UUID.randomUUID().toString())
    }

}