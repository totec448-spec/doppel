package de.totec.doppel.ai

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class MediaAndSpeechTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `media builders use the selected media role and prepared bytes`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val settings =
            testSettings().copy(
                sampling =
                    SamplingSettings(
                        temperature = 1.7,
                        frequencyPenalty = 1.2,
                        presencePenalty = 0.8,
                        reasoningEffort = ReasoningEffort.MAX,
                    ),
            )
        val request = MediaRequestBuilders().image(
            settings = settings,
            bytes = bytes,
            mimeType = "image/png",
            instruction = "Describe only visible facts.",
        )

        assertEquals("test/media", request.model)
        assertFalseValue(request.stream)
        val parts = (request.messages.single().content as MessageContent.Parts).values
        assertEquals("Describe only visible facts.", (parts[0] as ContentPart.Text).text)
        assertEquals(0.2, request.sampling.temperature, 0.0)
        assertEquals(0.0, request.sampling.frequencyPenalty, 0.0)
        assertEquals(0.0, request.sampling.presencePenalty, 0.0)
        assertEquals(ReasoningEffort.PROVIDER_DEFAULT, request.sampling.reasoningEffort)
        // The budget is a credit hold, not a cap on a description: OpenRouter reserves it in
        // full before the call runs, so an oversized one fails the analysis outright.
        assertEquals(MediaRequestBuilders.ANSWER_TOKENS, request.sampling.maxTokens)
        val url = (parts[1] as ContentPart.ImageUrl).url
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertArrayEquals(
            bytes,
            Base64.getDecoder().decode(url.substringAfter(',')),
        )
    }

    @Test
    fun `video uses the configured media model its own token budget and at least minimal reasoning`() {
        val request =
            MediaRequestBuilders().video(
                settings =
                    testSettings().copy(
                        mediaReasoningEffort = ReasoningEffort.NONE,
                    ),
                bytes = byteArrayOf(1, 2, 3),
                mimeType = "video/mp4",
                instruction = "Describe it.",
            )

        // Video used to be pinned to one hard-coded build, which made the media-model setting a
        // dead knob for the exact modality that kept being re-configured.
        assertEquals("test/media", request.model)
        // Higher than a still picture, because reasoning across frames is billed as output.
        assertEquals(MediaRequestBuilders.VIDEO_ANSWER_TOKENS, request.sampling.maxTokens)
        assertTrue(MediaRequestBuilders.VIDEO_ANSWER_TOKENS > MediaRequestBuilders.ANSWER_TOKENS)
        assertEquals(ReasoningEffort.MINIMAL, request.sampling.reasoningEffort)
    }

    @Test
    fun `speech endpoint returns unwrapped raw PCM`() = runBlocking {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/pcm")
                .setBody(okio.Buffer().write(pcm)),
        )
        val client = SpeechClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/api/v1/").toString().toHttpUrl(),
            apiKeyProvider = OpenRouterApiKeyProvider { "key" },
            retryPolicy = RetryPolicy(
                maxAttempts = 1,
                initialDelayMs = 0,
                maximumDelayMs = 0,
            ),
        )

        val result = client.synthesize(
            SpeechRequest(
                model = "test/tts",
                text = "Hallo",
                voice = "Kore",
                instructions = "Natural pacing.",
            ),
        )

        assertArrayEquals(pcm, result.bytes)
        assertEquals(24_000, result.sampleRateHz)
        assertEquals(1, result.channelCount)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/audio/speech", recorded.path)
        assertTrue(recorded.getHeader("Accept") == null)
        val json = JSONObject(recorded.body.readUtf8())
        assertTrue(!json.has("response_format"))
        assertEquals("test/tts", json.getString("model"))
        assertEquals("Kore", json.getString("voice"))
        assertTrue(!json.has("instructions"))
        assertTrue(!json.has("provider"))
        // The style has to arrive somehow: a provider without an instructions parameter reads it
        // as a bracketed direction in front of the words, which it follows and never speaks.
        assertEquals("[Natural pacing.] Hallo", json.getString("input"))
    }

    @Test
    fun `OpenAI speech instructions use provider options instead of top level`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/pcm")
                .setBody(okio.Buffer().write(byteArrayOf(1, 0))),
        )
        val client = SpeechClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/api/v1/").toString().toHttpUrl(),
            apiKeyProvider = OpenRouterApiKeyProvider { "key" },
            retryPolicy = RetryPolicy(maxAttempts = 1, initialDelayMs = 0, maximumDelayMs = 0),
        )

        client.synthesize(
            SpeechRequest(
                model = "openai/gpt-4o-mini-tts-2025-12-15",
                text = "Hallo",
                voice = "alloy",
                instructions = "Warm and friendly.",
            ),
        )

        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(!json.has("instructions"))
        assertEquals(
            "Warm and friendly.",
            json.getJSONObject("provider")
                .getJSONObject("options")
                .getJSONObject("openai")
                .getString("instructions"),
        )
        // Carried as a parameter here, so the words stay exactly the words.
        assertEquals("Hallo", json.getString("input"))
    }

    @Test
    fun `speech failure exposes bounded routing reason without raw provider text`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Generation-Id", "gen-safe-123")
                .setBody(
                    """{"error":{"code":404,"message":"No endpoints found that support the requested parameters SECRET","metadata":{"provider_name":"DeepSeek","provider_code":"endpoint_missing"}}}""",
                ),
        )
        val events = mutableListOf<AiNetworkEvent>()
        val client = SpeechClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/api/v1/").toString().toHttpUrl(),
            apiKeyProvider = OpenRouterApiKeyProvider { "key" },
            retryPolicy = RetryPolicy(maxAttempts = 1, initialDelayMs = 0, maximumDelayMs = 0),
            observer = AiNetworkObserver(events::add),
        )

        try {
            client.synthesize(SpeechRequest("test/tts", "Hallo", "Eve"))
            throw AssertionError("Expected HTTP failure")
        } catch (failure: OpenRouterHttpException) {
            assertEquals("no_compatible_endpoint", failure.reasonCode)
            assertTrue(!failure.message.orEmpty().contains("SECRET"))
            assertEquals("/api/v1/audio/speech", failure.endpointPath)
            assertEquals("application/json", failure.responseContentType)
            assertEquals("json_error", failure.responseBodyKind)
            assertEquals("DeepSeek", failure.providerName)
            assertEquals("endpoint_missing", failure.providerErrorCode)
            assertEquals("gen-safe-123", failure.providerRequestId)
            assertTrue(!failure.errorFingerprint.isNullOrBlank())
            assertTrue("provider_code" in failure.errorMetadataKeys)
        }
        val failed = events.last() as AiNetworkEvent.Failed
        assertEquals("no_compatible_endpoint", failed.reasonCode)
        assertEquals("/api/v1/audio/speech", failed.endpointPath)
        assertEquals("json_error", failed.responseBodyKind)
        assertEquals("endpoint_missing", failed.providerErrorCode)
        assertTrue(events.first() is AiNetworkEvent.Started)
    }

    private fun assertFalseValue(value: Boolean) {
        assertTrue(!value)
    }
}
