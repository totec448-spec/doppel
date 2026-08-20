package de.totec.doppel.engine

import de.totec.doppel.domain.ChatEventKind
import de.totec.doppel.domain.IncomingEvent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotEngineTest {
    private var engine: BotEngine? = null

    @After
    fun closeEngine() {
        engine?.close()
    }

    @Test
    fun `worker drains every already queued priority turn`() = runBlocking {
        val ai = RecordingAi(blockChat = "first")
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        engine!!.accept(event("first", "m1", 1))
        ai.blockStarted.await()
        engine!!.accept(event("second", "m2", 2))
        engine!!.accept(event("third", "m3", 3))
        ai.releaseBlock.complete(Unit)

        awaitCondition { ai.inputs.map(TurnInput::chatJid).containsAll(listOf("first", "second", "third")) }
        assertEquals(
            setOf("first", "second", "third"),
            ai.inputs.map(TurnInput::chatJid).toSet(),
        )
    }

    @Test
    fun `due scheduled reply absorbs same persona messages then runs before switched persona`() =
        runBlocking {
            var persona = "assistant"
            val clock = GateClock()
            val ai = RecordingAi()
            val store = FakeEngineStore()
            engine =
                newEngine(
                    this,
                    store,
                    ai,
                    clock = clock,
                    liveSettings = {
                        EngineSettingsSnapshot(
                            enabled = true,
                            batchWindowMs = 30_000,
                            replyPreset = ReplyPreset.HUMAN,
                            markRead = false,
                            proactiveLevel = 5,
                            personality = persona,
                        )
                    },
                )

            val assistantFirst = event("contact", "assistant-first", 1)
            val assistantDuringSleep = event("contact", "assistant-during-sleep", 2)
            engine!!.accept(assistantFirst)
            engine!!.accept(assistantDuringSleep)
            persona = "lina"
            val linaVideo = event("contact", "lina-video", 3)
            engine!!.accept(linaVideo)

            awaitCondition { clock.waiters > 0 }
            val scheduled =
                async {
                    engine!!.enqueueProactive(
                        seed = ProactiveSeed(assistantFirst.copy(personaKey = "assistant")),
                        level = 5,
                        request =
                            ProactiveTurnRequest(
                                reason = "You set a timer to write this person right now.",
                                trailingDirective = true,
                                personaKey = "assistant",
                                scheduledFollowUp = true,
                            ),
                    )
                }
            clock.open()

            withTimeout(3_000L) { scheduled.await() }
            awaitCondition { ai.inputs.size == 2 }
            assertEquals(
                listOf("contact#assistant", "contact#lina"),
                ai.inputs.map(TurnInput::conversationKey),
            )
            assertEquals(
                listOf("assistant-first", "assistant-during-sleep"),
                ai.inputs.first().newestEvents.map(IncomingEvent::messageId),
            )
            assertEquals(
                "You set a timer to write this person right now.",
                ai.inputs.first().proactiveTrailingDirective,
            )
            assertEquals(listOf("lina-video"), ai.inputs.last().newestEvents.map(IncomingEvent::messageId))
            assertTrue(
                store.dispositions
                    .filter { it.disposition == "handled_silently" }
                    .map(RecordedDisposition::eventId)
                    .containsAll(
                        listOf("event-assistant-first", "event-assistant-during-sleep"),
                    ),
            )
        }

    @Test
    fun `new burst replaces stale queued turn without losing its accepted input`() = runBlocking {
        val ai = RecordingAi(blockChat = "blocker")
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        engine!!.accept(event("blocker", "block", 1))
        ai.blockStarted.await()
        engine!!.accept(event("target", "old", 2, text = "old"))
        delay(40)
        engine!!.accept(event("target", "new", 3, text = "new"))
        delay(40)
        ai.releaseBlock.complete(Unit)

        awaitCondition { ai.inputs.any { it.chatJid == "target" } }
        delay(100)
        val targetTurns = ai.inputs.filter { it.chatJid == "target" }
        assertEquals(1, targetTurns.size)
        assertEquals(
            listOf("old", "new"),
            targetTurns.single().newestEvents.map(IncomingEvent::messageId),
        )
    }

    @Test
    fun `current persisted batch is excluded from history before AI`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        engine!!.accept(event("chat", "current", 1, text = "hello"))

        awaitCondition { ai.inputs.isNotEmpty() }
        val input = ai.inputs.single()
        assertEquals(listOf("current"), input.newestEvents.map(IncomingEvent::messageId))
        assertFalse(input.history.any { it.id == "current" })
    }

    @Test
    fun `persona switch opens an isolated history for the same physical chat`() = runBlocking {
        var persona = "female"
        val ai = RecordingAi(output = { TurnOutput(bubbles = listOf("answer-${it.settings.personality}")) })
        val store = FakeEngineStore()
        engine =
            newEngine(
                this,
                store,
                ai,
                liveSettings = {
                    EngineSettingsSnapshot(
                        enabled = true,
                        batchWindowMs = 0,
                        replyPreset = ReplyPreset.INSTANT,
                        markRead = false,
                        proactiveLevel = 1,
                        personality = persona,
                    )
                },
            )

        engine!!.accept(event("chat", "female-message", 1))
        awaitCondition { store.assistantRecords.any { it.conversationKey == "chat#female" } }
        persona = "male"
        engine!!.accept(event("chat", "male-message", 2))
        awaitCondition { ai.inputs.size == 2 }

        assertEquals(listOf("chat#female", "chat#male"), ai.inputs.map(TurnInput::conversationKey))
        assertTrue(ai.inputs.last().history.isEmpty())
        assertFalse(
            ai.inputs.last().history.any {
                it.id == "female-message" || it.text == "answer-female"
            },
        )
    }

    @Test
    fun `imported memory hold blocks the next turn until the importer releases it`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)
        val hold = engine!!.holdForMemoryWrite("chat#human")
        hold.awaitReady()

        engine!!.accept(event("chat", "during-import", 1).copy(personaKey = "human"))
        delay(100)
        assertTrue("AI started while imported memory was incomplete", ai.inputs.isEmpty())

        hold.close()
        awaitCondition { ai.inputs.isNotEmpty() }
        assertEquals("during-import", ai.inputs.single().newestEvents.single().messageId)
    }

    @Test
    fun `history request uses configured retained overlap plus current batch`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        engine =
            newEngine(
                this,
                store,
                ai,
                settings =
                    EngineSettingsSnapshot(
                        batchWindowMs = 0,
                        replyPreset = ReplyPreset.INSTANT,
                        markRead = false,
                        proactiveLevel = 1,
                        historyLimit = 12,
                    ),
            )

        engine!!.accept(event("chat", "current", 1))

        awaitCondition { ai.inputs.isNotEmpty() }
        assertEquals(listOf(13), store.historyLoadLimits)
    }

    @Test
    fun `empty reactions never start AI while stickers remain visible context`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        val reaction =
            event("chat", "reaction", 1).copy(kind = ChatEventKind.REACTION)
        val sticker =
            event("chat", "sticker", 2, text = "")
                .copy(
                    media =
                        de.totec.doppel.domain.MediaReference(
                            id = "sticker-media",
                            kind = de.totec.doppel.domain.MediaKind.STICKER,
                            mimeType = "image/webp",
                            sizeBytes = 10,
                        ),
                )

        assertEquals(BotEngine.AcceptResult.IGNORED, engine!!.accept(reaction))
        assertEquals(BotEngine.AcceptResult.QUEUED, engine!!.accept(sticker))
        awaitCondition { ai.inputs.isNotEmpty() }
        assertEquals(listOf("sticker"), ai.inputs.single().newestEvents.map(IncomingEvent::messageId))
    }

    /**
     * Nobody expects a message back for tapping an emoji, and sending one turns a
     * throwaway gesture into an unprompted message — the reply-ratio signal this
     * account can least afford. Groups always worked this way; a one-to-one
     * reaction used to pull her online for an answer nobody asked for.
     */
    @Test
    fun `a one-to-one reaction stays context and is never answered`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        val reaction =
            event("chat", "reaction", 1)
                .copy(kind = ChatEventKind.REACTION, isGroup = false, reactionEmoji = "😯")

        assertEquals(BotEngine.AcceptResult.IGNORED, engine!!.accept(reaction))
        // In the conversation, so every later turn reads that she was reacted to.
        assertTrue("reaction" in store.persistedMessageIds)

        // The next real message is answered, and carries the reaction as history.
        assertEquals(BotEngine.AcceptResult.QUEUED, engine!!.accept(event("chat", "m1", 2)))
        awaitCondition { ai.inputs.isNotEmpty() }
        assertEquals(
            listOf("m1"),
            ai.inputs.single().newestEvents.map(IncomingEvent::messageId),
        )
    }

    @Test
    fun `group trigger is stripped before AI`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        engine =
            newEngine(
                this,
                store,
                ai,
                settings =
                    EngineSettingsSnapshot(
                        batchWindowMs = 0,
                        replyPreset = ReplyPreset.INSTANT,
                        markRead = false,
                        proactiveLevel = 1,
                        groupTrigger = "@bot",
                    ),
            )

        engine!!.accept(
            event("group", "m1", 1, text = "  @bot   hello").copy(isGroup = true),
        )

        awaitCondition { ai.inputs.isNotEmpty() }
        assertEquals("hello", ai.inputs.single().newestEvents.single().text)
    }

    @Test
    fun `turn exception is recorded and worker continues`() = runBlocking {
        val ai = RecordingAi(failChat = "bad")
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        engine!!.accept(event("bad", "bad-1", 1))
        engine!!.accept(event("good", "good-1", 2))

        awaitCondition { ai.inputs.any { it.chatJid == "good" } }
        awaitCondition { store.failures.isNotEmpty() }
        assertEquals("bad", store.failures.single().chatJid)
        assertTrue(ai.inputs.any { it.chatJid == "good" })
    }

    @Test
    fun `voice materialization fallback sends verified text and exposes typing presence`() = runBlocking {
        val voice =
            PlannedSideEffect.SynthesizeVoiceNote(
                idempotencyKey = "voice-1",
                text = "gesprochener Inhalt",
                model = "google/tts",
                voice = "Kore",
                instructions = null,
                quality = 6,
            )
        val ai =
            RecordingAi(
                output = { TurnOutput(actions = listOf(voice)) },
                materializer = { _, _ ->
                    listOf(
                        PlannedSideEffect.VoiceTextFallback(
                            idempotencyKey = "voice-1:text-fallback",
                            text = voice.text,
                            reasonCode = "speech_http_404",
                        ),
                    )
                },
            )
        val presences = Collections.synchronizedList(mutableListOf<String>())
        val texts = Collections.synchronizedList(mutableListOf<String>())
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                override suspend fun setPresence(chatJid: String, presence: String) {
                    presences += presence
                }

                override suspend fun sendText(
                    chatJid: String,
                    text: String,
                    quoteMessageId: String?,
                    idempotencyKey: String,
                    quotePreview: String?,
                ): String {
                    texts += text
                    return idempotencyKey
                }
            }
        val store = FakeEngineStore()
        engine =
            newEngine(
                this,
                store,
                ai,
                whatsapp,
                settings =
                    EngineSettingsSnapshot(
                        enabled = true,
                        batchWindowMs = 0,
                        replyPreset = ReplyPreset.HUMAN,
                        markRead = false,
                        proactiveLevel = 1,
                    ),
                clock = ImmediateEngineClock,
            )

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { texts.isNotEmpty() }
        assertEquals(listOf("gesprochener Inhalt"), texts)
        assertTrue("composing" in presences)
        awaitCondition { "unavailable" in presences }
    }

    @Test
    fun `normal text reply emits composing between available and unavailable`() = runBlocking {
        val ai = RecordingAi(output = { TurnOutput(bubbles = listOf("antwort")) })
        val presences = Collections.synchronizedList(mutableListOf<String>())
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                override suspend fun setPresence(chatJid: String, presence: String) {
                    presences += presence
                }
            }
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai, whatsapp, clock = ImmediateEngineClock)

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { "unavailable" in presences }
        assertTrue("available" in presences)
        assertTrue("composing" in presences)
        assertTrue(presences.indexOf("available") < presences.indexOf("composing"))
        assertTrue(presences.indexOf("composing") < presences.indexOf("unavailable"))
    }

    @Test
    fun `text multi bubble keeps composing through the inter bubble pause`() = runBlocking {
        val ai = RecordingAi(output = { TurnOutput(bubbles = listOf("first", "second")) })
        val presences = Collections.synchronizedList(mutableListOf<String>())
        val sendPresenceOffsets = Collections.synchronizedList(mutableListOf<Int>())
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                override suspend fun setPresence(chatJid: String, presence: String) {
                    presences += presence
                }

                override suspend fun sendText(
                    chatJid: String,
                    text: String,
                    quoteMessageId: String?,
                    idempotencyKey: String,
                    quotePreview: String?,
                ): String {
                    sendPresenceOffsets += presences.size
                    return idempotencyKey
                }
            }
        engine = newEngine(this, FakeEngineStore(), ai, whatsapp, clock = ImmediateEngineClock)

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { sendPresenceOffsets.size == 2 }
        val betweenBubbles =
            presences.subList(sendPresenceOffsets[0], sendPresenceOffsets[1]).toList()
        assertTrue("composing" in betweenBubbles)
        assertFalse("paused" in betweenBubbles)
    }

    @Test
    fun `read receipt failure never prevents AI reply`() = runBlocking {
        val ai = RecordingAi(output = { TurnOutput(bubbles = listOf("antwort")) })
        val texts = Collections.synchronizedList(mutableListOf<String>())
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                override suspend fun markRead(chatJid: String, messageIds: List<String>) {
                    error("synthetic receipt rejection")
                }

                override suspend fun sendText(
                    chatJid: String,
                    text: String,
                    quoteMessageId: String?,
                    idempotencyKey: String,
                    quotePreview: String?,
                ): String {
                    texts += text
                    return idempotencyKey
                }
            }
        val store = FakeEngineStore()
        engine =
            newEngine(
                this,
                store,
                ai,
                whatsapp,
                settings =
                    EngineSettingsSnapshot(
                        enabled = true,
                        batchWindowMs = 0,
                        replyPreset = ReplyPreset.INSTANT,
                        markRead = true,
                        proactiveLevel = 1,
                    ),
                clock = ImmediateEngineClock,
            )

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { texts.isNotEmpty() }
        assertEquals(listOf("antwort"), texts)
        assertEquals(1, ai.inputs.size)
    }

    @Test
    fun `blocked preflight performs no AI or media work`() = runBlocking {
        val ai = RecordingAi()
        val store =
            FakeEngineStore(
                preflightDecision = OutboundDecision.Blocked("locked", hard = true),
            )
        engine = newEngine(this, store, ai)

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { store.preflightCalls > 0 }
        assertTrue(ai.inputs.isEmpty())
    }

    @Test
    fun `admin identity reaches outbound safety preflight`() = runBlocking {
        val ai = RecordingAi()
        val store =
            FakeEngineStore(
                accessEvaluator = { AccessDecision(allowed = true, isAdmin = true) },
            )
        engine = newEngine(this, store, ai)

        engine!!.accept(event("admin-chat", "m1", 1))

        awaitCondition { store.preflightIntents.isNotEmpty() }
        assertTrue(store.preflightIntents.single().admin)
    }

    @Test
    fun `partial multi bubble send stays sent after later transport failure`() = runBlocking {
        val ai =
            RecordingAi(
                output = {
                    TurnOutput(bubbles = listOf("first", "second"))
                },
            )
        val store = FakeEngineStore()
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                var calls = 0

                override suspend fun sendText(
                    chatJid: String,
                    text: String,
                    quoteMessageId: String?,
                    idempotencyKey: String,
                    quotePreview: String?,
                ): String {
                    calls++
                    if (calls == 2) error("second bubble failed")
                    return "transport-$calls"
                }
            }
        engine = newEngine(this, store, ai, whatsapp)

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { store.failures.isNotEmpty() }
        val parent =
            store.completions.filterNot {
                it.reservationId.contains(":text:") ||
                    it.reservationId.contains(":reaction")
            }
        assertTrue(parent.any { it.success })
        assertFalse(parent.any { !it.success })
    }

    @Test
    fun `six bubbles keep two independent reply targets`() = runBlocking {
        val bubbles = listOf("reply one", "reply two", "one", "two", "three", "four")
        val ai =
            RecordingAi(
                output = {
                    TurnOutput(
                        bubbles = bubbles,
                        bubbleQuoteMessageIds = listOf("target-1", "target-2", null, null, null, null),
                    )
                },
            )
        val sent = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                override suspend fun sendText(
                    chatJid: String,
                    text: String,
                    quoteMessageId: String?,
                    idempotencyKey: String,
                    quotePreview: String?,
                ): String {
                    sent += text to quoteMessageId
                    return "transport-${sent.size}"
                }
            }
        engine = newEngine(this, FakeEngineStore(), ai, whatsapp)

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { sent.size == 6 }
        assertEquals(
            listOf(
                "reply one" to "target-1",
                "reply two" to "target-2",
                "one" to null,
                "two" to null,
                "three" to null,
                "four" to null,
            ),
            sent.toList(),
        )
    }

    @Test
    fun `later reply does not accidentally quote the first plain bubble`() = runBlocking {
        val sent = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val ai =
            RecordingAi(
                output = {
                    TurnOutput(
                        bubbles = listOf("plain", "reply"),
                        quoteMessageId = "reply-target",
                        bubbleQuoteMessageIds = listOf(null, "reply-target"),
                    )
                },
            )
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                override suspend fun sendText(
                    chatJid: String,
                    text: String,
                    quoteMessageId: String?,
                    idempotencyKey: String,
                    quotePreview: String?,
                ): String {
                    sent += text to quoteMessageId
                    return "transport-${sent.size}"
                }
            }
        engine = newEngine(this, FakeEngineStore(), ai, whatsapp)

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { sent.size == 2 }
        assertEquals(listOf("plain" to null, "reply" to "reply-target"), sent.toList())
    }

    @Test
    fun `interrupting an unanswered turn folds its inputs into the combined batch`() = runBlocking {
        val ai = RecordingAi(blockChat = "chat")
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        // A voice note arrives and its turn reaches the model...
        engine!!.accept(event("chat", "voice", 1, text = "schick mir eine sprachnachricht"))
        ai.blockStarted.await()
        // ...then a photo lands before she has replied to anything.
        engine!!.accept(event("chat", "photo", 2, text = "[Image]"))
        ai.releaseBlock.complete(Unit)

        awaitCondition { ai.inputs.any { it.newestEvents.size > 1 } }
        val combined = ai.inputs.last { it.chatJid == "chat" }
        assertEquals(
            listOf("voice", "photo"),
            combined.newestEvents.map(IncomingEvent::messageId),
        )
    }

    @Test
    fun `disabled setting cancels active turn immediately`() = runBlocking {
        val ai = RecordingAi(blockChat = "chat")
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        engine!!.accept(event("chat", "m1", 1))
        ai.blockStarted.await()
        engine!!.applyEnabledSetting(false)

        awaitCondition { ai.blockCancelled.isCompleted }
        assertTrue(ai.blockCancelled.await())
    }

    @Test
    fun `own blocked and command events never contaminate conversation history`() = runBlocking {
        val ai = RecordingAi()
        val store =
            FakeEngineStore(
                accessEvaluator = { incoming ->
                    AccessDecision(
                        allowed = incoming.chatJid != "blocked",
                        isAdmin = false,
                    )
                },
            )
        val commandGateway =
            object : AdminCommandGateway {
                override suspend fun handle(event: IncomingEvent, isAdmin: Boolean) =
                    if (event.text == "/status") {
                        CommandHandling.Handled(emptyList())
                    } else {
                        CommandHandling.NotACommand
                    }
            }
        engine = newEngine(this, store, ai, commands = commandGateway)

        assertEquals(
            BotEngine.AcceptResult.IGNORED,
            engine!!.accept(event("own", "own-1", 1).copy(fromMe = true)),
        )
        assertEquals(
            BotEngine.AcceptResult.BLOCKED,
            engine!!.accept(event("blocked", "blocked-1", 2)),
        )
        assertEquals(
            BotEngine.AcceptResult.COMMAND,
            engine!!.accept(event("admin", "command-1", 3, text = "/status")),
        )

        assertTrue(store.persistedMessageIds.isEmpty())
        assertEquals(
            setOf("from_me", "access_blocked", "admin_command"),
            store.dispositions.map(RecordedDisposition::disposition).toSet(),
        )
        assertTrue(ai.inputs.isEmpty())
    }

    @Test
    fun `edit and delete events mutate targets without starting AI`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai)

        val edit =
            event("chat", "edit-event", 1, text = "korrigiert")
                .copy(
                    kind = ChatEventKind.EDIT,
                    targetMessageId = "original",
                )
        val delete =
            event("chat", "delete-event", 2)
                .copy(
                    kind = ChatEventKind.DELETE,
                    targetMessageId = "original",
                )

        assertEquals(BotEngine.AcceptResult.IGNORED, engine!!.accept(edit))
        assertEquals(BotEngine.AcceptResult.IGNORED, engine!!.accept(delete))
        assertEquals(listOf(ChatEventKind.EDIT, ChatEventKind.DELETE), store.mutations.map(IncomingEvent::kind))
        assertTrue(store.persistedMessageIds.isEmpty())
        assertTrue(ai.inputs.isEmpty())
    }

    @Test
    fun `failed optional self edit cannot duplicate or fail the sent reply`() = runBlocking {
        val ai =
            RecordingAi(
                output = { TurnOutput(bubbles = listOf("ausserordentliches wort")) },
            )
        val store = FakeEngineStore()
        val whatsapp =
            object : WhatsAppActions by NoOpWhatsAppActions {
                var sendCalls = 0
                var editCalls = 0

                override suspend fun sendText(
                    chatJid: String,
                    text: String,
                    quoteMessageId: String?,
                    idempotencyKey: String,
                    quotePreview: String?,
                ): String {
                    sendCalls++
                    return "sent-once"
                }

                override suspend fun editText(
                    chatJid: String,
                    messageId: String,
                    text: String,
                    idempotencyKey: String,
                ) {
                    editCalls++
                    error("synthetic edit rejection")
                }
            }
        engine =
            newEngine(
                scope = this,
                store = store,
                ai = ai,
                whatsapp = whatsapp,
                settings =
                    EngineSettingsSnapshot(
                        enabled = true,
                        batchWindowMs = 0,
                        replyPreset = ReplyPreset.INSTANT,
                        markRead = false,
                        proactiveLevel = 1,
                        selfEditEnabled = true,
                        selfEditChance = 1.0,
                    ),
                clock = ImmediateEngineClock,
            )

        engine!!.accept(event("chat", "m1", 1))

        awaitCondition { whatsapp.editCalls == 1 && store.failures.isNotEmpty() }
        assertEquals(1, whatsapp.sendCalls)
        val parent =
            store.completions.filterNot { it.reservationId.contains(":text:") }
        assertTrue(parent.any(RecordedCompletion::success))
        assertFalse(parent.any { !it.success })
    }

    @Test
    fun `messages arriving inside the pickup window are answered in one turn`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        val clock = GateClock()
        engine =
            newEngine(
                this,
                store,
                ai,
                settings = humanSettings,
                clock = clock,
            )

        // Both arrive while the bot is still "not looking at the phone".
        engine!!.accept(event("chat", "photo", 1, text = "guck mal"))
        engine!!.accept(event("chat", "clip", 2, text = "und das video"))
        awaitCondition { clock.waiters >= 1 }
        clock.open()

        awaitCondition { ai.inputs.isNotEmpty() }
        delay(100)
        val turns = ai.inputs.filter { it.chatJid == "chat" }
        assertEquals(1, turns.size)
        assertEquals(
            listOf("photo", "clip"),
            turns.single().newestEvents.map(IncomingEvent::messageId),
        )
    }

    @Test
    fun `chats sharing one pickup window are worked off in arrival order`() = runBlocking {
        val ai = RecordingAi()
        val store = FakeEngineStore()
        val clock = GateClock()
        engine =
            newEngine(
                this,
                store,
                ai,
                settings = humanSettings,
                clock = clock,
            )

        engine!!.accept(event("erst", "a", 1))
        engine!!.accept(event("dann", "b", 2))
        awaitCondition { clock.waiters >= 1 }
        clock.open()

        awaitCondition { ai.inputs.size == 2 }
        assertEquals(listOf("erst", "dann"), ai.inputs.map(TurnInput::chatJid))
    }

    @Test
    fun `confirmed media is persisted before identical-turn follow up`() = runBlocking {
        val store = FakeEngineStore()
        var followUpSawDurableMedia = false
        val followUpIndexes = mutableListOf<Int>()
        val ai =
            RecordingAi(
                output = {
                    TurnOutput(
                        actions =
                            listOf(
                                PlannedSideEffect.SendMedia(
                                    idempotencyKey = "media-1",
                                    uploadId = "upload-1",
                                    mimeType = "image/jpeg",
                                    voiceNote = false,
                                    historyText = "selfie.jpg",
                                ),
                            ),
                    )
                },
                followUp = { _, confirmed, index ->
                    followUpIndexes += index
                    followUpSawDurableMedia = store.assistantRecords.isNotEmpty()
                    assertEquals(1, index)
                    assertEquals(store.assistantRecords.single().text, confirmed.single().text)
                    TurnOutput(bubbles = listOf("danach noch text"))
                },
            )
        engine = newEngine(this, store, ai, clock = ImmediateEngineClock)

        engine!!.accept(event("chat", "incoming", 1, text = "zeig mal"))

        awaitCondition { store.assistantRecords.size == 2 }
        assertTrue(followUpSawDurableMedia)
        assertEquals(listOf(1), followUpIndexes)
        assertEquals("You sent an image: selfie.jpg", store.assistantRecords[0].text)
        assertEquals(listOf("media-1"), store.assistantRecords[0].transportMessageIds)
        assertEquals("You sent: danach noch text", store.assistantRecords[1].text)
    }

    @Test
    fun `block chosen by a follow up suppresses its sibling text`() = runBlocking {
        val store = FakeEngineStore()
        val ai =
            RecordingAi(
                output = {
                    TurnOutput(
                        actions =
                            listOf(
                                PlannedSideEffect.SendMedia(
                                    idempotencyKey = "media-before-block",
                                    uploadId = "upload-before-block",
                                    mimeType = "image/jpeg",
                                    voiceNote = false,
                                    historyText = "already sent",
                                ),
                            ),
                    )
                },
                followUp = { _, _, _ ->
                    TurnOutput(
                        bubbles = listOf("must never be sent after the block"),
                        actions =
                            listOf(
                                PlannedSideEffect.BlockContact(
                                    idempotencyKey = "follow-up-block",
                                    jid = "chat@s.whatsapp.net",
                                    reason = "test block",
                                ),
                            ),
                    )
                },
            )
        engine = newEngine(this, store, ai, clock = ImmediateEngineClock)

        engine!!.accept(event("chat", "incoming", 1, text = "send then block"))

        awaitCondition { store.completions.isNotEmpty() }
        assertEquals(listOf("You sent an image: already sent"), store.assistantRecords.map(RecordedAssistant::text))
        assertEquals(listOf("chat@s.whatsapp.net"), store.blockedSenders)
    }

    @Test
    fun `all media from one model batch are confirmed before one follow up`() = runBlocking {
        val store = FakeEngineStore()
        val followUpConfirmedCounts = mutableListOf<Int>()
        val ai =
            RecordingAi(
                output = {
                    TurnOutput(
                        actions =
                            listOf(
                                PlannedSideEffect.SendMedia(
                                    idempotencyKey = "voice-1",
                                    uploadId = "upload-1",
                                    mimeType = "audio/ogg; codecs=opus",
                                    voiceNote = true,
                                    historyText = "erste voice",
                                ),
                                PlannedSideEffect.SendMedia(
                                    idempotencyKey = "voice-2",
                                    uploadId = "upload-2",
                                    mimeType = "audio/ogg; codecs=opus",
                                    voiceNote = true,
                                    historyText = "zweite voice",
                                ),
                            ),
                    )
                },
                followUp = { _, confirmed, _ ->
                    followUpConfirmedCounts += confirmed.size
                    TurnOutput(noReply = true)
                },
            )
        engine = newEngine(this, store, ai, clock = ImmediateEngineClock)

        engine!!.accept(event("chat", "incoming", 1, text = "zwei voices"))

        awaitCondition { followUpConfirmedCounts.isNotEmpty() }
        assertEquals(listOf(2), followUpConfirmedCounts)
        assertEquals(
            listOf(
                "You sent a voice note: erste voice",
                "You sent a voice note: zweite voice",
            ),
            store.assistantRecords.map(RecordedAssistant::text),
        )
    }

    @Test
    fun `normal text uses one multi-bubble batch without follow up`() = runBlocking {
        val store = FakeEngineStore()
        val followUpIndexes = mutableListOf<Int>()
        val ai =
            RecordingAi(
                output = { TurnOutput(bubbles = listOf("erste Antwort", "zweite Bubble")) },
                followUp = { _, _, index ->
                    followUpIndexes += index
                    TurnOutput(bubbles = listOf("unerwarteter Zusatz"))
                },
            )
        engine = newEngine(this, store, ai, clock = ImmediateEngineClock)

        engine!!.accept(event("chat", "incoming", 1, text = "los"))

        // Completion is signalled before the last bubble's record lands, so waiting on it alone
        // raced the second bubble. Wait for both: the turn is finished and fully recorded.
        awaitCondition { store.completions.isNotEmpty() && store.assistantRecords.size == 2 }
        assertTrue(followUpIndexes.isEmpty())
        assertEquals(
            listOf("You sent: erste Antwort", "You sent: zweite Bubble"),
            store.assistantRecords.map(RecordedAssistant::text),
        )
    }

    @Test
    fun `an outreach is recorded as a message she started herself`() = runBlocking {
        val store = FakeEngineStore()
        val ai = RecordingAi(output = { TurnOutput(bubbles = listOf("hey, alles gut bei dir?")) })
        engine = newEngine(this, store, ai, clock = ImmediateEngineClock)

        val seed = event("contact", "letzte-eingehende", 1, text = "bis dann")
        withTimeout(3_000L) {
            engine!!.enqueueProactive(
                seed = ProactiveSeed(seed),
                level = 10,
                request = ProactiveTurnRequest(reason = "Reaching out."),
            )
        }

        awaitCondition { store.assistantRecords.isNotEmpty() }
        // Plain "You sent: …" is indistinguishable from an answer, and the next turn then met the
        // reply to this very message with "why are you writing me". The marker is what the model
        // reads on the turn after this one.
        assertEquals(
            listOf("You sent (you wrote first, they had not messaged): hey, alles gut bei dir?"),
            store.assistantRecords.map(RecordedAssistant::text),
        )
    }

    @Test
    fun `repeated startup recovery cannot answer the same text twice`() = runBlocking {
        val pending = event("chat", "same-message", 1, text = "nur einmal")
        val ai =
            RecordingAi(
                blockChat = "chat",
                output = { TurnOutput(bubbles = listOf("genau eine Antwort")) },
            )
        val store = FakeEngineStore().apply { recoveredBatches = listOf(listOf(pending)) }
        engine = newEngine(this, store, ai, clock = ImmediateEngineClock)

        engine!!.startRecovery()
        ai.blockStarted.await()
        // Exact phone race: reconnect recovery schedules the durable input again while its first
        // normal turn is already active but has not written the outbound message yet.
        engine!!.startRecovery()
        ai.releaseBlock.complete(Unit)

        awaitCondition { store.dispositions.any { it.eventId == pending.eventId } }
        delay(100)
        assertEquals(1, ai.inputs.size)
        assertEquals(
            "answered",
            store.dispositions.single { it.eventId == pending.eventId }.disposition,
        )
    }

    @Test
    fun `reaction and text bubbles never start a follow up`() = runBlocking {
        val followUpConfirmedCounts = mutableListOf<Int>()
        val ai =
            RecordingAi(
                output = {
                    TurnOutput(
                        bubbles = listOf("erste Bubble", "zweite Bubble"),
                        reaction = "🔥",
                    )
                },
                followUp = { _, confirmed, _ ->
                    followUpConfirmedCounts += confirmed.size
                    TurnOutput(noReply = true)
                },
            )
        val store = FakeEngineStore()
        engine = newEngine(this, store, ai, clock = ImmediateEngineClock)

        engine!!.accept(event("chat", "incoming", 1, text = "los"))

        awaitCondition { store.completions.isNotEmpty() }
        // Completion and the last bubble's own record are written by different steps, so the
        // completion alone does not mean the transcript is finished. Waiting on the thing actually
        // being asserted removes a race that failed this test on the second bubble roughly one run
        // in twenty; it weakens nothing, because a bubble that never lands still times out here.
        awaitCondition { store.assistantRecords.size == 3 }
        assertTrue(followUpConfirmedCounts.isEmpty())
        assertEquals(
            listOf(
                "You reacted with: 🔥",
                "You sent: erste Bubble",
                "You sent: zweite Bubble",
            ),
            store.assistantRecords.map(RecordedAssistant::text),
        )
        assertEquals(3, store.assistantRecords.flatMap(RecordedAssistant::transportMessageIds).distinct().size)
    }

    /**
     * The human-delay window used to be re-planned only when a message arrived,
     * so switching to instant while someone was already waiting changed nothing
     * until you sent another message — the one thing you cannot do on behalf of
     * the contact. The gate is never opened here: if the answer needs the human
     * wait to elapse, this test times out.
     */
    @Test
    fun `switching to instant answers who is already waiting, without a new message`() =
        runBlocking {
            val ai = RecordingAi()
            val store = FakeEngineStore()
            val clock = GateClock()
            var current = humanSettings
            engine =
                newEngine(
                    this,
                    store,
                    ai,
                    settings = humanSettings,
                    clock = clock,
                    liveSettings = { current },
                )

            engine!!.accept(event("chat", "m1", 1))
            awaitCondition { clock.waiters >= 1 }
            assertTrue(ai.inputs.isEmpty())

            current = humanSettings.copy(replyPreset = ReplyPreset.INSTANT)
            engine!!.onSettingsChanged()

            awaitCondition { ai.inputs.isNotEmpty() }
            assertEquals(
                listOf("m1"),
                ai.inputs.single().newestEvents.map(IncomingEvent::messageId),
            )
        }

    @Test
    fun `a per-chat instant override releases an already armed human reply`() =
        runBlocking {
            val ai = RecordingAi()
            val store = FakeEngineStore()
            val clock = GateClock()
            var current = humanSettings
            engine =
                newEngine(
                    this,
                    store,
                    ai,
                    settings = humanSettings,
                    clock = clock,
                    liveSettings = { current },
                )

            engine!!.accept(event("chat", "m1", 1))
            awaitCondition { clock.waiters >= 1 }
            assertTrue(ai.inputs.isEmpty())

            current = humanSettings.copy(replyPreset = ReplyPreset.INSTANT)
            engine!!.onChatSettingsChanged("chat")

            awaitCondition { ai.inputs.isNotEmpty() }
            assertEquals("m1", ai.inputs.single().newestEvents.single().messageId)
        }

    @Test
    fun `an idle bot is not pulled online just because the preset changed`() =
        runBlocking {
            val ai = RecordingAi()
            val store = FakeEngineStore()
            var current = humanSettings
            engine =
                newEngine(
                    this,
                    store,
                    ai,
                    settings = humanSettings,
                    clock = ImmediateEngineClock,
                    liveSettings = { current },
                )

            current = humanSettings.copy(replyPreset = ReplyPreset.INSTANT)
            engine!!.onSettingsChanged()

            delay(100)
            assertTrue(ai.inputs.isEmpty())
        }

    @Test
    fun `changing the persona puts the new face up straight away`() =
        runBlocking {
            val ai = RecordingAi()
            val store = FakeEngineStore()
            val switched = Collections.synchronizedList(mutableListOf<String>())
            val rotator =
                object : ProfilePictureRotation {
                    override suspend fun rotateIfDue(personaKey: String, nowMs: Long) =
                        ProfilePictureOutcome.UNCHANGED

                    override suspend fun applyPersonaSwitch(
                        personaKey: String,
                        nowMs: Long,
                    ): ProfilePictureOutcome {
                        switched += personaKey
                        return ProfilePictureOutcome.CHANGED
                    }
                }
            engine =
                newEngine(
                    this,
                    store,
                    ai,
                    settings = humanSettings.copy(personality = "goth"),
                    clock = ImmediateEngineClock,
                    profilePictures = rotator,
                )

            engine!!.onSettingsChanged()

            assertEquals(listOf("goth"), switched)
        }

    @Test
    fun `the persona on the account is reconciled as soon as the link is back`() =
        runBlocking {
            val ai = RecordingAi()
            val store = FakeEngineStore()
            val switched = Collections.synchronizedList(mutableListOf<String>())
            val rotator =
                object : ProfilePictureRotation {
                    override suspend fun rotateIfDue(personaKey: String, nowMs: Long) =
                        ProfilePictureOutcome.UNCHANGED

                    override suspend fun applyPersonaSwitch(
                        personaKey: String,
                        nowMs: Long,
                    ): ProfilePictureOutcome {
                        switched += personaKey
                        return ProfilePictureOutcome.CHANGED
                    }
                }
            engine =
                newEngine(
                    this,
                    store,
                    ai,
                    settings = humanSettings.copy(personality = "goth"),
                    clock = ImmediateEngineClock,
                    profilePictures = rotator,
                )

            // Nobody changed a setting in this run: the persona may have been
            // switched while the app was down, and a picture that could not be
            // applied earlier is still owed.
            engine!!.startRecovery()

            assertEquals(listOf("goth"), switched)
        }

    @Test
    fun `persona switched during sleep waits for the first carrying recovery`() = runBlocking {
        val switched = Collections.synchronizedList(mutableListOf<String>())
        val linkPower = LinkPowerFeed().apply { publishCarrying(false) }
        val rotator =
            object : ProfilePictureRotation {
                override suspend fun rotateIfDue(personaKey: String, nowMs: Long) =
                    ProfilePictureOutcome.UNCHANGED

                override suspend fun applyPersonaSwitch(
                    personaKey: String,
                    nowMs: Long,
                ): ProfilePictureOutcome {
                    switched += personaKey
                    return ProfilePictureOutcome.CHANGED
                }
            }
        engine =
            newEngine(
                this,
                FakeEngineStore(),
                RecordingAi(),
                settings = humanSettings.copy(personality = "nico"),
                clock = ImmediateEngineClock,
                linkPower = linkPower,
                profilePictures = rotator,
            )

        engine!!.onSettingsChanged()
        assertTrue(switched.isEmpty())

        linkPower.publishCarrying(true)
        engine!!.startRecovery()
        assertEquals(listOf("nico"), switched)
    }

    private val humanSettings =
        EngineSettingsSnapshot(
            enabled = true,
            batchWindowMs = 0,
            replyPreset = ReplyPreset.HUMAN,
            markRead = false,
            proactiveLevel = 1,
        )

    /**
     * Holds every wait until the test opens the gate, so the pickup window is
     * observable instead of racing real time.
     */
    private class GateClock : EngineClock {
        private var now = 1_000_000L
        private val gate = CompletableDeferred<Unit>()

        @Volatile
        var waiters = 0
            private set

        fun open() {
            gate.complete(Unit)
        }

        override fun wallTimeMillis(): Long = now++

        override suspend fun delay(millis: Long) {
            if (millis <= 0L) return
            if (!gate.isCompleted) {
                waiters++
                gate.await()
            }
            now += millis
        }
    }

    private fun newEngine(
        scope: kotlinx.coroutines.CoroutineScope,
        store: FakeEngineStore,
        ai: RecordingAi,
        whatsapp: WhatsAppActions = NoOpWhatsAppActions,
        commands: AdminCommandGateway =
            object : AdminCommandGateway {
                override suspend fun handle(event: IncomingEvent, isAdmin: Boolean) =
                    CommandHandling.NotACommand
            },
        settings: EngineSettingsSnapshot =
            EngineSettingsSnapshot(
                enabled = true,
                batchWindowMs = 0,
                replyPreset = ReplyPreset.INSTANT,
                markRead = false,
                proactiveLevel = 1,
            ),
        clock: EngineClock = SystemEngineClock,
        /** Overrides [settings] per call, for tests that change a setting mid-flight. */
        liveSettings: (() -> EngineSettingsSnapshot)? = null,
        linkPower: LinkPowerFeed = LinkPowerFeed(),
        profilePictures: ProfilePictureRotation? = null,
    ): BotEngine =
        BotEngine(
            // Keep the long-lived engine worker outside runBlocking's child set.
            // JUnit closes it deterministically in @After; attaching it directly to
            // runBlocking would make the test wait for the worker before @After runs.
            parentScope =
                kotlinx.coroutines.CoroutineScope(
                    scope.coroutineContext + kotlinx.coroutines.SupervisorJob(),
                ),
            store = store,
            settingsProvider =
                object : EngineSettingsProvider {
                    override suspend fun resolve(chatJid: String) =
                        liveSettings?.invoke() ?: settings
                },
            commands = commands,
            ai = ai,
            whatsapp = whatsapp,
            linkPower = linkPower,
            clock = clock,
            profilePictures = profilePictures,
            // These tests fast-forward time, so the every-few-hours presence loop would
            // never stop coming due. Presence has its own tests; this file is about turns.
            selfOnlineSessions = false,
        )

    private fun event(
        chat: String,
        messageId: String,
        sequence: Long,
        text: String = messageId,
    ) = IncomingEvent(
        eventId = "event-$messageId",
        sequence = sequence,
        kind = ChatEventKind.MESSAGE,
        messageId = messageId,
        chatJid = chat,
        isGroup = false,
        senderJid = "$chat@s.whatsapp.net",
        fromMe = false,
        timestampMs = sequence * 1_000L,
        text = text,
    )

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(3_000L) {
            while (!condition()) delay(5)
        }
    }
}

