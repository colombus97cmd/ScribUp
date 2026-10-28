package com.scribup.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scribup.app.ai.ClaudeAssistant
import com.scribup.app.data.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fait un vrai appel à l'API avec une fausse clé : vérifie que le SDK Java tourne sur Android
 * (chargement des classes, Jackson, OkHttp, TLS) et que l'erreur remonte lisiblement.
 */
@RunWith(AndroidJUnit4::class)
class ClaudeSdkOnDeviceTest {
    @Test
    fun invalidKeyGivesReadableError() = runBlocking {
        val result = ClaudeAssistant().reply(
            apiKey = "sk-ant-api03-invalid-key-for-test",
            model = "claude-opus-5-5",
            history = listOf(ChatMessage(role = "user", text = "Salut")),
            onText = {},
        )
        assertTrue("résultat inattendu : $result", result is ClaudeAssistant.Result.Failed)
        assertTrue(
            "message inattendu : $result",
            (result as ClaudeAssistant.Result.Failed).message.startsWith("Clé API refusée"),
        )
    }
}
