package com.fitbittrack.pulse

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import com.fitbittrack.pulse.ui.screens.WidgetSetupScreen
import com.fitbittrack.pulse.ui.theme.PulseTheme
import com.fitbittrack.pulse.ui.theme.ThemeMode
import com.fitbittrack.pulse.ui.widget.art.ArtWidgets
import com.fitbittrack.pulse.ui.widget.art.WidgetConfig
import com.fitbittrack.pulse.ui.widget.art.WidgetDesign
import com.fitbittrack.pulse.ui.widget.art.WidgetStore
import kotlinx.coroutines.launch

/** Opened by the home screen when a Pulse widget is placed, or later through "Settings" on the widget. */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Leaving without saving means: don't place the widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // Any app can start this page, so it only ever works on our own widgets.
        val size = ArtWidgets.sizeOf(this, id) ?: return finish()
        enableEdgeToEdge()
        val store = WidgetStore(this)
        setContent {
            val theme by remember { UiPrefsStore(applicationContext).theme }.collectAsStateWithLifecycle(ThemeMode.System)
            var config by rememberSaveable(stateSaver = ConfigSaver) { mutableStateOf(store.saved(id) ?: WidgetConfig(WidgetDesign.bySize(size).first())) }
            PulseTheme(theme) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        WidgetSetupScreen(
                            config = config, choices = WidgetDesign.bySize(size), contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
                            action = "Save", onBack = ::finish, onChange = { config = it },
                            onAction = {
                                store.save(id, config)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                lifecycleScope.launch { ArtWidgets.refreshAll(applicationContext); finish() }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Keeps half-made choices when the screen turns. */
val ConfigSaver = Saver<WidgetConfig, String>(save = { it.encode() }, restore = { WidgetConfig.decode(it) })
