package com.scribup.app.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.scribup.app.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Appels à l'API Claude via le SDK officiel, en streaming. */
class ClaudeAssistant {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Volatile
    private var active: AutoCloseable? = null

    sealed interface Result {
        data object Done : Result
        data class Refused(val reason: String) : Result
        data class Failed(val message: String) : Result
    }

    /** Interrompt la réponse en cours (ferme le flux HTTP). */
    fun cancel() {
        runCatching { active?.close() }
    }

    private fun clientFor(apiKey: String): AnthropicClient {
        if (client == null || clientKey != apiKey) {
            client?.close()
            client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            clientKey = apiKey
        }
        return client!!
    }

    /**
     * Envoie l'historique (dont le dernier message utilisateur) et diffuse la réponse.
     * [onText] reçoit le texte complet à chaque morceau reçu.
     */
    suspend fun reply(
        apiKey: String,
        model: String,
        history: List<ChatMessage>,
        onText: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Failed("Ajoute ta clé API Claude dans les réglages.")
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(Prompts.SYSTEM)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            // Mise en cache automatique de l'historique : la conversation ne fait que s'allonger.
            .cacheControl(BetaCacheControlEphemeral.builder().build())
        history.forEach { msg ->
            if (msg.role == "user") builder.addUserMessage(msg.apiText) else builder.addAssistantMessage(msg.apiText)
        }
        if (!model.startsWith("claude-haiku")) {
            // Si le modèle décline une demande, l'API la relance sur un autre modèle au lieu d'échouer.
            builder.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        }

        val text = StringBuilder()
        var stopReason: BetaStopReason? = null
        try {
            clientFor(apiKey).beta().messages().createStreaming(builder.build()).use { response ->
                active = response
                for (event in response) {
                    event.contentBlockStart().ifPresent { start ->
                        // Bascule vers le modèle de secours : on repart d'une réponse vierge.
                        if (start.contentBlock().isFallback()) {
                            text.clear()
                            onText("")
                        }
                    }
                    event.contentBlockDelta().ifPresent { delta ->
                        delta.delta().text().ifPresent { t ->
                            text.append(t.text())
                            onText(text.toString())
                        }
                    }
                    event.messageDelta().ifPresent { md -> md.delta().stopReason().ifPresent { stopReason = it } }
                }
            }
        } catch (e: UnauthorizedException) {
            return@withContext Result.Failed("Clé API refusée. Vérifie-la dans les réglages.")
        } catch (e: PermissionDeniedException) {
            return@withContext Result.Failed("Accès refusé à ce modèle avec cette clé.")
        } catch (e: RateLimitException) {
            return@withContext Result.Failed("Trop de requêtes pour l'instant, réessaie dans un moment.")
        } catch (e: AnthropicServiceException) {
            return@withContext Result.Failed("Erreur de l'API (${e.statusCode()}) : ${e.message}")
        } catch (e: AnthropicIoException) {
            return@withContext Result.Failed("Pas de connexion à l'API Claude : ${e.message}")
        } catch (e: Exception) {
            return@withContext Result.Failed("Erreur : ${e.message ?: e.javaClass.simpleName}")
        } finally {
            active = null
        }
        when (stopReason) {
            BetaStopReason.REFUSAL -> Result.Refused("Claude a décliné cette demande.")
            BetaStopReason.MAX_TOKENS -> {
                onText(text.toString() + "\n\n…(réponse coupée, demande la suite)")
                Result.Done
            }
            else -> Result.Done
        }
    }
}
