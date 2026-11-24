package com.fitbittrack.pulse.ui.widget.art

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the user chose for each widget on the home screen. Kept on this phone only. */
class WidgetStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("pulse_widgets", Context.MODE_PRIVATE)

    fun saved(id: Int): WidgetConfig? = WidgetConfig.decode(prefs.getString("w_$id", null))

    fun save(id: Int, config: WidgetConfig) {
        prefs.edit().putString("w_$id", config.encode()).apply()
        changed()
    }

    fun remove(ids: IntArray) = prefs.edit().apply { ids.forEach { remove("w_$it") } }.apply()

    /** The choice for a widget that the phone is about to place for us ("Add to home screen"). */
    fun setPending(config: WidgetConfig) = prefs.edit().putString("pending_${config.design.size.name}", config.encode()).apply()

    /** Settings of a placed widget. A new one takes over the pending choice, else the first design of its size. */
    fun forWidget(id: Int, size: WidgetSize): WidgetConfig {
        saved(id)?.takeIf { it.design.size == size }?.let { return it }
        val key = "pending_${size.name}"
        val config = WidgetConfig.decode(prefs.getString(key, null))?.takeIf { it.design.size == size } ?: WidgetConfig(WidgetDesign.bySize(size).first())
        prefs.edit().putString("w_$id", config.encode()).remove(key).apply()
        return config
    }

    companion object {
        private val versionState = MutableStateFlow(0)

        /** Goes up when settings or data changed. A widget that is being drawn right now reads everything again. */
        val version: StateFlow<Int> = versionState
        fun changed() = versionState.update { it + 1 }
    }
}
