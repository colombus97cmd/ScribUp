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

data class BridgeState(
    val status: LinkStatus = LinkStatus.OFF,
    val message: String = "",
    val pcName: String = "",
    val fl: FlInfo = FlInfo(),
    val audio: AudioInfo = AudioInfo(),
    val grid: Map<Int, String> = emptyMap(),
    /** Horodatage (elapsedRealtime) de la dernière position reçue, pour l'extrapolation. */
    val receivedAt: Long = 0L,
) {
    val live: Boolean get() = status == LinkStatus.CONNECTED && fl.connected

    /** Position en mesures (1.0 = début de la mesure 1), extrapolée avec le tempo. */
    fun positionAt(nowMs: Long = SystemClock.elapsedRealtime()): Double {
        val beats = (fl.beat - 1) + fl.beatFrac
        var pos = fl.bar + beats / fl.beatsPerBar
        if (fl.playing && receivedAt > 0) {
            val elapsed = ((nowMs - receivedAt).coerceIn(0, 500)) / 1000.0
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
            "welcome" -> _state.update {
                it.copy(
                    status = LinkStatus.CONNECTED,
                    message = "Connecté",
                    pcName = obj["name"]?.jsonPrimitive?.content ?: "",
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