private class RecordingAi(
    private val blockChat: String? = null,
    private val failChat: String? = null,
    private val output: (TurnInput) -> TurnOutput = { TurnOutput(noReply = true) },
    private val materializer: suspend (TurnInput, List<PlannedSideEffect>) -> List<PlannedSideEffect> =
        { _, actions -> actions },
    private val followUp:
        suspend (TurnInput, List<StoredTurnMessage>, Int) -> TurnOutput =
        { _, _, _ -> TurnOutput(noReply = true) },
) : AiTurnRunner {
    val inputs: MutableList<TurnInput> = Collections.synchronizedList(mutableListOf())
    val blockStarted = CompletableDeferred<Unit>()
    val releaseBlock = CompletableDeferred<Unit>()
    val blockCancelled = CompletableDeferred<Boolean>()

    override suspend fun run(input: TurnInput): TurnOutput {
        inputs += input
        if (input.chatJid == failChat) error("synthetic turn failure")
        if (input.chatJid == blockChat) {
            blockStarted.complete(Unit)
            try {
                releaseBlock.await()
            } finally {
                blockCancelled.complete(!releaseBlock.isCompleted)
            }
        }
        return output(input)
    }

    override suspend fun materializeActions(
        input: TurnInput,
        actions: List<PlannedSideEffect>,
    ): List<PlannedSideEffect> = materializer(input, actions)

    override suspend fun followUpAfterVisibleAssistant(
        input: TurnInput,
        confirmedAssistantMessages: List<StoredTurnMessage>,
        followUpIndex: Int,
    ): TurnOutput = followUp(input, confirmedAssistantMessages, followUpIndex)
}

