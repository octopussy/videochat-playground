package dev.camera.sandbox.util

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.jvm.Synchronized

inline fun <reified T> SharedPreferences.flowProperty(json: Json, key: String, defaultValue: T): SettingFlowProperty<T> {
    return SettingFlowPropertyImpl(this, json, key, defaultValue, serializer())
}

interface SettingFlowProperty<T> {
    val flow: StateFlow<T>

    fun getValue(): T
    fun setValue(newValue: T)
    fun updateValue(transform: (old: T) -> T)
    fun resetToDefault()
}

class SettingFlowPropertyImpl<T>(
    private val settings: SharedPreferences,
    private val json: Json,
    private val key: String,
    private val defaultValue: T,
    private val serializer: KSerializer<T>
) : SettingFlowProperty<T> {

    private val _flow = MutableStateFlow(defaultValue)
    override val flow: StateFlow<T> = _flow.asStateFlow()

    private var isValueRead = false

    init {
        readValue()
    }

    @Synchronized
    override fun getValue(): T {
        if (!isValueRead) {
            readValue()
            isValueRead = true
        }
        return _flow.value
    }

    @Synchronized
    override fun setValue(newValue: T) {
        _flow.value = newValue
        writeValue()
    }

    override fun updateValue(transform: (old: T) -> T) {
        setValue(transform(_flow.value))
    }

    override fun resetToDefault() {
        setValue(defaultValue)
    }

    @Synchronized
    private fun readValue() {
        val value = settings.getString(key, null)
        _flow.value = runCatching { json.decodeFromString(serializer, value!!) }
            .getOrDefault(defaultValue)
    }

    @Synchronized
    private fun writeValue() {
        val value = _flow.value
        if (value != null) {
            settings.edit {
                putString(key, json.encodeToString(serializer, value))
            }
        } else {
            settings.edit {
                remove(key)
            }
        }
    }

}
