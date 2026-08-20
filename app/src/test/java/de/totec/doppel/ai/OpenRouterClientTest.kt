package de.totec.doppel.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenRouterClientTest {
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
    fun `OpenRouter attribution uses the provider documented title header`() {
        val headers =
            authHeaders(
                apiKey = "secret",
                attribution =
                    OpenRouterAttribution(
                        referer = "https://example.invalid/app",
                        title = "WhatsApp Android Bridge",
                    ),
            )

        assertEquals("https://example.invalid/app", headers["HTTP-Referer"])
        assertEquals("WhatsApp Android Bridge", headers["X-OpenRouter-Title"])
        assertEquals(null, headers["X-Title"])
    }

    @Test
    fun `SSE accumulates indexed tool fragments content and usage`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"model":"test/actual","choices":[{"delta":{"content":"Kurz "}}]}

                    data: {"choices":[{"delta":{"content":"gesagt."}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"search_current_chat","arguments":"{\"query\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Kaffee\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":4,"total_tokens":24,"prompt_tokens_details":{"cached_tokens":12,"cache_write_tokens":3}}}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val client = client()

        val result = client.complete(simpleRequest(stream = true))

        assertEquals("Kurz gesagt.", result.content)
        assertEquals("test/actual", result.model)
        assertEquals("tool_calls", result.finishReason)
        assertEquals("search_current_chat", result.toolCalls.single().name)
        assertEquals(
            "Kaffee",
            JSONObject(result.toolCalls.single().argumentsJson).getString("query"),
        )
        assertEquals(12, result.usage?.cachedPromptTokens)
        assertEquals(3, result.usage?.cacheWriteTokens)
    }

    @Test
    fun `stream request parses same JSON response without second call`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "model":"test/fallback",
                      "choices":[{
                        "message":{
                          "content":"Antwort",
                          "tool_calls":[{
                            "id":"c1",
                            "function":{
                              "name":"list_chats",
                              "arguments":{"limit":2}
                            }
                          }]
                        },
                        "finish_reason":"stop"
                      }],
                      "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}
                    }
                    """.trimIndent(),
                ),
        )
        val client = client()

        val result = client.complete(simpleRequest(stream = true))

        assertEquals("Antwort", result.content)
        assertEquals("""{"limit":2}""", result.toolCalls.single().argumentsJson)
        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val requestJson = JSONObject(requireNotNull(recorded.body.readUtf8()))
        assertTrue(requestJson.getBoolean("stream"))
    }

    @Test
    fun `429 retries but a normal client error does not`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setBody("""{"error":{"message":"busy"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
                ),
        )
        val retrying = client()
        assertEquals("ok", retrying.complete(simpleRequest(stream = false)).content)
        assertEquals(2, server.requestCount)

        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"bad"}}"""),
        )
        val before = server.requestCount
        try {
            retrying.complete(simpleRequest(stream = false))
            throw AssertionError("Expected HTTP failure")
        } catch (failure: OpenRouterHttpException) {
            assertEquals(400, failure.statusCode)
        }
        assertEquals(before + 1, server.requestCount)
    }

    @Test
    fun `provider routing error is reduced to privacy safe reason code`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody(
                    """{"error":{"message":"No endpoints found that support the requested parameters and private input"}}""",
                ),
        )

        try {
            client().complete(simpleRequest(stream = false))
            throw AssertionError("Expected HTTP failure")
        } catch (failure: OpenRouterHttpException) {
            assertEquals(404, failure.statusCode)
            assertEquals("no_compatible_endpoint", failure.reasonCode)
            assertTrue(!failure.message.orEmpty().contains("private input"))
        }
    }

    @Test
    fun `request encoding marks only stable cache boundary`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
        )
        val client = client()
        val settings = testSettings(preferStreaming = false)
        val bundle = PromptAssembler().assemble(
            settings,
            testTurn(),
            ToolRegistry.allowed(settings.tools),
        )

        client.complete(
            ChatCompletionRequest(
                model = settings.model(ModelRole.MAIN),
                messages = bundle.messages,
                stream = false,
            ),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val messages = body.getJSONArray("messages")
        val prefixContent = messages.getJSONObject(0).getJSONArray("content")
        assertNotNull(
            prefixContent.getJSONObject(0).getJSONObject("cache_control"),
        )
        assertTrue(messages.getJSONObject(messages.length() - 1).get("content") is String)
    }

    @Test
    fun `models are pinned to the maker that built them and the router may not stray`() {
        val body =
            client().encodeRequest(
                simpleRequest(stream = false).copy(model = "deepseek/deepseek-v4-flash-0731"),
            )

        val provider = body.getJSONObject("provider")
        assertEquals("deepseek", provider.getJSONArray("only").getString(0))
        assertEquals(1, provider.getJSONArray("only").length())
        assertEquals("deepseek", provider.getJSONArray("order").getString(0))
        assertEquals(1, provider.getJSONArray("order").length())
        // The whole point of the pin: with fallbacks left on, OpenRouter reads the lists as a
        // preference and hands the request to whichever third party is cheaper at that moment.
        assertFalse(provider.getBoolean("allow_fallbacks"))
    }

    /**
     * There is no client-side fallback either. A host the maker cannot serve fails the turn — a
     * detour to another provider is exactly what the pin exists to prevent, and it used to happen
     * without a trace.
     */
    @Test
    fun `a first-party outage fails instead of quietly moving to another host`() = runBlocking {
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"message":"provider is down"}}"""),
            )
        }

        val failure = runCatching {
            client().complete(
                simpleRequest(stream = false).copy(model = "deepseek/deepseek-v4-flash-0731"),
            )
        }.exceptionOrNull()

        assertTrue(failure is OpenRouterHttpException)
        // Two attempts, both pinned; nothing was re-issued without the pin.
        assertEquals(2, server.requestCount)
        repeat(2) { assertTrue(JSONObject(server.takeRequest().body.readUtf8()).has("provider")) }
    }

    /** A pin that the router ignores has to be visible, not silently cheaper-routed. */
    @Test
    fun `a reply served by a host other than the pinned one is recognised`() {
        assertTrue(servedAgainstPin("original_deepseek_only", "GMICloud"))
        assertTrue(servedAgainstPin("original_deepseek_only", "CoreWeave"))
        assertFalse(servedAgainstPin("original_deepseek_only", "DeepSeek"))
        // The endpoint may name a quantization variant, which is still the pinned provider.
        assertFalse(servedAgainstPin("original_deepseek_only", "DeepSeek/fp8"))
        assertFalse(servedAgainstPin("original_google-ai-studio_only", "Google AI Studio"))
        // Nothing was pinned, or nothing is known about who answered.
        assertFalse(servedAgainstPin(null, "GMICloud"))
        assertFalse(servedAgainstPin("original_deepseek_only", null))
    }

    @Test
    fun `pinning is skipped for makers that do not serve their own models here`() {
        val body =
            client().encodeRequest(
                simpleRequest(stream = false).copy(model = "meta-llama/llama-4-maverick"),
            )

        assertTrue(!body.has("provider"))
    }

    @Test
    fun `pinning can be switched off per request`() {
        val body =
            client(firstPartyProviderOnly = { false }).encodeRequest(
                simpleRequest(stream = false).copy(model = "deepseek/deepseek-v4-flash-0731"),
            )

        assertTrue(!body.has("provider"))
    }

    @Test
    fun `reasoning effort and explicit token budget cannot be combined`() {
        val failure =
            runCatching {
                SamplingSettings(
                    reasoningEffort = ReasoningEffort.MAX,
                    reasoningMaxTokens = 3_000,
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `cancelling coroutine cancels the in-flight OkHttp call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val httpClient = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val events = mutableListOf<AiNetworkEvent>()
        val client = client(httpClient, observer = { events += it })

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            client.complete(simpleRequest(stream = true))
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancelAndJoin()

        var polls = 0
        while (httpClient.dispatcher.runningCallsCount() > 0 && polls < 100) {
            delay(10)
            polls += 1
        }
        assertTrue(job.isCancelled)
        assertEquals(0, httpClient.dispatcher.runningCallsCount())
        assertTrue(events.last() is AiNetworkEvent.Cancelled)
    }

    @Test
    fun `a streamed request that produced nothing is abandoned after the stall deadline and asked again`() =
        runBlocking {
            // Nothing fails here: the first socket stays open and simply never answers, which is
            // what a very high time-to-first-token looks like from the client side. Tokens were
            // asked for as they are produced and none arrived, so nothing is being generated yet
            // and the re-issue cannot be paying for the same answer twice.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"doch noch da"}}]}"""),
            )
            val events = mutableListOf<AiNetworkEvent>()
            val client = client(
                observer = { events += it },
                stallTimeoutMs = 200,
            )

            val result = client.complete(simpleRequest(stream = true))

            assertEquals("doch noch da", result.content)
            assertEquals(2, server.requestCount)
            val retry = events.filterIsInstance<AiNetworkEvent.Retrying>().single()
            assertEquals("stalled", retry.reasonCode)
        }

    /**
     * The same silence on a buffered request means nothing of the sort. A non-streamed completion
     * says nothing at all until the whole answer is written, so this is what a completion in
     * progress looks like — and it is billed on delivery whether or not this client is still
     * listening. The turn fails on one bill instead of succeeding on two.
     */
    @Test
    fun `a stalled buffered request is not asked again`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"zu spät"}}]}"""),
        )
        val events = mutableListOf<AiNetworkEvent>()
        val client = client(
            observer = { events += it },
            stallTimeoutMs = 200,
        )

        val failure =
            runCatching { client.complete(simpleRequest(stream = false)) }.exceptionOrNull()

        assertTrue(failure is OpenRouterStallException)
        assertEquals(1, server.requestCount)
        assertTrue(events.filterIsInstance<AiNetworkEvent.Retrying>().isEmpty())
    }

    /**
     * The deadline measures silence, not duration. This body takes far longer than the deadline to
     * arrive but never pauses for it, which is what a memory write condensing thousands of messages
     * looks like from here. A client that cannot tell the two apart kills exactly the answers that
     * cost the most to produce, and pays for them anyway.
     */
    @Test
    fun `a stream that keeps producing is not abandoned for outliving the deadline`() = runBlocking {
        val chunks = 12
        val body = buildString {
            repeat(chunks) { index ->
                append("data: {\"choices\":[{\"delta\":{\"content\":\"$index\"}}]}\n\n")
            }
            append("data: [DONE]\n\n")
        }
        val deadlineMs = 400L
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
                .throttleBody(64, 100, TimeUnit.MILLISECONDS),
        )
        val client = client(stallTimeoutMs = deadlineMs)

        val startedAt = System.currentTimeMillis()
        val result = client.complete(simpleRequest(stream = true))
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals((0 until chunks).joinToString(""), result.content)
        assertEquals(1, server.requestCount)
        // Proves the test is actually testing something: had the deadline still measured duration
        // rather than silence, a call this much longer than it could not have survived.
        assertTrue(elapsed > deadlineMs)
    }

    @Test
    fun `the stall deadline never fires before an explicitly configured HTTP timeout`() {
        // Switched off: the watchdog is the only remaining bound, so it must be the default.
        assertEquals(
            OpenRouterClient.DEFAULT_STALL_TIMEOUT_MS,
            OpenRouterClient.stallDeadlineFor(0),
        )
        // Shorter than the watchdog: OkHttp fails first with a more precise error, and does so
        // without the watchdog having to know about it.
        assertEquals(
            OpenRouterClient.DEFAULT_STALL_TIMEOUT_MS,
            OpenRouterClient.stallDeadlineFor(30_000),
        )
        // Deliberately raised for a slow model: the watchdog stays behind the setting instead of
        // silently capping it at two minutes.
        assertTrue(OpenRouterClient.stallDeadlineFor(300_000) > 300_000)
    }

    /**
     * The tokens are billed as output whether they come back or not, so excluding them only ever
     * cost the account of why an answer looks the way it does. This asserts the field is present
     * and false rather than absent: absent means "provider's choice", and the point is that this
     * one is made here.
     */
    @Test
    fun `reasoning is asked for rather than excluded`() {
        val body =
            client().encodeRequest(
                simpleRequest(stream = true).copy(
                    sampling = SamplingSettings(reasoningEffort = ReasoningEffort.HIGH),
                ),
            )

        assertFalse(body.getJSONObject("reasoning").getBoolean("exclude"))
    }

    @Test
    fun `streamed reasoning reaches the sink before the answer is complete`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"reasoning":"Sie fragt nach "}}]}

                    data: {"choices":[{"delta":{"reasoning":"dem Wetter."}}]}

                    data: {"choices":[{"delta":{"content":"Regnet."}}],"finish_reason":"stop"}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val seen = mutableListOf<String>()
        val seenContent = mutableListOf<String>()
        val sink = object : LiveTokenSink() {
            override fun emitReasoning(text: String) {
                seen += text
            }

            override fun emitContent(text: String) {
                seenContent += text
            }
        }

        val result = withContext(sink) { client().complete(simpleRequest(stream = true)) }

        assertEquals("Regnet.", result.content)
        assertEquals("Sie fragt nach dem Wetter.", result.reasoning)
        // Batched, so the split is not fixed — but nothing may be left in the buffer at the end.
        assertEquals("Sie fragt nach dem Wetter.", seen.joinToString(""))
        assertEquals("Regnet.", seenContent.joinToString(""))
    }

    /**
     * The providers that sign or redact their reasoning send it as structured blocks instead of a
     * flat string. Only the readable text of those is of interest; a turn that never sees the flat
     * field must still end up with something to show.
     */
    @Test
    fun `structured reasoning blocks are read when there is no flat field`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"Kurz nachgedacht."},{"type":"reasoning.encrypted","data":"AAAA"}]}}]}

                    data: {"choices":[{"delta":{"content":"Ja."}}],"finish_reason":"stop"}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val result = client().complete(simpleRequest(stream = true))

        assertEquals("Kurz nachgedacht.", result.reasoning)
    }

    /**
     * Providers that stream the flat field tend to repeat the whole chain in the structured one on
     * the closing chunk. Read twice, the trace would show the same thought twice.
     */
    @Test
    fun `a repeated structured copy of streamed reasoning is ignored`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"reasoning":"Einmal gedacht."}}]}

                    data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"Einmal gedacht."}]}}]}

                    data: {"choices":[{"delta":{"content":"Ok."}}],"finish_reason":"stop"}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val result = client().complete(simpleRequest(stream = true))

        assertEquals("Einmal gedacht.", result.reasoning)
    }

    @Test
    fun `a non-streamed answer still carries its reasoning`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "choices":[{
                        "message":{"content":"Antwort","reasoning":"Erst überlegt."},
                        "finish_reason":"stop"
                      }]
                    }
                    """.trimIndent(),
                ),
        )

        val result = client().complete(simpleRequest(stream = false))

        assertEquals("Erst überlegt.", result.reasoning)
    }

    private fun client(
        httpClient: OkHttpClient = OkHttpClient(),
        observer: AiNetworkObserver = AiNetworkObserver.NONE,
        stallTimeoutMs: Long = OpenRouterClient.DEFAULT_STALL_TIMEOUT_MS,
        firstPartyProviderOnly: () -> Boolean = { true },
    ) = OpenRouterClient(
        httpClient = httpClient,
        baseUrl = server.url("/api/v1/").toString().toHttpUrl(),
        apiKeyProvider = OpenRouterApiKeyProvider { "test-key" },
        retryPolicy = RetryPolicy(
            maxAttempts = 2,
            initialDelayMs = 0,
            maximumDelayMs = 0,
        ),
        observer = observer,
        stallTimeoutMs = stallTimeoutMs,
        firstPartyProviderOnly = firstPartyProviderOnly,
    )

    private fun simpleRequest(stream: Boolean) = ChatCompletionRequest(
        model = "test/main",
        messages = listOf(AiMessage.text(ChatRole.USER, "test")),
        stream = stream,
    )
}
