package com.scribup.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scribup.app.ai.ClaudeAssistant
import com.scribup.app.ai.Prompts
import com.scribup.app.bridge.BridgeClient
import com.scribup.app.bridge.BridgeDiscovery
import com.scribup.app.bridge.FoundBridge
import com.scribup.app.data.AppSettings
import com.scribup.app.data.ChatMessage
import com.scribup.app.data.SettingsStore
import com.scribup.app.data.Song
import com.scribup.app.data.SongRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SongRepository(app)
    private val settingsStore = SettingsStore(app)
    private val assistant = ClaudeAssistant()
    val bridge = BridgeClient(viewModelScope)
    val discovery = BridgeDiscovery(app)

    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current.asStateFlow()

    /** Réponse de Claude en cours de réception (null si aucune). */
    private val _streaming = MutableStateFlow<String?>(null)
    val streaming: StateFlow<String?> = _streaming.asStateFlow()

    private val _assistantError = MutableStateFlow<String?>(null)
    val assistantError: StateFlow<String?> = _assistantError.asStateFlow()

    private var saveJob: Job? = null
    private var askJob: Job? = null
    private var requestId = 0

    init {
        viewModelScope.launch { _songs.value = repo.list() }
        settings.value.let { if (it.bridgeHost.isNotBlank()) bridge.connect(it.bridgeHost, it.bridgePort, it.bridgePin) }
    }

    // --- Morceaux ---------------------------------------------------------

    fun newSong() {
        val song = Song(title = "Nouveau son", languages = settings.value.defaultLanguages, lyrics = TEMPLATE)
        _current.value = song
        persist(song, immediate = true)
    }

    fun open(id: String) {
        _current.value = _songs.value.firstOrNull { it.id == id }
        _assistantError.value = null
    }

    fun close() {
        _current.value?.let { persist(it, immediate = true) }
        _current.value = null
    }

    /** Sauvegarde immédiate (l'app passe en arrière-plan). */
    fun flush() {
        _current.value?.let { persist(it, immediate = true) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            _songs.value = _songs.value.filterNot { it.id == id }
            if (_current.value?.id == id) _current.value = null
        }
    }

    fun update(transform: (Song) -> Song) {
        val song = _current.value ?: return
        val updated = transform(song).copy(updatedAt = System.currentTimeMillis())
        _current.value = updated
        persist(updated, immediate = false)
    }

    private fun persist(song: Song, immediate: Boolean) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediate) delay(700)
            repo.save(song)
            _songs.value = (listOf(song) + _songs.value.filterNot { it.id == song.id }).sortedByDescending { it.updatedAt }
        }
    }

    fun appendToLyrics(text: String) = update { song ->
        val sep = if (song.lyrics.isBlank() || song.lyrics.endsWith("\n")) "" else "\n"
        song.copy(lyrics = song.lyrics + sep + text.trim() + "\n")
    }

    fun saveGridToSong() {
        val grid = bridge.state.value.grid
        if (grid.isNotEmpty()) update { it.copy(chordGrid = grid) }
    }

    // --- Réglages / pont PC -----------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    fun connectBridge() = settings.value.let { bridge.connect(it.bridgeHost, it.bridgePort, it.bridgePin) }

    fun disconnectBridge() = bridge.disconnect()

    fun useFoundBridge(found: FoundBridge) {
        updateSettings { it.copy(bridgeHost = found.host, bridgePort = found.port) }
        discovery.stop()
        connectBridge()
    }

    // --- Assistant Claude -------------------------------------------------

    fun ask(text: String) {
        val song = _current.value ?: return
        if (text.isBlank() || _streaming.value != null) return
        val s = settings.value
        val bridgeState = bridge.state.value
        val grid = bridgeState.grid.ifEmpty { song.chordGrid }
        val context = Prompts.songContext(song, bridgeState.audio.key, grid, song.languages)

        // On ne rejoint le contexte du morceau que s'il a changé depuis le dernier envoi :
        // l'historique déjà envoyé n'est jamais modifié (cache et coût maîtrisés).
        val lastContext = song.chat.lastOrNull { it.role == "user" && it.apiText.contains("<morceau>") }
            ?.apiText?.substringBefore("</morceau>")?.plus("</morceau>")
        val apiText = if (lastContext == context) text.trim() else context + "\n\n" + text.trim()

        val userMessage = ChatMessage(role = "user", text = text.trim(), apiText = apiText)
        update { it.copy(chat = it.chat + userMessage) }
        _assistantError.value = null
        _streaming.value = ""

        val myRequest = ++requestId
        askJob = viewModelScope.launch {
            val history = _current.value?.chat ?: return@launch
            val result = assistant.reply(s.apiKey, s.model, history) { partial ->
                if (myRequest == requestId) _streaming.value = partial
            }
            if (myRequest != requestId) return@launch
            val answer = _streaming.value.orEmpty()
            _streaming.value = null
            when (result) {
                is ClaudeAssistant.Result.Done -> if (answer.isNotBlank()) {
                    update { it.copy(chat = it.chat + ChatMessage(role = "assistant", text = answer)) }
                } else {
                    dropLastUserMessage(userMessage)
                }
                is ClaudeAssistant.Result.Refused -> {
                    dropLastUserMessage(userMessage)
                    _assistantError.value = result.reason
                }
                is ClaudeAssistant.Result.Failed -> {
                    dropLastUserMessage(userMessage)
                    _assistantError.value = result.message
                }
            }
        }
    }

    /** Une question sans réponse est retirée, pour que l'historique reste une alternance propre. */
    private fun dropLastUserMessage(message: ChatMessage) = update { song ->
        if (song.chat.lastOrNull() == message) song.copy(chat = song.chat.dropLast(1)) else song
    }

    fun cancelAsk() {
        requestId++
        assistant.cancel()
        askJob?.cancel()
        val pending = _current.value?.chat?.lastOrNull()
        _streaming.value = null
        if (pending?.role == "user") dropLastUserMessage(pending)
    }

    fun clearChat() = update { it.copy(chat = emptyList()) }

    override fun onCleared() {
        discovery.stop()
        bridge.disconnect()
    }

    companion object {
        val TEMPLATE = """
            [Intro]
            (yeah)

            [Couplet 1 @5]
            Écris ta première ligne ici (ad-lib)

            [Refrain]
        """.trimIndent() + "\n"
    }
}