private data class RecordedFailure(
    val chatJid: String,
    val turnId: String,
    val proactive: Boolean,
)

private data class RecordedCompletion(
    val reservationId: String,
    val success: Boolean,
)

private data class RecordedDisposition(
    val eventId: String,
    val disposition: String,
)

private data class RecordedAssistant(
    val conversationKey: String,
    val text: String,
    val transportMessageIds: List<String>,
)

private class FakeEngineStore(
    private val preflightDecision: OutboundDecision? = null,
    private val accessEvaluator: (IncomingEvent) -> AccessDecision = {
        AccessDecision(allowed = true, isAdmin = false)
    },
) : EngineStore {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val history = ConcurrentHashMap<String, MutableList<StoredTurnMessage>>()
    val failures: MutableList<RecordedFailure> = Collections.synchronizedList(mutableListOf())
    val completions: MutableList<RecordedCompletion> = Collections.synchronizedList(mutableListOf())
    val dispositions: MutableList<RecordedDisposition> = Collections.synchronizedList(mutableListOf())
    val persistedMessageIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val assistantRecords: MutableList<RecordedAssistant> =
        Collections.synchronizedList(mutableListOf())
    val blockedSenders: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val mutations: MutableList<IncomingEvent> = Collections.synchronizedList(mutableListOf())
    val preflightIntents: MutableList<OutboundIntent> =
        Collections.synchronizedList(mutableListOf())
    val historyLoadLimits: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    @Volatile
    var preflightCalls: Int = 0
    var recoveredBatches: List<List<IncomingEvent>> = emptyList()

    override suspend fun claimInbound(event: IncomingEvent): Boolean =
        seen.add(event.eventId)

    override suspend fun persistConversation(event: IncomingEvent): Boolean {
        persistedMessageIds += event.messageId
        val conversationKey = event.personaKey?.let { "${event.chatJid}#$it" } ?: event.chatJid
        history.computeIfAbsent(conversationKey) {
            Collections.synchronizedList(mutableListOf())
        } +=
            StoredTurnMessage(
                id = event.messageId,
                role = "user",
                text = event.text,
                timestampMs = event.timestampMs,
            )
        return true
    }

    override suspend fun applyInboundMutation(event: IncomingEvent): Boolean {
        mutations += event
        return true
    }

    override suspend fun completeInbound(
        event: IncomingEvent,
        disposition: String,
    ) {
        dispositions += RecordedDisposition(event.eventId, disposition)
    }

    override suspend fun accessDecision(event: IncomingEvent, allowAll: Boolean) =
        accessEvaluator(event)

    override suspend fun inboundRateDecision(
        event: IncomingEvent,
        perMinute: Int,
        perFiveMinutes: Int,
        perTenMinutes: Int,
    ) = InboundRateDecision(
        limited = false,
        autoblockThresholdExceeded = false,
    )

    override suspend fun blockSender(event: IncomingEvent, reason: String) {
        blockedSenders += event.senderJid
    }

    override suspend fun loadHistory(conversationKey: String, limit: Int): List<StoredTurnMessage> {
        historyLoadLimits += limit
        return history[conversationKey].orEmpty().toList()
    }

    override suspend fun loadChatMemory(conversationKey: String): String? = null

    override suspend fun loadPersonaMemory(personaKey: String): String? = null

    override suspend fun recordAssistant(
        conversationKey: String,
        chatJid: String,
        text: String,
        transportMessageIds: List<String>,
        timestampMs: Long,
    ) {
        assistantRecords += RecordedAssistant(conversationKey, text, transportMessageIds)
        history.computeIfAbsent(conversationKey) {
            Collections.synchronizedList(mutableListOf())
        } +=
            StoredTurnMessage(
                id = transportMessageIds.firstOrNull() ?: "assistant-${assistantRecords.size}",
                role = "assistant",
                text = text,
                timestampMs = timestampMs,
            )
    }

    override suspend fun markDeferred(conversationKey: String, timestampMs: Long) = Unit

    override suspend fun clearDeferred(conversationKey: String) = Unit

    override suspend fun requestMemoryRefresh(conversationKey: String, timestampMs: Long) = Unit

    override suspend fun preflightOutbound(intent: OutboundIntent): OutboundDecision =
        (preflightDecision ?: OutboundDecision.Allowed(intent.reservationId)).also {
            preflightIntents += intent
            preflightCalls++
        }

    override suspend fun reserveOutbound(intent: OutboundIntent): OutboundDecision =
        OutboundDecision.Allowed(intent.reservationId)

    override suspend fun completeOutbound(
        reservationId: String,
        transportMessageId: String?,
        success: Boolean,
        timestampMs: Long,
    ) {
        completions += RecordedCompletion(reservationId, success)
    }

    override suspend fun lastOnlineAt(): Long? = null

    override suspend fun setLastOnlineAt(timestampMs: Long) = Unit

    override suspend fun recordTurnFailure(
        chatJid: String,
        turnId: String,
        proactive: Boolean,
        error: Throwable,
        timestampMs: Long,
    ) {
        failures += RecordedFailure(chatJid, turnId, proactive)
    }

    override suspend fun recoverPending(): List<List<IncomingEvent>> = recoveredBatches
}

