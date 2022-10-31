package dev.video.sandbox.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber
import java.nio.ByteBuffer


data class MediaTrackInfo(
    val trackIndex: Int,
    val mime: String,
    val format: MediaFormat
)

fun ByteBuffer.deepCopyData(): ByteBuffer {
    val newBuffer = ByteBuffer.allocate(limit())
    rewind()
    newBuffer.put(this)
    rewind()
    newBuffer.flip()
    return newBuffer
}

fun selectCodec(mimeType: String): MediaCodecInfo? {
    val numCodecs = MediaCodecList.getCodecCount()
    for (i in 0 until numCodecs) {
        val codecInfo = MediaCodecList.getCodecInfoAt(i)
        if (!codecInfo.isEncoder) {
            continue
        }
        val types = codecInfo.supportedTypes
        for (type in types) {
            if (type.equals(mimeType, ignoreCase = true)) {
                return codecInfo
            }
        }
    }
    return null
}

fun dumpTrack(extractor: MediaExtractor, i: Int) {
    val format = extractor.getTrackFormat(i)
    val mime = format.getString(MediaFormat.KEY_MIME)

    Timber.d("\n====== Track $i ======")
    Timber.d("Mime: $mime")

    if (mime == null) return
    when {
        mime.startsWith("video/") -> {
            Timber.d("Frame rate: ${format.getInteger(MediaFormat.KEY_FRAME_RATE)}")
            Timber.d("Width: ${format.getInteger(MediaFormat.KEY_WIDTH)}")
            Timber.d("Height: ${format.getInteger(MediaFormat.KEY_HEIGHT)}")
            Timber.d("Duration: ${format.getLong(MediaFormat.KEY_DURATION)}")
        }

        mime.startsWith("audio/") -> {
            Timber.d("Duration: ${format.getLong(MediaFormat.KEY_DURATION)}")
        }
    }
}

fun MediaExtractor.pickMediaTrackInfo(prefix: String): MediaTrackInfo? {
    var result: MediaTrackInfo? = null
    for (index in 0 until trackCount) {
        val format = getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(prefix)) {
            result = MediaTrackInfo(
                trackIndex = index,
                mime = mime,
                format = format
            )
            break
        }
    }

    return result
}

fun MediaFormat.getRotationDeg(): Int {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getInteger(MediaFormat.KEY_ROTATION)
        } else {
            getInteger("rotation-degrees")
        }
    } catch (th: Throwable) {
        Timber.w("Property 'rotation-degrees' not found.")
        0
    }
}

