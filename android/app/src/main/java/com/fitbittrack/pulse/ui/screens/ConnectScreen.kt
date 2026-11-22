package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.AuthState
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.theme.PulseTheme

@Composable
fun ConnectScreen(
    auth: AuthState,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onContinue: () -> Unit,
    onExploreSample: () -> Unit,
) {
    // As soon as Google grants access, move on to Today.
    LaunchedEffect(auth) { if (auth is AuthState.SignedIn) onContinue() }

    val c = PulseTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Canvas(Modifier.size(200.dp)) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(c.track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(c.sleep, -110f, 120f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(c.move, 40f, 130f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("Welcome to Pulse", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            "Your Fitbit Air data, in an app designed by you. Sign in with the Google account your Fitbit uses.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PulseCard {
            Text("Read-only", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pulse can read activity, heart, sleep and body data. It never changes your Fitbit data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (auth is AuthState.Error) {
            Text(auth.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onConnect,
            enabled = auth !is AuthState.Working,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (auth is AuthState.Working) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Continue with Google")
            }
        }
        TextButton(onClick = onExploreSample) { Text("Look around with sample data") }
        Text(
            "Opens Google's own screen. Pick your Fitbit account there. Pulse never sees your password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
