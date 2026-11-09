package com.scribup.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scribup.app.ai.ClaudeAssistant
import com.scribup.app.ai.PcAssistant
import com.scribup.app.ai.Prompts
import com.scribup.app.bridge.AudioMonitor
import com.scribup.app.bridge.BridgeClient
import com.scribup.app.bridge.BridgeDiscovery
import com.scribup.app.bridge.FoundBridge
import com.scribup.app.bridge.LinkStatus
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SongRepository(app)
    private val settingsStore = SettingsStore(app)
    private val assistant = ClaudeAssistant()
    val bridge = BridgeClient(viewModelScope)
    private val pcAssistant = PcAssistant(bridge)
    val discovery = BridgeDiscovery(app)
    val monitor = AudioMonitor(bridge)

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

    // --- Écran live sur le PC (miroir du téléphone) --------------------------------

    private val _tab = MutableStateFlow(0)
    private val _cursorLine = MutableStateFlow(-1)

    /** Onglet affiché (0 Écrire, 1 Live, 2 Claude, 3 Accords), recopié sur l'écran du PC. */
    fun setTab(tab: Int) { _tab.value = tab }

    /** Ligne du texte où se trouve le curseur, surlignée sur l'écran du PC. */
    fun setCursorLine(line: Int) { _cursorLine.value = line }

    private fun startMirror() = viewModelScope.launch {
        val connected = bridge.state.map { it.status == LinkStatus.CONNECTED }.distinctUntilChanged()
        combine(_current, _tab, _cursorLine, _streaming, connected) { song, tab, cursor, streaming, online ->
            if (online) mirrorMessage(song, tab, cursor, streaming) else null
        }.conflate().collect { msg ->
            if (msg != null) {
                bridge.sendMirror(msg)
                delay(60) // au plus ~15 envois par seconde pendant qu'on tape ou que Claude répond
            }
        }
    }

    private fun mirrorMessage(song: Song?, tab: Int, cursor: Int, streaming: String?): JsonObject = buildJsonObject {
        put("type", "mirror")
        put("source", "android")
        put("tab", listOf("write", "live", "claude", "chords").getOrElse(tab) { "write" })
        put("cursorLine", cursor)
        if (song == null) {
            put("song", JsonNull)
            return@buildJsonObject
        }
        putJsonObject("song") {
            put("id", song.id)
            put("title", song.title)
            put("bpm", song.bpm)
            put("key", song.key)
            put("lyrics", song.lyrics)
            put("barsPerLine", song.barsPerLine)
            putJsonObject("chordGrid") { song.chordGrid.forEach { (bar, chord) -> put(bar.toString(), chord) } }
        }
        putJsonArray("chat") {
            song.chat.takeLast(30).forEach { m -> addJsonObject { put("role", m.role); put("text", m.text) } }
        }
        if (streaming != null) put("streaming", streaming) else put("streaming", JsonNull)
    }

    init {
        viewModelScope.launch { _songs.value = repo.list() }
        settings.value.let { if (it.bridgeHost.isNotBlank()) bridge.connect(it.bridgeHost, it.bridgePort, it.bridgePin) }
        startMirror()
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

    /** Modifie un morceau précis, même s'il n'est plus ouvert (réponse de Claude arrivée après coup). */
    private fun updateById(id: String, transform: (Song) -> Song) {
        if (_current.value?.id == id) return update(transform)
        val song = _songs.value.firstOrNull { it.id == id } ?: return
        persist(transform(song).copy(updatedAt = System.currentTimeMillis()), immediate = true)
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

    // --- Retour casque ---------------------------------------------------------

    private val _monitorOn = MutableStateFlow(false)
    val monitorOn: StateFlow<Boolean> = _monitorOn.asStateFlow()

    fun setMonitor(on: Boolean) {
        if (on) {
            monitor.setVolume(settings.value.monitorVolume)
            monitor.start()
        } else {
            monitor.stop()
        }
        _monitorOn.value = on
    }

    fun setMonitorVolume(volume: Float) {
        monitor.setVolume(volume)
        updateSettings { it.copy(monitorVolume = volume) }
    }

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
            val history = _current.value?.takeIf { it.id == song.id }?.chat ?: return@launch
            val onText: (String) -> Unit = { partial -> if (myRequest == requestId) _streaming.value = partial }
            val result = if (s.claudeViaPc) pcAssistant.reply(s.model, history, onText)
            else assistant.reply(s.apiKey, s.model, history, onText)
            if (myRequest != requestId) return@launch
            val answer = _streaming.value.orEmpty()
            _streaming.value = null
            when (result) {
                is ClaudeAssistant.Result.Done -> if (answer.isNotBlank()) {
                    updateById(song.id) { it.copy(chat = it.chat + ChatMessage(role = "assistant", text = answer)) }
                } else {
                    dropLastUserMessage(song.id, userMessage)
                }
                is ClaudeAssistant.Result.Refused -> {
                    dropLastUserMessage(song.id, userMessage)
                    _assistantError.value = result.reason
                }
                is ClaudeAssistant.Result.Failed -> {
                    dropLastUserMessage(song.id, userMessage)
                    _assistantError.value = result.message
                }
            }
        }
    }

    /** Une question sans réponse est retirée, pour que l'historique reste une alternance propre. */
    private fun dropLastUserMessage(songId: String, message: ChatMessage) = updateById(songId) { song ->
        if (song.chat.lastOrNull() == message) song.copy(chat = song.chat.dropLast(1)) else song
    }

    fun cancelAsk() {
        requestId++
        assistant.cancel()
        pcAssistant.cancel()
        askJob?.cancel()
        val song = _current.value
        val pending = song?.chat?.lastOrNull()
        _streaming.value = null
        if (song != null && pending?.role == "user") dropLastUserMessage(song.id, pending)
    }

    fun clearChat() = update { it.copy(chat = emptyList()) }

    override fun onCleared() {
        monitor.stop()
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
