package paywall.testtool

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A Compose state that is also saved on the device, so form fields survive app restarts and "Yenile".
 * Test tool only: values (including the temp token) are stored in plain SharedPreferences.
 */
class PersistedState<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) : ReadWriteProperty<Any?, T> {

    private val state = mutableStateOf(prefs.read(key, default))

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        prefs.edit().apply { write(key, value) }.apply()
    }

    /** Back to the default value (the saved value is removed by clearing the preferences). */
    fun reset() {
        state.value = default
    }
}

/** Creates [PersistedState]s and remembers them, so all saved fields can be reset at once. */
class PersistedFields(val prefs: SharedPreferences) {
    private val fields = mutableListOf<PersistedState<*>>()

    fun text(key: String, default: String): PersistedState<String> = PersistedState(
        prefs,
        key,
        default,
        read = { k, d -> getString(k, d) ?: d },
        write = { k, v -> putString(k, v) },
    ).also(fields::add)

    fun flag(key: String, default: Boolean): PersistedState<Boolean> = PersistedState(
        prefs,
        key,
        default,
        read = { k, d -> getBoolean(k, d) },
        write = { k, v -> putBoolean(k, v) },
    ).also(fields::add)

    inline fun <reified E : Enum<E>> enum(key: String, default: E): PersistedState<E> = custom(
        PersistedState(
            prefs,
            key,
            default,
            read = { k, d -> getString(k, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: d },
            write = { k, v -> putString(k, v.name) },
        ),
    )

    fun <T> custom(field: PersistedState<T>): PersistedState<T> = field.also(fields::add)

    fun clearAll() {
        prefs.edit().clear().apply()
        fields.forEach { it.reset() }
    }
}
