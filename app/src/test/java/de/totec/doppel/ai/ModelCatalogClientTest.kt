package de.totec.doppel.ai

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ModelCatalogClientTest {
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
    fun `capabilities context pricing and parameters are parsed from model objects`() {
        val client = client(InMemoryModelCatalogCache())
        val models = client.parseModels(
            """
            {"data":[
              {
                "id":"vendor/omni",
                "name":"Omni",
                "description":"Multimodal",
                "created":1785513600,
                "context_length":128000,
                "architecture":{
                  "input_modalities":["text","image","audio","video"],
                  "output_modalities":["text","audio"]
                },
                "supported_parameters":["tools","reasoning","response_format"],
                "reasoning":{
                  "supported_efforts":["minimal","low","high"],
                  "default_effort":"minimal",
                  "mandatory":true,
                  "supports_max_tokens":true
                },
                "supported_voices":["Kore","Puck"],
                "pricing":{"prompt":"0.000001","completion":"0.000002"}
              },
              {
                "id":"vendor/legacy",
                "architecture":{"modality":"text+image->text"},
                "top_provider":{"context_length":4096},
                "supported_parameters":[]
              }
            ]}
            """.trimIndent(),
        )

        val omni = models.first()
        assertEquals(128_000, omni.contextLength)
        assertEquals(1_785_513_600L, omni.createdAtEpochSeconds)
        assertTrue(ModelCapability.IMAGE_INPUT in omni.capabilities)
        assertTrue(ModelCapability.AUDIO_INPUT in omni.capabilities)
        assertTrue(ModelCapability.VIDEO_INPUT in omni.capabilities)
        assertTrue(ModelCapability.AUDIO_OUTPUT in omni.capabilities)
        assertTrue(ModelCapability.TOOLS in omni.capabilities)
        assertTrue(ModelCapability.REASONING in omni.capabilities)
        assertTrue(ModelCapability.STRUCTURED_OUTPUT in omni.capabilities)
        assertTrue(omni.supports(ModelRole.MAIN))
        assertTrue(omni.supports(ModelRole.MEDIA))
        assertTrue(omni.supports(ModelRole.TTS))
        assertEquals(listOf("minimal", "low", "high"), omni.reasoning?.supportedEfforts)
        assertEquals("minimal", omni.reasoning?.defaultEffort)
        assertTrue(omni.reasoning?.mandatory == true)
        assertTrue(omni.reasoning?.supportsMaxTokens == true)
        assertEquals(listOf("Kore", "Puck"), omni.supportedVoices)
        assertEquals(0.000001, omni.pricing.promptPerToken!!, 0.0)

        val legacy = models.last()
        assertEquals(4096, legacy.contextLength)
        assertTrue(ModelCapability.IMAGE_INPUT in legacy.capabilities)
        assertTrue(ModelCapability.TEXT_OUTPUT in legacy.capabilities)
        assertFalse(legacy.supports(ModelRole.MAIN))
        assertFalse(legacy.supports(ModelRole.TTS))
    }

    @Test
    fun `fresh catalog is cached and stale catalog survives provider outage`() = runBlocking {
        val cache = InMemoryModelCatalogCache()
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":[{"id":"vendor/model","architecture":{"modality":"text->text"}}]}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":[{"id":"vendor/tts","architecture":{"input_modalities":["text"],"output_modalities":["speech"]}}]}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":[{"id":"vendor/image","architecture":{"input_modalities":["text","image"],"output_modalities":["image"]}}]}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":[{"id":"vendor/asr","architecture":{"input_modalities":["audio"],"output_modalities":["transcription"]}}]}""",
                ),
        )
        val client = client(cache)

        val fresh = client.getCatalog()
        assertFalse(fresh.stale)
        assertEquals(
            listOf("vendor/model", "vendor/tts", "vendor/image", "vendor/asr"),
            fresh.models.map(OpenRouterModel::id),
        )
        assertEquals(4, server.requestCount)

        val cached = client.getCatalog()
        assertFalse(cached.stale)
        assertEquals(4, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(401))
        val stale = client.getCatalog(forceRefresh = true)
        assertTrue(stale.stale)
        assertEquals(
            listOf("vendor/model", "vendor/tts", "vendor/image", "vendor/asr"),
            stale.models.map(OpenRouterModel::id),
        )
        assertEquals(5, server.requestCount)
        val normalRequest = server.takeRequest()
        val speechRequest = server.takeRequest()
        val imageRequest = server.takeRequest()
        val transcriptionRequest = server.takeRequest()
        server.takeRequest()
        assertEquals(null, normalRequest.requestUrl?.queryParameter("output_modalities"))
        assertEquals("speech", speechRequest.requestUrl?.queryParameter("output_modalities"))
        assertEquals("image", imageRequest.requestUrl?.queryParameter("output_modalities"))
        assertEquals(
            "transcription",
            transcriptionRequest.requestUrl?.queryParameter("output_modalities"),
        )
        assertEquals("newest", speechRequest.requestUrl?.queryParameter("sort"))
    }

    @Test
    fun `speech output is selectable as tts model`() {
        val model = client(InMemoryModelCatalogCache()).parseModels(
            """{"data":[{"id":"vendor/tts","architecture":{"input_modalities":["text"],"output_modalities":["speech"]}}]}""",
        ).single()

        assertTrue(model.supports(ModelRole.TTS))
        assertFalse(model.supports(ModelRole.MAIN))
    }

    @Test
    fun `only dedicated transcription output is selectable as transcription model`() {
        val models = client(InMemoryModelCatalogCache()).parseModels(
            """
            {"data":[
              {"id":"vendor/chat-audio","architecture":{"input_modalities":["audio"],"output_modalities":["text"]}},
              {"id":"vendor/asr","architecture":{"modality":"audio->transcription"}}
            ]}
            """.trimIndent(),
        )

        assertFalse(models.first().supports(ModelRole.TRANSCRIBE))
        assertTrue(models.last().supports(ModelRole.TRANSCRIBE))
        assertTrue(ModelCapability.TRANSCRIPTION_OUTPUT in models.last().capabilities)
    }

    @Test
    fun `duration transcription rates are normalized to one comparable hour`() {
        val models = client(InMemoryModelCatalogCache()).parseModels(
            """
            {"data":[
              {"id":"vendor/per-second","architecture":{"modality":"audio->transcription"},"pricing":{"prompt":"0.000035","completion":"0"}},
              {"id":"vendor/per-minute","architecture":{"modality":"audio->transcription"},"pricing":{"prompt":"0.006","completion":"0"}},
              {"id":"vendor/per-hour","architecture":{"modality":"audio->transcription"},"pricing":{"prompt":"0.10","completion":"0"}},
              {"id":"vendor/per-token","description":"It is priced per token (input and output).","architecture":{"modality":"audio->transcription"},"pricing":{"prompt":"0.00000125","completion":"0.000005"}}
            ]}
            """.trimIndent(),
        )

        assertEquals(0.126, models[0].pricing.transcriptionPerHour!!, 0.0000001)
        assertEquals(0.36, models[1].pricing.transcriptionPerHour!!, 0.0000001)
        assertEquals(0.10, models[2].pricing.transcriptionPerHour!!, 0.0000001)
        assertEquals(null, models[3].pricing.transcriptionPerHour)
    }

    @Test
    fun `image output and per-image prices survive catalog parsing`() {
        val models = client(InMemoryModelCatalogCache()).parseModels(
            """
            {"data":[
              {"id":"vendor/per-image","architecture":{"modality":"text->image"},"pricing":{"prompt":"0","completion":"0","image":"0.01"}},
              {"id":"vendor/per-image-token","architecture":{"modality":"text->image"},"pricing":{"prompt":"0.000008","completion":"0.000008","image_output":"0.00003"}}
            ]}
            """.trimIndent(),
        )

        assertEquals(0.01, models[0].pricing.imagePerUnit!!, 0.0)
        assertEquals(0.00003, models[1].pricing.imageOutputPerToken!!, 0.0)
    }

    private fun client(cache: ModelCatalogCache) = ModelCatalogClient(
        httpClient = OkHttpClient(),
        baseUrl = server.url("/api/v1/").toString().toHttpUrl(),
        apiKeyProvider = OpenRouterApiKeyProvider { "key" },
        cache = cache,
        retryPolicy = RetryPolicy(
            maxAttempts = 1,
            initialDelayMs = 0,
            maximumDelayMs = 0,
        ),
        clock = Clock.fixed(
            Instant.parse("2026-07-30T10:00:00Z"),
            ZoneOffset.UTC,
        ),
    )
}
