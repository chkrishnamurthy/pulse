package com.fitbittrack.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitbittrack.pulse.ui.navigation.PulseApp
import com.fitbittrack.pulse.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            PulseTheme(mode = state.themeMode) {
                PulseApp(state = state, viewModel = viewModel)
            }
        }
    }
}
