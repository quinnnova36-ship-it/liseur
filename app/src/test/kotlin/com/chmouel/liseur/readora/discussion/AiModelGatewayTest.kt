package com.chmouel.liseur.readora.discussion

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/**
 * The gateway's own rules: address normalisation, the one-request send,
 * and how each failure reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AiModelGatewayTest {

    private fun config(url: String) = ModelConfig(
        id = "cfg", label = "测试", baseUrl = url, modelId = "m1",
        isDefault = true, createdAt = 0,
    )

    @Test
    fun `a bare host means https and gains v1`() {
        val gateway = AiModelGateway()
        assertEquals(
            "https://api.example.com/v1",
            gateway.normalise("api.example.com", allowPlaintext = false).toString(),
        )
        assertEquals(
            "https://api.example.com/v1",
            gateway.normalise("https://api.example.com", allowPlaintext = false).toString(),
        )
        assertEquals(
            "https://api.example.com/custom",
            gateway.normalise("https://api.example.com/custom", allowPlaintext = false).toString(),
        )
    }

    @Test
    fun `plain http is refused without the explicit allowance`() {
        val gateway = AiModelGateway()
        assertNull(gateway.normalise("http://localhost:11434", allowPlaintext = false))
        assertEquals(
            "http://localhost:11434/v1",
            gateway.normalise("http://localhost:11434", allowPlaintext = true).toString(),
        )
    }

    @Test
    fun `garbage is refused`() {
        val gateway = AiModelGateway()
        assertNull(gateway.normalise("", allowPlaintext = false))
        assertNull(gateway.normalise("not a url at all ::", allowPlaintext = false))
    }

    @Test
    fun `a send posts the context as the last user message`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body("""{"choices":[{"message":{"role":"assistant","content":"这是回答"}}]}""")
                .build(),
        )
        server.start()
        val gateway = AiModelGateway()
        val sent = gateway.send(
            config = config(server.url("/v1").toString().trimEnd('/')),
            key = "sk-test",
            question = "这句什么意思？",
            composedContext = "《选中原句》\n选中\n《/选中原句》\n\n这句什么意思？",
            history = listOf("user" to "上一问", "assistant" to "上一答"),
            allowPlaintext = true,
        )

        assertEquals("这是回答", sent.getOrNull())
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        val body = org.json.JSONObject(recorded.body!!.utf8())
        assertEquals(false, body.get("stream"))
        assertEquals("m1", body.get("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("这句什么意思？", messages.getJSONObject(2).getString("content").substringAfterLast('\n'))
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        server.close()
    }

    @Test
    fun `a server error comes back as a server failure`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"message":"quota"}}""").build())
        server.start()
        val result = AiModelGateway().send(
            config(server.url("/v1").toString().trimEnd('/')),
            key = null, question = "q", composedContext = "c", history = emptyList(),
            allowPlaintext = true,
        )
        val error = result.exceptionOrNull() as DiscussionSendException.Server
        assertEquals(429, error.code)
        server.close()
    }

    @Test
    fun `a refused connection is a network failure, not a crash`() = runTest {
        // Port 1 on localhost: nothing listens there in the test world.
        val result = AiModelGateway().send(
            config("https://127.0.0.1:1"),
            key = null, question = "q", composedContext = "c", history = emptyList(),
        )
        assertTrue(result.exceptionOrNull() is DiscussionSendException.Network)
    }

    @Test
    fun `an insecure address without allowance is refused before any request`() = runTest {
        val result = AiModelGateway().send(
            config("http://192.168.1.5:8080"),
            key = null, question = "q", composedContext = "c", history = emptyList(),
            allowPlaintext = false,
        )
        assertTrue(result.exceptionOrNull() is DiscussionSendException.InsecureAddress)
    }
}
