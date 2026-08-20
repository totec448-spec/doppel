package de.totec.doppel.ai

import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenRouterImageClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `gpt image 2 uses only its declared fields and nests OpenAI moderation`() = runBlocking {
        val generated = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        server.enqueue(imageResponse(generated))
        val client = client(
            imageModels = parametersOf(
                """
                {
                  "quality": {"type": "enum", "values": ["auto", "low", "medium", "high"]},
                  "background": {"type": "enum", "values": ["auto", "transparent", "opaque"]},
                  "n": {"type": "range", "min": 1, "max": 10},
                  "input_references": {"type": "range", "min": 0, "max": 16},
                  "output_compression": {"type": "range", "min": 0, "max": 100}
                }
                """.trimIndent(),
            ),
        )

        val result =
            client.generate(
                ImageGenerationRequest(
                    model = "openai/gpt-image-2",
                    prompt = "ordinary hallway selfie",
                    references =
                        listOf(
                            ImageReferenceInput(
                                bytes = byteArrayOf(1, 2, 3),
                                mimeType = "image/png",
                            ),
                        ),
                    quality = "very_low",
                ),
            )

        assertArrayEquals(generated, result.bytes)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/images", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals(1, body.getInt("n"))
        assertEquals("low", body.getString("quality"))
        // Compression is valid only when JPEG/WebP was selected. This route currently exposes no
        // output format, so OpenAI's PNG default must not receive a compression value.
        assertFalse(body.has("output_compression"))
        assertEquals("opaque", body.getString("background"))
        // Declared by only six of the route's models, and this is not one of them.
        assertFalse(body.has("output_format"))
        val reference =
            body.getJSONArray("input_references")
                .getJSONObject(0)
                .getJSONObject("image_url")
                .getString("url")
        assertTrue(reference.startsWith("data:image/png;base64,"))
        val provider = body.getJSONObject("provider")
        assertEquals(false, provider.getBoolean("allow_fallbacks"))
        assertFalse(body.has("moderation"))
        assertEquals(
            "low",
            provider.getJSONObject("options").getJSONObject("openai").getString("moderation"),
        )
    }

    @Test
    fun `a model that declares no output settings is not asked for any`() = runBlocking {
        server.enqueue(imageResponse(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 9)))
        // Grok Imagine's real contract. Every generation used to carry output_format,
        // output_compression and background regardless, and OpenRouter answered the whole request
        // with 404 — no endpoint supports them — before xAI ever saw it. That is the failure that
        // showed up on the phone as "OpenRouter HTTP 404" and in the OpenRouter log as nothing.
        val client = client(
            imageModels = parametersOf(
                """
                {
                  "resolution": {"type": "enum", "values": ["1K", "2K"]},
                  "aspect_ratio": {"type": "enum", "values": ["1:1", "16:9"]},
                  "quality": {"type": "enum", "values": ["low", "medium"]},
                  "n": {"type": "range", "min": 1, "max": 1},
                  "input_references": {"type": "range", "min": 0, "max": 3}
                }
                """.trimIndent(),
            ),
        )

        client.generate(
            ImageGenerationRequest(
                model = "x-ai/grok-imagine-image-2.0",
                prompt = "casual phone selfie",
                references = List(5) { ImageReferenceInput(byteArrayOf(1, 2, 3), "image/jpeg") },
                quality = "very_low",
            ),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("output_format"))
        assertFalse(body.has("output_compression"))
        assertFalse(body.has("background"))
        assertEquals("low", body.getString("quality"))
        assertEquals(1, body.getInt("n"))
        // Five were on file, three is the most this model routes to.
        assertEquals(3, body.getJSONArray("input_references").length())
        assertFalse(body.getJSONObject("provider").has("options"))
    }

    @Test
    fun `compression is sent only with a declared jpeg or webp format`() = runBlocking {
        server.enqueue(imageResponse(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 8)))
        val client =
            client(
                imageModels =
                    parametersOf(
                        """{
                          "output_format": {"type": "enum", "values": ["jpeg", "png"]},
                          "output_compression": {"type": "range", "min": 0, "max": 100}
                        }""",
                    ),
            )

        client.generate(
            ImageGenerationRequest(
                model = "some/model",
                prompt = "a red cube",
                quality = "very_low",
            ),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("jpeg", body.getString("output_format"))
        assertEquals(42, body.getInt("output_compression"))
    }

    @Test
    fun `an unknown contract asks for nothing beyond the picture`() = runBlocking {
        server.enqueue(imageResponse(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 7)))
        // The catalog is unreachable. Guessing is what produced the 404 in the first place, so the
        // request keeps to the two fields every image model on the route takes.
        val client = client(imageModels = ImageModelParameterSource.UNKNOWN)

        client.generate(
            ImageGenerationRequest(
                model = "some/new-model",
                prompt = "a red cube",
                quality = "high",
            ),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("some/new-model", body.getString("model"))
        assertEquals("a red cube", body.getString("prompt"))
        assertFalse(body.has("quality"))
        assertFalse(body.has("n"))
        assertFalse(body.has("output_format"))
        assertFalse(body.has("output_compression"))
        assertFalse(body.has("background"))
    }

    @Test
    fun `a quality the model does not offer falls to the nearest one it does`() = runBlocking {
        server.enqueue(imageResponse(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 5)))
        val client = client(
            imageModels = parametersOf(
                """{"quality": {"type": "enum", "values": ["medium", "auto"]}}""",
            ),
        )

        client.generate(
            ImageGenerationRequest(
                model = "some/model",
                prompt = "a red cube",
                quality = "low",
            ),
        )

        // `auto` is the provider's default, not a price, so it stays the last resort.
        assertEquals("medium", JSONObject(server.takeRequest().body.readUtf8()).getString("quality"))
    }

    @Test
    fun `the images catalog is read once and reused`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data": [{"id": "some/model", "supported_parameters": {
                  "n": {"type": "range", "min": 1, "max": 1},
                  "output_format": {"type": "enum", "values": ["png", "jpeg"]}
                }}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(imageResponse(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 3)))
        server.enqueue(imageResponse(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 4)))
        val client = client(imageModels = null)
        val request =
            ImageGenerationRequest(model = "some/model", prompt = "a red cube", quality = "low")

        client.generate(request)
        client.generate(request)

        assertEquals("/api/v1/images/models", server.takeRequest().path)
        assertEquals(
            "jpeg",
            JSONObject(server.takeRequest().body.readUtf8()).getString("output_format"),
        )
        // The second generation asks the catalog nothing: it is already in hand.
        assertEquals("/api/v1/images", server.takeRequest().path)
    }

    @Test
    fun `a routing failure names the field no endpoint takes`() {
        val message =
            "No endpoints found that support the provided 'output_format' value. " +
                "Try a different model."

        assertEquals("output_format", unsupportedParameterIn(message.lowercase()))
        // Anything that is not a bare field name is dropped rather than logged, because an upstream
        // error can quote the prompt back.
        assertEquals(
            null,
            unsupportedParameterIn("no endpoints found that support 'a girl in a red dress'"),
        )
    }

    private fun imageResponse(bytes: ByteArray): MockResponse =
        MockResponse().setBody(
            JSONObject()
                .put(
                    "data",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("b64_json", Base64.getEncoder().encodeToString(bytes))
                            .put("media_type", "image/jpeg"),
                    ),
                ).toString(),
        )

    /** [imageModels] null means "let the client fetch the real route", as it does on the phone. */
    private fun client(imageModels: ImageModelParameterSource?): OpenRouterImageClient {
        val baseUrl = server.url("/api/v1/").toString().toHttpUrl()
        val apiKeyProvider = OpenRouterApiKeyProvider { "key" }
        return OpenRouterImageClient(
            httpClient = OkHttpClient(),
            baseUrl = baseUrl,
            apiKeyProvider = apiKeyProvider,
            retryPolicy = RetryPolicy(maxAttempts = 1, initialDelayMs = 0, maximumDelayMs = 0),
            imageModels =
                imageModels
                    ?: OpenRouterImageModelCatalog(
                        httpClient = OkHttpClient(),
                        baseUrl = baseUrl,
                        apiKeyProvider = apiKeyProvider,
                    ),
        )
    }

    private fun parametersOf(supportedParameters: String): ImageModelParameterSource {
        val parsed = ImageModelParameters.parse(JSONObject(supportedParameters))
        return ImageModelParameterSource { parsed }
    }
}
