package dev.video.sandbox.presentation

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.video.sandbox.App
import dev.video.sandbox.domain.ChatRepository
import dev.video.sandbox.domain.MessageModel
import dev.video.sandbox.video.storage.VideoHandler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MessagePresentationModel {
    val id: String
    data class Text(override val id: String, val text: String): MessagePresentationModel
    data class Video(override val id: String, val handler: VideoHandler): MessagePresentationModel
}

@Immutable
data class MainPresentationModels(
    val messages: List<MessagePresentationModel> = emptyList()
)

class MainViewModel: ViewModel() {

    private val videoManager by lazy { App.instance.videoManager }
    private val chatRepository = ChatRepository()

    val models: StateFlow<MainPresentationModels> = chatRepository.messages.map {
        MainPresentationModels(
            messages = it
                .asReversed()
                .asSequence()
                .map(::mapMessage)
                .toList()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainPresentationModels())

    fun sendText(text: String) {
        viewModelScope.launch {
            chatRepository.postText(text)
        }
    }

    private fun mapMessage(raw: MessageModel): MessagePresentationModel {
        return when(raw) {
            is MessageModel.Text -> MessagePresentationModel.Text(raw.id, raw.text)
            is MessageModel.Video -> MessagePresentationModel.Video(
                id = raw.id,
                handler = videoManager.getVideoHandler(raw.descriptor)
            )
        }
    }

    fun sendVideo(uri: Uri, withAudio: Boolean) {
        chatRepository.initNewOutgoingVideo(uri, withAudio)
    }

    fun onClearStorageClick() {
        videoManager.clearStorage()
    }
}