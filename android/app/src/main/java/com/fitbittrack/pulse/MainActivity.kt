package com.fitbittrack.pulse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitbittrack.pulse.ui.navigation.PulseApp
import com.fitbittrack.pulse.ui.theme.PulseTheme
import com.fitbittrack.pulse.ui.theme.ThemeMode
import com.fitbittrack.pulse.ui.widget.art.WidgetOpen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** The page a home-screen widget asked for, until it has been opened. */
    private val widgetOpen = MutableStateFlow<WidgetOpen?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WidgetOpen.from(intent)?.let { widgetOpen.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) widgetOpen.value = WidgetOpen.from(intent)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Status/navigation bar icons and the window background follow the app's own theme,
            // not just the phone's, so they stay readable when the user forces Light or Dark.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.setBackgroundDrawable((if (dark) 0xFF0B1018.toInt() else 0xFFF4F6FA.toInt()).toDrawable())
                onDispose {}
            }
            PulseTheme(mode = state.themeMode) {
                val open by widgetOpen.collectAsStateWithLifecycle()
                PulseApp(state = state, viewModel = viewModel, widgetOpen = open, onWidgetOpened = { widgetOpen.value = null })
            }
        }
    }
}
