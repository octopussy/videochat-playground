package dev.camera.sandbox.video.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dev.camera.sandbox.video.HashUtil
import dev.camera.sandbox.video.makeMP4Filename
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class VideoStorage(
    private val context: Context,
    private val tempProcessingDir: File,
    private val storageDir: File
) {

    companion object {
        private const val KEY_MAP = "data"

        private fun clearDir(dir: File) {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            dir.mkdirs()
        }
    }

    private val json = Json {
        allowStructuredMapKeys = true
    }

    @Serializable
    private data class StoredData(
        val data: Map<OriginVideoKey, String> = emptyMap()
    )

    @Serializable
    private data class OriginVideoKey(
        val hash: String,
        val withAudio: Boolean
    )

    private val prefs by lazy {
        context.getSharedPreferences(
            "video_storage",
            Context.MODE_PRIVATE
        )
    }

    //private val originToStored = ConcurrentHashMap<OriginVideoKey, String>()

    init {
        clearDir(tempProcessingDir)
    }

    fun clearAll() {
        clearDir(storageDir)
    }

    fun getTempProcessingFile(): File {
        val name = UUID.randomUUID().toString()
        val file = File(tempProcessingDir, name.makeMP4Filename())
        if (file.exists()) {
            file.delete()
        }
        return file
    }

    fun storeProcessedFile(originalUri: Uri, withAudio: Boolean, file: File) {
        val storedHash = VideoStorageUtil.calculateHash(file)

        val fileToStore = File(storageDir, storedHash.makeMP4Filename())
        file.copyTo(fileToStore, overwrite = true)
        file.delete()

        val key = genOriginKey(originalUri, withAudio)
        putHash(key, storedHash)
    }

    fun getStoredHashByOrigin(originalUri: Uri, withAudio: Boolean): String? {
        val originHash = HashUtil.calculateMD5(context, originalUri)
        val originKey = OriginVideoKey(originHash, withAudio)
        return getHash(originKey)
    }

    fun getStoredFile(storedHash: String): File? {
        val file = File(storageDir, storedHash.makeMP4Filename())
        return if (file.exists()) {
            file
        } else {
            null
        }
    }

    @Synchronized
    private fun putHash(key: OriginVideoKey, hash: String) {
        updateData {
            it.toMutableMap().apply {
                this[key] = hash
            }
        }
    }

    @Synchronized
    private fun containsKey(key: OriginVideoKey) = loadData().containsKey(key)

    @Synchronized
    private fun getHash(originKey: OriginVideoKey): String? = loadData()[originKey]

    private fun loadData(): Map<OriginVideoKey, String> {
        val data = runCatching {
            json.decodeFromString<StoredData>(
                prefs.getString(KEY_MAP, "{}") ?: "{}"
            )
        }.getOrDefault(
            StoredData()
        )
        return data.data
    }

    private fun storeData(data: Map<OriginVideoKey, String>) {
        prefs.edit {
            putString(KEY_MAP, json.encodeToString(StoredData(data)))
        }
    }

    private fun updateData(block: (Map<OriginVideoKey, String>) -> Map<OriginVideoKey, String>) {
        storeData(block(loadData()))
    }

    private fun genOriginKey(originalUri: Uri, withAudio: Boolean): OriginVideoKey {
        val originHash = HashUtil.calculateMD5(context, originalUri)
        return OriginVideoKey(originHash, withAudio)
    }

}