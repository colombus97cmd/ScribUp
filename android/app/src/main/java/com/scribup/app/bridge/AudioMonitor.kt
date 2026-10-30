package com.scribup.app.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * « Retour casque » : joue sur le téléphone (donc dans les écouteurs Bluetooth) le son du PC
 * envoyé par le pont, avec le moins de retard possible.
 *
 * Le pont envoie des blocs de 10 ms (PCM 16 bits stéréo). On garde un petit tampon (~40 ms)
 * pour absorber les irrégularités du Wi-Fi, et on jette le surplus s'il grossit, pour que le
 * retard ne s'accumule jamais.
 */
class AudioMonitor(private val bridge: BridgeClient) {

    data class Stats(
        val running: Boolean = false,
        /** Du son est arrivé du PC dans la dernière demi-seconde. */
        val receiving: Boolean = false,
        val bufferedMs: Int = 0,
        /** Blocs sautés pour rattraper le retard (Wi-Fi irrégulier). */
        val dropped: Int = 0,
        /** Sortie audio du téléphone impossible à ouvrir. */
        val error: String? = null,
    )

    private val queue = ArrayBlockingQueue<ShortArray>(MAX_QUEUE)
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    @Volatile private var running = false
    @Volatile private var lastReceived = 0L
    @Volatile private var dropped = 0
    @Volatile private var volume = 1f
    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        queue.clear()
        dropped = 0
        bridge.audioSink = ::onPcm
        bridge.setAudioStream(true)
        worker = thread(name = "scribup-retour-casque") { playLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        bridge.setAudioStream(false)
        bridge.audioSink = null
        worker?.interrupt()
        worker = null
        queue.clear()
        _stats.value = Stats()
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        track?.setVolume(volume)
    }

    private fun onPcm(bytes: ByteString) {
        val samples = ShortArray(bytes.size / 2)
        bytes.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        lastReceived = SystemClock.elapsedRealtime()
        if (!queue.offer(samples)) {
            queue.poll()
            queue.offer(samples)
            dropped++
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val format = bridge.state.value.audioFormat ?: StreamFormat()
        val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bytesPerMs = format.rate * format.channels * 2 / 1000
        val minBuffer = AudioTrack.getMinBufferSize(format.rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val audioTrack = try {
            buildTrack(format, channelMask, minBuffer, bytesPerMs)
        } catch (e: Exception) {
            _stats.value = Stats(running = true, error = e.message ?: e.javaClass.simpleName)
            return
        }
        track = audioTrack
        audioTrack.setVolume(volume)
        audioTrack.play()

        var primed = false
        var lastStats = 0L
        var chunkMs = 10
        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastStats > 250) {
                    lastStats = now
                    _stats.value = Stats(
                        running = true,
                        receiving = now - lastReceived < 500,
                        bufferedMs = queue.size * chunkMs,
                        dropped = dropped,
                    )
                }
                // Petit remplissage avant de jouer, pour éviter les craquements au démarrage
                if (!primed) {
                    if (queue.size < PRIME_CHUNKS) {
                        Thread.sleep(5)
                        continue
                    }
                    primed = true
                }
                // Le Wi-Fi a rattrapé d'un coup : on jette le surplus plutôt que de garder du retard
                while (queue.size > MAX_BUFFERED_CHUNKS) {
                    queue.poll()
                    dropped++
                }
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    primed = false // coupure : on se recale au prochain son reçu
                    continue
                }
                chunkMs = maxOf(1, chunk.size * 2 / bytesPerMs)
                if (audioTrack.write(chunk, 0, chunk.size) == AudioTrack.ERROR_DEAD_OBJECT) {
                    _stats.value = Stats(running = true, error = "sortie audio perdue, réactive le retour casque")
                    return
                }
            }
        } catch (_: InterruptedException) {
            // arrêt demandé
        } finally {
            track = null
            runCatching { audioTrack.stop() }
            audioTrack.release()
        }
    }

    private fun buildTrack(format: StreamFormat, channelMask: Int, minBuffer: Int, bytesPerMs: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.rate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, TRACK_BUFFER_MS * bytesPerMs))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

    companion object {
        private const val MAX_QUEUE = 200          // ~2 s de son au maximum en file
        private const val PRIME_CHUNKS = 4         // ~40 ms avant de commencer à jouer
        private const val MAX_BUFFERED_CHUNKS = 12 // au-delà de ~120 ms en attente, on rattrape
        private const val TRACK_BUFFER_MS = 40
    }
}
