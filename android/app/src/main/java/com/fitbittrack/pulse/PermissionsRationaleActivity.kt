package com.fitbittrack.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.theme.PulseTheme
import com.fitbittrack.pulse.ui.theme.ThemeMode

/** Health Connect opens this when you tap "privacy policy" on its permission screen. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PulseTheme(mode = ThemeMode.System) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    PulsePage(padding) {
                        Text("How Pulse uses Health Connect", style = MaterialTheme.typography.headlineMedium)
                        listOf(
                            "Pulse reads today's steps, distance, floors and heart rate, so your numbers appear faster.",
                            "It only reads. It never changes or adds anything.",
                            "What it reads stays in the app's memory. It is not saved, not sent anywhere, and gone when Pulse closes.",
                            "You can turn this off in Pulse → Settings, or in Health Connect, at any time.",
                        ).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }
}
