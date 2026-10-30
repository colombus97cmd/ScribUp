package com.scribup.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel
import com.scribup.app.bridge.Latency
import com.scribup.app.ui.theme.Accent
import com.scribup.app.ui.theme.Bad
import com.scribup.app.ui.theme.Dim
import com.scribup.app.ui.theme.Good
import com.scribup.app.ui.theme.Surface1
import com.scribup.app.ui.theme.Warn

/** Réglages du retour casque, affichés dans l'onglet Live quand il est activé. */
@Composable
fun MonitorPanel(vm: MainViewModel, delayMs: Int, volume: Float, onMeasure: () -> Unit) {
    val stats by vm.monitor.stats.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(Surface1).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Headphones, null, tint = if (stats.receiving && stats.error == null) Good else Warn)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    stats.error != null -> "Lecture impossible : ${stats.error}"
                    stats.receiving -> "Son du PC reçu"
                    else -> "En attente du son du PC…"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (stats.error != null) Bad else Color.Unspecified,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", color = Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
            Slider(value = volume, onValueChange = vm::setMonitorVolume, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Décalage", color = Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
            IconButton(
                onClick = { vm.updateSettings { it.copy(monitorDelayMs = (delayMs - 10).coerceAtLeast(0)) } },
                modifier = Modifier.size(36.dp),
            ) { Icon(Icons.Default.Remove, "Moins 10 ms") }
            Text("$delayMs ms", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
            IconButton(
                onClick = { vm.updateSettings { it.copy(monitorDelayMs = delayMs + 10) } },
                modifier = Modifier.size(36.dp),
            ) { Icon(Icons.Default.Add, "Plus 10 ms") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onMeasure) { Text("Mesurer", maxLines = 1, softWrap = false) }
        }
        Text(
            "Coupe l'écoute du micro dans FL : ta voix te reviendrait avec ce retard. " +
                "Après une prise, avance le clip de $delayMs ms dans la Playlist.",
            color = Dim, fontSize = 12.sp, lineHeight = 16.sp,
        )
    }
}

/**
 * Mesure du retard : on tape en rythme sur le beat entendu dans les écouteurs. L'instant
 * du toucher (et non du relâcher) est comparé à la position de FL Studio.
 */
@Composable
fun MeasureDelayDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val bridge by vm.bridge.state.collectAsStateWithLifecycle()
    val taps = remember { mutableStateListOf<Double>() }
    val ready = bridge.live && bridge.fl.playing
    val result = if (taps.size >= Latency.MIN_TAPS) Latency.fromTaps(taps.takeLast(16), 60_000.0 / bridge.fl.bpm) else null
    val usable = result != null && result.regularity >= 0.8

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mesurer le décalage") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (ready) "Tape sur le rond à chaque temps du beat que tu entends dans tes écouteurs (au moins ${Latency.MIN_TAPS} fois)."
                    else "Lance la lecture dans FL Studio, puis tape sur le rond en rythme avec le beat que tu entends.",
                )
                Box(
                    Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(if (ready) Accent else Dim)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // Instant réel du toucher, converti dans l'horloge utilisée par la position FL
                                val tapTime = SystemClock.elapsedRealtime() - (SystemClock.uptimeMillis() - down.uptimeMillis)
                                val state = vm.bridge.state.value
                                if (state.live && state.fl.playing) {
                                    taps += (state.positionAt(tapTime) - 1) * state.fl.beatsPerBar
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${taps.size}", fontSize = 40.sp, fontWeight = FontWeight.Black)
                }
                when {
                    result == null -> Text(" ", color = Dim)
                    usable -> Text("Décalage mesuré : ${result.ms} ms", fontWeight = FontWeight.Bold)
                    else -> Text("Tapes trop irrégulières : continue ou recommence.", color = Warn)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = usable,
                onClick = {
                    result?.let { r -> vm.updateSettings { it.copy(monitorDelayMs = r.ms) } }
                    onDismiss()
                },
            ) { Text("Utiliser") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { taps.clear() }) { Text("Recommencer") }
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }
        },
    )
}
