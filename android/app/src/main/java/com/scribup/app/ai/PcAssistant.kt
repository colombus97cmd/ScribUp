package com.scribup.app.ai

import com.scribup.app.bridge.BridgeClient
import com.scribup.app.bridge.LinkStatus
import com.scribup.app.bridge.PcClaudeEvent
import com.scribup.app.data.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Claude via Claude Code installé sur le PC : le pont lance `claude -p` avec ton abonnement,
 * aucune clé API n'est utilisée. Même résultat que [ClaudeAssistant].
 */
class PcAssistant(private val bridge: BridgeClient) {
    @Volatile
    private var activeId: String? = null

    fun cancel() {
        activeId?.let { bridge.cancelClaude(it) }
        activeId = null
    }

    suspend fun reply(model: String, history: List<ChatMessage>, onText: (String) -> Unit): ClaudeAssistant.Result {
        val state = bridge.state.value
        if (state.status != LinkStatus.CONNECTED) {
            return ClaudeAssistant.Result.Failed("Le PC n'est pas connecté : lance le pont sur le PC puis connecte-le dans les réglages.")
        }
        if (!state.claudeOnPc) {
            return ClaudeAssistant.Result.Failed(
                if (state.bridgeOutdated) "Le pont du PC n'est pas à jour : remplace le dossier pc-bridge."
                else "Claude Code est introuvable sur le PC : installe-le, puis tape « claude » et /login dans un terminal."
            )
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<ClaudeAssistant.Result>()
        val sent = bridge.askClaude(id, Prompts.SYSTEM, model, history.map { it.role to it.apiText }) { event ->
            when (event) {
                is PcClaudeEvent.Delta -> onText(event.text)
                is PcClaudeEvent.Done -> {
                    onText(event.text)
                    done.complete(ClaudeAssistant.Result.Done)
                }
                is PcClaudeEvent.Error -> done.complete(ClaudeAssistant.Result.Failed(event.message))
            }
        }
        if (!sent) return ClaudeAssistant.Result.Failed("Impossible d'envoyer la demande au PC.")
        activeId = id
        return try {
            done.await()
        } catch (e: CancellationException) {
            bridge.cancelClaude(id)
            throw e
        } finally {
            if (activeId == id) activeId = null
        }
    }
}
