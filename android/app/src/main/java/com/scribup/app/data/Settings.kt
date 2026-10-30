package com.scribup.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val apiKey: String = "",
    val model: String = MODELS.first().id,
    val bridgeHost: String = "",
    val bridgePort: Int = 8765,
    val bridgePin: String = "",
    val defaultLanguages: String = "Français, Anglais",
    val latinNotes: Boolean = false,
    val preRollBars: Int = 1,
    val liveFontSize: Int = 26,
    /** Retard du retour casque (PC -> Wi-Fi -> téléphone -> Bluetooth), mesuré en tapant en rythme. */
    val monitorDelayMs: Int = 250,
    val monitorVolume: Float = 1f,
)

data class ModelOption(val id: String, val label: String)

val MODELS = listOf(
    ModelOption("claude-opus-5-5", "Claude Opus 5.5 — le plus créatif"),
    ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5 — rapide, moins cher"),
    ModelOption("claude-haiku-5-5", "Claude Haiku 5.5 — très rapide, très économique"),
)

/** Réglages stockés dans les préférences privées de l'app (appli perso, sur ton téléphone). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scribup", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        apiKey = prefs.getString("apiKey", "") ?: "",
        model = prefs.getString("model", null)?.takeIf { id -> MODELS.any { it.id == id } } ?: MODELS.first().id,
        bridgeHost = prefs.getString("bridgeHost", "") ?: "",
        bridgePort = prefs.getInt("bridgePort", 8765),
        bridgePin = prefs.getString("bridgePin", "") ?: "",
        defaultLanguages = prefs.getString("defaultLanguages", null) ?: AppSettings().defaultLanguages,
        latinNotes = prefs.getBoolean("latinNotes", false),
        preRollBars = prefs.getInt("preRollBars", 1),
        liveFontSize = prefs.getInt("liveFontSize", 26),
        monitorDelayMs = prefs.getInt("monitorDelayMs", AppSettings().monitorDelayMs),
        monitorVolume = prefs.getFloat("monitorVolume", 1f),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("apiKey", s.apiKey.trim())
            .putString("model", s.model)
            .putString("bridgeHost", s.bridgeHost.trim())
            .putInt("bridgePort", s.bridgePort)
            .putString("bridgePin", s.bridgePin.trim())
            .putString("defaultLanguages", s.defaultLanguages)
            .putBoolean("latinNotes", s.latinNotes)
            .putInt("preRollBars", s.preRollBars)
            .putInt("liveFontSize", s.liveFontSize)
            .putInt("monitorDelayMs", s.monitorDelayMs)
            .putFloat("monitorVolume", s.monitorVolume)
            .apply()
        _settings.value = s
    }
}
