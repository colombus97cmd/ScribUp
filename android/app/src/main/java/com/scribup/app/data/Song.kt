package com.scribup.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessage(
    val role: String, // "user" | "assistant"
    /** Ce qui est affiché dans l'app. */
    val text: String,
    /**
     * Ce qui a été réellement envoyé à Claude (texte + contexte du morceau).
     * Conservé tel quel pour que l'historique renvoyé reste identique d'un appel à l'autre.
     */
    val apiText: String = text,
)

@Serializable
data class Song(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Sans titre",
    val lyrics: String = "",
    val bpm: Int = 90,
    val key: String = "",
    val languages: String = "",
    val barsPerLine: Double = 1.0,
    val notes: String = "",
    /** Grille d'accords captée depuis FL Studio : mesure -> accord. */
    val chordGrid: Map<Int, String> = emptyMap(),
    val chat: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

class SongRepository(context: Context) {
    private val dir = File(context.filesDir, "songs").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    suspend fun list(): List<Song> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Song>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun save(song: Song) = withContext(Dispatchers.IO) {
        val target = File(dir, "${song.id}.json")
        val tmp = File(dir, "${song.id}.json.tmp")
        tmp.writeText(json.encodeToString(Song.serializer(), song))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
    }
}
