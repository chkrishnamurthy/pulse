package com.fitbittrack.pulse

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitbittrack.pulse.ui.navigation.PulseApp
import com.fitbittrack.pulse.ui.theme.PulseTheme
import com.fitbittrack.pulse.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF0B1018.toInt() else 0xFFF4F6FA.toInt()))
                onDispose {}
            }
            PulseTheme(mode = state.themeMode) {
                PulseApp(state = state, viewModel = viewModel)
            }
        }
    }
}
