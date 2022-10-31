package dev.camera.sandbox.presentation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.rememberImagePainter
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player.REPEAT_MODE_ONE
import com.google.android.exoplayer2.ui.StyledPlayerView
import dev.camera.sandbox.video.storage.VideoState
import java.io.File
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel, onPickImageClick: () -> Unit) {

    val models = viewModel.models.collectAsState().value

    val inputText = remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {

        Row {
            TextButton(onClick = viewModel::onClearStorageClick) {
                Text("clear storage")
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            reverseLayout = true,
            contentPadding = PaddingValues(16.dp)
        ) {
            items(models.messages.size, key = { models.messages[it].id }) {
                MessageItemContainer {
                    when (val item = models.messages[it]) {
                        is MessagePresentationModel.Text -> TextItemView(item)
                        is MessagePresentationModel.Video -> VideoItemView(item)
                    }
                }
            }
        }

        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(modifier = Modifier.weight(1f), value = inputText.value, onValueChange = {
                inputText.value = it
            })

            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                viewModel.sendText(inputText.value)
                inputText.value = ""
            }, enabled = inputText.value.isNotBlank()) {
                Text("POST")
            }

            TextButton(onClick = onPickImageClick) {
                Text("VIDOS")
            }
        }
    }
}

@Composable
private fun VideoItemView(item: MessagePresentationModel.Video) {
    val state = item.handler.state.collectAsState().value

    Box(
        modifier = Modifier
            .size(130.dp, 170.dp)
            .background(Color.LightGray),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            VideoState.Initial -> {
                Text(text = "Initialization...")
            }

            is VideoState.Processing.InProgress -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.thumbBitmap != null)
                        Image(
                            painter = rememberImagePainter(state.thumbBitmap),
                            contentDescription = null,
                        )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val color = Color.DarkGray.copy(alpha = 0.5f)
                                val f = size.width * (1f - state.progress)
                                drawRect(
                                    color = color,
                                    topLeft = Offset(size.width - f, 0f),
                                    size = Size(f, size.height)
                                )
                            }) {
                        val percent = (state.progress * 100f).roundToInt()
                        Text(text = "Processing...")
                        Text(text = "${percent}%")
                    }
                }
            }

            VideoState.Processing.Initialization -> {
                Text(text = "Processing...")
            }

            VideoState.ProcessingError -> {
                Column(modifier = Modifier.clickable { item.handler.retry() }) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red)
                    Text("retry")
                }
            }

            is VideoState.Uploading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.thumbBitmap != null)
                        Image(
                            painter = rememberImagePainter(state.thumbBitmap),
                            contentDescription = null,
                        )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val color = Color.Blue.copy(alpha = 0.5f)
                                val f = size.width * (1f - state.progress)
                                drawRect(
                                    color = color,
                                    topLeft = Offset(size.width - f, 0f),
                                    size = Size(f, size.height)
                                )
                            }) {
                        val percent = (state.progress * 100f).roundToInt()
                        Text(text = "Uploading...")
                        Text(text = "${percent}%")
                    }
                }
            }

            is VideoState.Ready -> {
                Player(modifier = Modifier.fillMaxSize(), file = state.file)
            }
        }
    }
}

@Composable
private fun Player(modifier: Modifier = Modifier, file: File) {
    AndroidView(modifier = modifier, factory = { context ->
        StyledPlayerView(context).apply {
            //useController = false
            val mediaItem = MediaItem.fromUri(file.absolutePath)

            val player = ExoPlayer.Builder(context).build()
            player.prepare()
            player.playWhenReady = true
            player.repeatMode = REPEAT_MODE_ONE
            player.setMediaItem(mediaItem)

            this.player = player
        }
    })
}

@Composable
private fun TextItemView(item: MessagePresentationModel.Text) {
    Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        Text(item.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.MessageItemContainer(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(16.dp)
            .animateItemPlacement(),
        shape = RoundedCornerShape(4.dp),
        elevation = 8.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}