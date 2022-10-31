package dev.camera.sandbox.domain

import android.content.Context
import android.net.Uri
import dev.camera.sandbox.App
import dev.camera.sandbox.video.storage.VideoDescriptor
import dev.camera.sandbox.util.flowProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
sealed interface MessageModel {
    val id: String // represents server ID

    @Serializable
    data class Text(
        override val id: String,
        val text: String
    ) : MessageModel

    @Serializable
    data class Video(override val id: String, val descriptor: VideoDescriptor) : MessageModel
}

@Serializable
private data class SavedMessages(
    val messages: List<MessageModel> = emptyList()
)

class ChatRepository {

    private val prefs = App.instance.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val savedMessages = prefs.flowProperty(Json, "messages", SavedMessages())

    private val videoStorage by lazy { App.instance.videoManager }

    val messages: StateFlow<List<MessageModel>> = savedMessages.flow.map { it.messages }
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Eagerly, emptyList())

    init {
        savedMessages.resetToDefault()
    }

    fun postText(text: String) {
        storeMessage(MessageModel.Text(newId(), text))
    }

    fun initNewOutgoingVideo(originalMediaUri: Uri, withAudio: Boolean) {
        val desc = videoStorage.initNewVideoByUri(originalMediaUri, withAudio)
        storeMessage(MessageModel.Video(newId(), desc))
    }

    private fun storeMessage(m: MessageModel) {
        savedMessages.updateValue {
            it.copy(messages = it.messages + m)
        }
    }

    private fun newId(): String {
        return UUID.randomUUID().toString()
    }
}