private object ImmediateEngineClock : EngineClock {
    private var now = 1_000_000L

    override fun wallTimeMillis(): Long = now++

    override suspend fun delay(millis: Long) {
        now += millis.coerceAtLeast(0L)
        // The wait costs no wall-clock time, but it still has to be a suspension point:
        // a "delay" that never gives the dispatcher back keeps every other coroutine in
        // the test off the single runBlocking thread.
        kotlinx.coroutines.yield()
    }
}

private object NoOpWhatsAppActions : WhatsAppActions {
    override suspend fun markRead(chatJid: String, messageIds: List<String>) = Unit

    override suspend fun markPlayed(chatJid: String, messageId: String) = Unit

    override suspend fun setPresence(chatJid: String, presence: String) = Unit

    override suspend fun sendText(
        chatJid: String,
        text: String,
        quoteMessageId: String?,
        idempotencyKey: String,
        quotePreview: String?,
    ) = idempotencyKey

    override suspend fun sendReaction(
        chatJid: String,
        targetMessageId: String,
        emoji: String,
        idempotencyKey: String,
    ): String? = idempotencyKey

    override suspend fun sendMedia(
        chatJid: String,
        action: PlannedSideEffect.SendMedia,
    ) = action.idempotencyKey

    override suspend fun editText(
        chatJid: String,
        messageId: String,
        text: String,
        idempotencyKey: String,
    ) = Unit

    override suspend fun blockContact(
        jid: String,
        reason: String,
        idempotencyKey: String,
        aliases: List<String>,
    ) = Unit
}
