package com.scribup.app.bridge

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

@Serializable
data class FlInfo(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val recording: Boolean = false,
    val bpm: Double = 120.0,
    val songMode: Boolean = true,
    val bar: Int = 1,
    val beat: Int = 1,
    val beatFrac: Double = 0.0,
    val beatsPerBar: Int = 4,
)

@Serializable
data class AudioInfo(
    val active: Boolean = false,
    val level: Double = -120.0,
    val chord: String? = null,
    val chordConf: Double = 0.0,
    val bass: String? = null,
    val key: String? = null,
    val keyConf: Double = 0.0,
    val chroma: List<Double> = emptyList(),
)

enum class LinkStatus { OFF, CONNECTING, CONNECTED, ERROR }

/** Format du son envoyé par le pont pour le retour casque. */
data class StreamFormat(val rate: Int = 48000, val channels: Int = 2)

data class BridgeState(
    val status: LinkStatus = LinkStatus.OFF,
    val message: String = "",
    val pcName: String = "",
    val fl: FlInfo = FlInfo(),
    val audio: AudioInfo = AudioInfo(),
    val grid: Map<Int, String> = emptyMap(),
    val audioFormat: StreamFormat? = null,
    /** Horodatage (elapsedRealtime) de la dernière position reçue, pour l'extrapolation. */
    val receivedAt: Long = 0L,
) {
    val live: Boolean get() = status == LinkStatus.CONNECTED && fl.connected

    /**
     * Position en mesures (1.0 = début de la mesure 1), extrapolée avec le tempo.
     * [delayMs] recule la position pendant la lecture : avec le retour casque, on affiche
     * ce que tu entends, pas ce que FL joue.
     */
    fun positionAt(nowMs: Long = SystemClock.elapsedRealtime(), delayMs: Int = 0): Double {
        val beats = (fl.beat - 1) + fl.beatFrac
        var pos = fl.bar + beats / fl.beatsPerBar
        if (fl.playing && receivedAt > 0) {
            val elapsed = ((nowMs - receivedAt).coerceIn(-500, 500) - delayMs) / 1000.0
            pos += elapsed * fl.bpm / 60.0 / fl.beatsPerBar
        }
        return pos
    }
}

/** Client WebSocket vers le compagnon PC (pc-bridge/scribup_bridge.py). */
class BridgeClient(private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    /** Reçoit les blocs de son du PC (fil réseau d'OkHttp) quand le retour casque est actif. */
    @Volatile
    var audioSink: ((ByteString) -> Unit)? = null
    @Volatile
    private var audioWanted = false

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var target: Triple<String, Int, String>? = null
    private var generation = 0

    fun connect(host: String, port: Int, pin: String) {
        if (host.isBlank()) {
            _state.update { it.copy(status = LinkStatus.ERROR, message = "Renseigne l'IP du PC dans les réglages") }
            return
        }
        target = Triple(host.trim(), port, pin.trim())
        open()
    }

    fun disconnect() {
        target = null
        generation++
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _state.value = BridgeState()
    }

    fun command(action: String, bar: Int? = null) = send(buildJsonObject {
        put("type", "cmd")
        put("action", action)
        if (bar != null) put("bar", bar)
    })

    fun resetGrid() = send(buildJsonObject { put("type", "grid_reset") })

    fun resetKey() = send(buildJsonObject { put("type", "key_reset") })

    /** Demande (ou arrête) l'envoi du son du PC ; redemandé automatiquement après une reconnexion. */
    fun setAudioStream(enabled: Boolean) {
        audioWanted = enabled
        send(audioRequest(enabled))
    }

    private fun audioRequest(enabled: Boolean) = buildJsonObject {
        put("type", "audio")
        put("enabled", enabled)
    }

    private fun send(obj: JsonObject): Boolean = socket?.send(obj.toString()) ?: false

    private fun open() {
        val (host, port, pin) = target ?: return
        val myGeneration = ++generation
        reconnectJob?.cancel()
        socket?.cancel()
        _state.update { it.copy(status = LinkStatus.CONNECTING, message = "Connexion à $host:$port…") }
        val request = Request.Builder().url("ws://$host:$port/").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject {
                    put("type", "hello")
                    put("pin", pin)
                    put("client", "android")
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGeneration == generation) handle(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (myGeneration == generation) audioSink?.invoke(bytes)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration == generation) scheduleReconnect("Connexion fermée")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGeneration == generation) scheduleReconnect("PC injoignable (${t.message ?: t.javaClass.simpleName})")
            }
        })
    }

    private fun handle(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "welcome" -> {
                _state.update {
                    it.copy(
                        status = LinkStatus.CONNECTED,
                        message = "Connecté",
                        pcName = obj["name"]?.jsonPrimitive?.content ?: "",
                    )
                }
                if (audioWanted) send(audioRequest(true))
            }
            "audio_format" -> _state.update {
                it.copy(
                    audioFormat = StreamFormat(
                        rate = obj["rate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 48000,
                        channels = obj["channels"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2,
                    )
                )
            }
            "state" -> {
                val fl = obj["fl"]?.let { json.decodeFromJsonElement(FlInfo.serializer(), it) } ?: FlInfo()
                val audio = obj["audio"]?.let { json.decodeFromJsonElement(AudioInfo.serializer(), it) } ?: AudioInfo()
                _state.update { it.copy(fl = fl, audio = audio, receivedAt = SystemClock.elapsedRealtime()) }
            }
            "grid" -> {
                val bars = obj["bars"]?.jsonObject.orEmpty().mapNotNull { (k, v) ->
                    k.toIntOrNull()?.let { bar -> bar to v.jsonPrimitive.content }
                }.toMap()
                _state.update { it.copy(grid = bars) }
            }
            "error" -> {
                target = null // PIN faux : inutile de réessayer en boucle
                _state.update {
                    it.copy(status = LinkStatus.ERROR, message = obj["message"]?.jsonPrimitive?.content ?: "Erreur")
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        socket = null
        if (target == null) return
        _state.update { it.copy(status = LinkStatus.ERROR, message = "$reason — nouvelle tentative…", fl = FlInfo()) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2500)
            open()
        }
    }
}
