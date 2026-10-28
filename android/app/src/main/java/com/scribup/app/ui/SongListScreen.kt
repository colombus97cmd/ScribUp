package com.scribup.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel
import com.scribup.app.data.Song
import com.scribup.app.ui.theme.Dim
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val bridge by vm.bridge.state.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Song?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ScribUp", fontWeight = FontWeight.Black) },
                actions = {
                    ConnectionBadge(bridge)
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Réglages") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::newSong,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nouveau son") },
            )
        },
    ) { padding ->
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aucun morceau pour l'instant.\nAppuie sur « Nouveau son » pour commencer à écrire.",
                    color = Dim,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(songs, key = { it.id }) { song ->
                    Card(
                        Modifier.fillMaxWidth().clickable { vm.open(song.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                val firstLine = song.lyrics.lines()
                                    .firstOrNull { it.isNotBlank() && !it.trim().startsWith("[") && !it.trim().startsWith("//") }
                                    ?.trim().orEmpty()
                                if (firstLine.isNotEmpty()) {
                                    Text(firstLine, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    listOfNotNull(
                                        "${song.bpm} BPM",
                                        song.key.takeIf { it.isNotBlank() },
                                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(song.updatedAt)),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Dim,
                                )
                            }
                            IconButton(onClick = { toDelete = song }) { Icon(Icons.Default.Delete, "Supprimer", tint = Dim) }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${song.title} » ?") },
            text = { Text("Les paroles et la conversation avec Claude seront effacées.") },
            confirmButton = { TextButton(onClick = { vm.delete(song.id); toDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}
