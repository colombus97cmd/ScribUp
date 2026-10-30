package com.scribup.app.bridge

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Mesure du décalage du retour casque : tu tapes en rythme sur le beat que tu entends ;
 * l'écart moyen entre tes tapes et les temps de FL Studio donne le retard total
 * (PC -> Wi-Fi -> téléphone -> Bluetooth -> oreilles).
 */
object Latency {

    data class Result(
        val ms: Int,
        /** 1.0 = tapes parfaitement régulières ; en dessous de ~0.8, mieux vaut recommencer. */
        val regularity: Double,
    )

    const val MIN_TAPS = 8

    /**
     * @param beatPositions position dans FL (en temps, valeur continue) au moment de chaque tape
     * @param beatMs durée d'un temps au tempo actuel
     */
    fun fromTaps(beatPositions: List<Double>, beatMs: Double): Result? {
        if (beatPositions.size < 4 || beatMs <= 0) return null
        var s = 0.0
        var c = 0.0
        for (position in beatPositions) {
            val angle = 2 * PI * (position - floor(position))
            s += sin(angle)
            c += cos(angle)
        }
        val regularity = hypot(s, c) / beatPositions.size
        // Phase moyenne dans [-0.5, 0.5[ ; une légère avance (on anticipe en tapant) compte comme 0,
        // le reste est du retard.
        var phase = atan2(s, c) / (2 * PI)
        if (phase < -0.1) phase += 1.0
        return Result(ms = (phase * beatMs).roundToInt().coerceAtLeast(0), regularity = regularity)
    }
}
