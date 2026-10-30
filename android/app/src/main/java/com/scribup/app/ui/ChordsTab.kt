package com.scribup.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel
import com.scribup.app.data.Song
import com.scribup.app.lyrics.MusicTheory
import com.scribup.app.ui.theme.Accent
import com.scribup.app.ui.theme.Accent2
import com.scribup.app.ui.theme.Dim
import com.scribup.app.ui.theme.Surface2

@Composable
fun ChordsTab(vm: MainViewModel, song: Song, onAskClaude: (String) -> Unit) {
    val bridge by vm.bridge.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val latin = settings.latinNotes
    val audio = bridge.audio
    val liveGrid = bridge.grid
    val grid = liveGrid.ifEmpty { song.chordGrid }
    val key = MusicTheory.parseKey(song.key) ?: MusicTheory.parseKey(audio.key)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- Ce qui joue maintenant ---------------------------------------------
        ChordCard("En direct du PC") {
            if (bridge.status != com.scribup.app.bridge.LinkStatus.CONNECTED) {
                Text("Connecte le pont PC (réglages) pour analyser le son de FL Studio en direct.", color = Dim)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        MusicTheory.displayChord(if (audio.active) audio.chord else null, latin),
                        fontSize = 44.sp, fontWeight = FontWeight.Black, color = Accent2,
                    )
                    Spacer(Modifier.padding(6.dp))
                    Column {
                        if (audio.active) Text("confiance ${(audio.chordConf * 100).toInt()} %", color = Dim, fontSize = 12.sp)
                        audio.bass?.let { Text("basse ${MusicTheory.displayChord(it, latin)}", color = Dim, fontSize = 12.sp) }
                        Text(
                            when {
                                audio.source == null -> "pas de son (plugin ScribUp Send sur le Master ?)"
                                audio.active -> "${audio.level.toInt()} dB"
                                else -> "silence"
                            },
                            color = Dim, fontSize = 12.sp,
                        )
                    }
                }
                ChromaBars(audio.chroma, latin)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val detected = audio.key?.let { MusicTheory.parseKey(it) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Tonalité détectée : " + (detected?.name(latin) ?: "—") +
                                (if (detected != null) " (${(audio.keyConf * 100).toInt()} %)" else ""),
                        )
                        detected?.let {
                            Text("ou son relatif ${it.relative.name(latin)} (mêmes notes)", color = Dim, fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { vm.bridge.resetKey() }) { Text("Réinit.") }
                }
                if (audio.key != null) {
                    OutlinedButton(onClick = {
                        MusicTheory.parseKey(audio.key)?.let { k -> vm.update { it.copy(key = k.name(latin)) } }
                    }) { Text("Utiliser comme tonalité du morceau") }
                }
            }
        }

        // --- Théorie : gamme et accords de la tonalité ------------------------------
        ChordCard("Tonalité ${key?.name(latin) ?: "—"}") {
            if (key == null) {
                Text("Renseigne la tonalité du morceau (onglet Écrire → Infos) ou laisse le PC la détecter.", color = Dim)
            } else {
                Text(
                    "Gamme : " + MusicTheory.scale(key).joinToString(" ") { MusicTheory.noteName(it, latin) },
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Dans FL : piano roll → Scale highlighting → ${MusicTheory.noteName(key.tonic, false)} ${if (key.minor) "Minor" else "Major"}", color = Dim, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MusicTheory.diatonicChords(key, latin).forEach { d ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Surface2).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(d.degree, color = Dim, fontSize = 11.sp)
                            Text(d.chord, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        // --- Grille captée ---------------------------------------------------------
        ChordCard(if (liveGrid.isNotEmpty()) "Grille captée (en direct)" else "Grille enregistrée") {
            if (grid.isEmpty()) {
                Text(
                    "Lance la lecture dans FL : chaque mesure reçoit son accord. Plus la boucle tourne, plus la grille est fiable.",
                    color = Dim,
                )
            } else {
                val last = grid.keys.max()
                (1..last).chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { bar ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Surface2).padding(6.dp),
                            ) {
                                Text("$bar", color = Dim, fontSize = 10.sp)
                                Text(MusicTheory.displayChord(grid[bar], latin), fontWeight = FontWeight.Bold)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (liveGrid.isNotEmpty()) Button(onClick = vm::saveGridToSong) { Text("Enregistrer") }
                if (bridge.status == com.scribup.app.bridge.LinkStatus.CONNECTED) {
                    OutlinedButton(onClick = { vm.bridge.resetGrid() }) { Text("Effacer (PC)") }
                }
            }
        }

        Button(
            onClick = {
                onAskClaude(
                    "Analyse ma grille d'accords et ma tonalité : degrés, ambiance, et propose 2 variations " +
                        "ou un pont, une ligne de basse/808 et une idée de topline. Donne les notes pour le piano roll de FL Studio."
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Demander à Claude d'analyser l'harmonie") }
    }
}

@Composable
private fun ChordCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun ChromaBars(chroma: List<Double>, latin: Boolean) {
    if (chroma.size != 12) return
    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        chroma.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(v.toFloat().coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (v > 0.6) Accent else Color.White.copy(alpha = 0.25f)),
                    )
                }
                Text(MusicTheory.noteName(i, latin), fontSize = 9.sp, color = Dim, maxLines = 1)
            }
        }
    }
}
