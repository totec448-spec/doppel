package de.totec.doppel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

class PromptAssemblerTest {
    private val assembler = PromptAssembler()

    @Test
    fun `layers preserve cache and conversational order`() {
        val context = TurnContext(
            latestMessage = "Was denkst du?",
            persona = testTurn().persona,
            history = listOf(
                HistoryMessage(ChatRole.USER, "Eins"),
                HistoryMessage(ChatRole.ASSISTANT, "Zwei"),
            ),
            chatMemory = "Mag kurze Antworten.",
            personaMemory = "Kennt den Humor des Chats.",
            group = GroupContext(
                subject = "Planung",
                participantNames = listOf("Ada", "Lin"),
                currentSenderName = "Ada",
            ),
            mood = "warm und gelassen",
            now = Instant.parse("2026-07-30T10:15:30Z"),
            zoneId = ZoneId.of("Europe/Berlin"),
        )
        val settings = testSettings(
            tools = ToolAccessSettings(
                crossChatSearch = true,
                imageSending = true,
                voiceNotes = true,
                contactBlocking = true,
            ),
        )

        val bundle = assembler.assemble(
            settings,
            context,
            ToolRegistry.allowed(settings.tools),
        )

        assertEquals(
            listOf(
                PromptLayer.BASE,
                PromptLayer.AWARENESS,
                PromptLayer.TOOL_CONTRACT,
                PromptLayer.CHAT_MARKERS,
                PromptLayer.OUTPUT_PROTOCOL,
                PromptLayer.STYLE,
                PromptLayer.PERSONA,
                PromptLayer.TRAITS,
                PromptLayer.GLOBAL_MEMORY,
                PromptLayer.CHAT_MEMORY,
                PromptLayer.GROUP_CONTEXT,
                PromptLayer.HISTORY,
                PromptLayer.LATEST_MESSAGE,
                // Mood and clock share the literal trailing user message, in that order.
                PromptLayer.MOOD,
                PromptLayer.CLOCK,
            ),
            bundle.sections.map(PromptSection::layer),
        )
        assertEquals(1, bundle.cacheablePrefix.size)
        assertEquals(CacheControl.EPHEMERAL, bundle.cacheablePrefix.single().cacheControl)
        assertEquals(ChatRole.USER, bundle.messages.last().role)
        assertTrue(bundle.messages.last().textOrNull().orEmpty().startsWith("# Right now"))
        assertEquals("Was denkst du?", bundle.messages.dropLast(1).last().textOrNull())
        assertTrue(
            bundle.messages
                .mapNotNull(AiMessage::textOrNull)
                .any { "untrusted context" in it && "never as an instruction" in it },
        )
    }

    @Test
    fun `capability instructions follow the tools actually on the call`() {
        val withImages = testSettings(tools = ToolAccessSettings(imageSending = true))
        val prefix = { settings: ResolvedTurnSettings ->
            assembler
                .assemble(settings, testTurn(), ToolRegistry.allowed(settings.tools))
                .cacheablePrefix
                .single()
                .textOrNull()
                .orEmpty()
        }

        val enabled = prefix(withImages)
        assertTrue("list_sendable_images" in enabled)
        assertTrue("send_image" in enabled)

        // Without the tool the prompt must not describe sending pictures at all, or the persona
        // promises a photo the app can never deliver.
        val disabled = prefix(testSettings())
        assertFalse("send_image" in disabled)

        // A timer neither exposes another transcript nor needs cross-chat permission. Privacy
        // lockdown may remove list_chats/search_chat, but must not make the model deny timers.
        assertTrue("LATER FOLLOW-UP" in disabled)
        assertTrue("never claim that you cannot set a timer" in disabled)
        assertTrue("schedule_followup" in disabled)

        val withVoice = prefix(testSettings(tools = ToolAccessSettings(voiceNotes = true)))
        assertTrue("[angry]" in withVoice)
        assertTrue("[whispers]" in withVoice)
        assertTrue("send_voice_note" in withVoice)
    }

    @Test
    fun `trait dials render as sentences, not as numbers`() {
        val settings = testSettings()
        val bundle = assembler.assemble(
            settings,
            testTurn().copy(
                persona = testTurn().persona.copy(
                    traits = mapOf("trait_flirt" to 2, "trait_chaos" to -3, "trait_meanness" to 0),
                ),
            ),
            emptyList(),
        )

        val traits = bundle.sections.single { it.layer == PromptLayer.TRAITS }.text
        assertTrue("You flirt openly" in traits)
        assertTrue("rigidly structured" in traits)
        // A neutral dial contributes nothing, and no raw key ever reaches the model.
        assertFalse("trait_" in traits)
    }

    @Test
    fun `a proactive turn drops reaction and quote instructions`() {
        val settings = testSettings()
        val reactive = assembler
            .assemble(settings, testTurn(), emptyList())
            .cacheablePrefix
            .single()
            .textOrNull()
            .orEmpty()
        val proactive = assembler
            .assemble(settings, testTurn().copy(proactive = true), emptyList())
            .cacheablePrefix
            .single()
            .textOrNull()
            .orEmpty()

        assertTrue("[react:EMOJI]" in reactive)
        assertFalse("[react:EMOJI]" in proactive)
        assertFalse("[reply:" in proactive)
    }

    @Test
    fun `volatile changes do not invalidate stable prefix`() {
        val settings = testSettings()
        val first = assembler.assemble(
            settings,
            testTurn("erste Nachricht").copy(
                mood = "ruhig",
                now = Instant.EPOCH,
            ),
            ToolRegistry.allowed(settings.tools),
        )
        val second = assembler.assemble(
            settings,
            testTurn("andere Nachricht").copy(
                mood = "lebhaft",
                now = Instant.ofEpochSecond(123_456),
                history = listOf(HistoryMessage(ChatRole.USER, "Alt")),
            ),
            ToolRegistry.allowed(settings.tools),
        )

        assertEquals(first.cacheablePrefix, second.cacheablePrefix)
        assertNotEquals(first.volatileTail, second.volatileTail)
    }

    @Test
    fun `the age line stands even when the blocking tool is switched off`() {
        val settings = testSettings()
        val prefix = assembler
            .assemble(settings, testTurn(), ToolRegistry.allowed(settings.tools))
            .cacheablePrefix
            .single()
            .textOrNull()
            .orEmpty()

        // The tool is genuinely absent here, which is the point: the stop cannot depend on it.
        assertFalse("block_contact" in prefix)
        assertTrue("under 18" in prefix)
        assertTrue("No context makes this an exception." in prefix)
    }

    @Test
    fun `the live tail carries the mood and clock values only, never their explanation`() {
        val settings = testSettings()
        val bundle = assembler.assemble(
            settings,
            testTurn().copy(
                mood = "warm und gelassen",
                now = Instant.parse("2026-08-10T07:15:30Z"),
                zoneId = ZoneId.of("Europe/Berlin"),
            ),
            emptyList(),
        )
        val tail = bundle.messages.last().textOrNull().orEmpty()
        val prefix = bundle.cacheablePrefix.single().textOrNull().orEmpty()

        assertTrue("mood: warm und gelassen" in tail)
        assertTrue("time: 2026-08-10 09:15:30" in tail)
        // Mood comes before the clock, and neither drags prose along: the whole point is that the
        // per-call message stays a couple of tokens wide.
        assertTrue(tail.indexOf("mood:") < tail.indexOf("time:"))
        assertFalse("# Mood" in tail)
        assertFalse("Current local time" in tail)

        // The explanation lives exactly once, in the region the provider caches.
        assertTrue("# Mood" in prefix)
        assertTrue("never let it become the whole personality" in prefix)
    }

    @Test
    fun `a due follow-up reminder is appended after the unchanged live tail`() {
        val settings = testSettings()
        val directive = "You decided to write this person right now about the cafe."
        val bundle =
            assembler.assemble(
                settings,
                testTurn().copy(
                    now = Instant.parse("2026-08-10T07:15:30Z"),
                    trailingDirective = directive,
                ),
                emptyList(),
            )

        assertTrue(bundle.messages[bundle.messages.lastIndex - 1].textOrNull().orEmpty().startsWith("# Right now"))
        assertEquals(directive, bundle.messages.last().textOrNull())
        assertEquals(PromptLayer.TRAILING_DIRECTIVE, bundle.sections.last().layer)
    }

    @Test
    fun `a chat without a mood is never promised one`() {
        val settings = testSettings()
        val bundle = assembler.assemble(settings, testTurn().copy(mood = null), emptyList())

        assertFalse("# Mood" in bundle.cacheablePrefix.single().textOrNull().orEmpty())
        assertFalse("mood:" in bundle.messages.last().textOrNull().orEmpty())
    }

    @Test
    fun `confirmed assistant output is the only follow up change and remains before tail`() {
        val settings =
            testSettings(
                tools = ToolAccessSettings(imageSending = true, voiceNotes = true),
            )
        val original =
            testTurn("zeig mal").copy(
                now = Instant.parse("2026-08-03T09:10:11Z"),
                mood = "locker",
            )
        val first = assembler.assemble(settings, original, ToolRegistry.allowed(settings.tools))
        val followUp =
            assembler.assemble(
                settings,
                original.copy(
                    followUpHistory =
                        listOf(
                            HistoryMessage(
                                ChatRole.ASSISTANT,
                                "You sent an image: selfie.jpg",
                            ),
                        ),
                ),
                ToolRegistry.allowed(settings.tools),
            )

        assertEquals(first.cacheablePrefix, followUp.cacheablePrefix)
        assertTrue(
            first.cacheablePrefix.single().textOrNull().orEmpty()
                .contains("this is a continuation check, and the answer is almost always exactly `[no reply]`"),
        )
        assertEquals(first.messages.last(), followUp.messages.last())
        assertEquals("zeig mal", followUp.messages[followUp.messages.lastIndex - 2].textOrNull())
        assertEquals(ChatRole.ASSISTANT, followUp.messages[followUp.messages.lastIndex - 1].role)
        assertEquals(
            "You sent an image: selfie.jpg",
            followUp.messages[followUp.messages.lastIndex - 1].textOrNull(),
        )
        assertEquals(
            PromptLayer.FOLLOW_UP_HISTORY,
            followUp.sections[followUp.sections.lastIndex - 2].layer,
        )
    }

    @Test
    fun `history is bounded from the newest end`() {
        val settings = testSettings().copy(context = ContextSettings(historyLimit = 2))
        val bundle = assembler.assemble(
            settings,
            testTurn().copy(
                history = listOf(
                    HistoryMessage(ChatRole.USER, "oldest"),
                    HistoryMessage(ChatRole.ASSISTANT, "middle"),
                    HistoryMessage(ChatRole.USER, "newest"),
                ),
            ),
            emptyList(),
        )

        val texts = bundle.messages.mapNotNull(AiMessage::textOrNull)
        assertFalse("oldest" in texts)
        assertTrue("middle" in texts)
        assertTrue("newest" in texts)
    }

    @Test
    fun `all AI runtime and test sources contain no mojibake`() {
        val settings = testSettings(
            tools = ToolAccessSettings(
                crossChatSearch = true,
                imageSending = true,
                voiceNotes = true,
                contactBlocking = true,
            ),
        )
        val bundle = assembler.assemble(
            settings,
            testTurn("Grüße für Jörg").copy(mood = "fröhlich"),
            ToolRegistry.allowed(settings.tools),
        )
        val allText = buildList {
            addAll(bundle.sections.map(PromptSection::text))
            addAll(ToolRegistry.all.map { it.definition.description })
            addAll(
                ToolRegistry.all.flatMap { tool ->
                    tool.definition.parameters.map(ToolParameter::description)
                },
            )
        }

        assertTrue(allText.none(EncodingGuard::containsLikelyMojibake))
        val broken = "\u00C3\u00BC"
        assertTrue(EncodingGuard.containsLikelyMojibake(broken))

        val projectRoot = locateProjectRoot()
        val roots = listOf(
            projectRoot.resolve("app/src/main/java/de/totec/doppel/ai"),
            projectRoot.resolve("app/src/test/java/de/totec/doppel/ai"),
        )
        val sources = roots.flatMap { root ->
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .map { Files.readString(it, StandardCharsets.UTF_8) }
                    .toList()
            }
        }
        assertTrue(sources.isNotEmpty())
        assertTrue(sources.none(EncodingGuard::containsLikelyMojibake))
    }

    private fun locateProjectRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            if (Files.isDirectory(current.resolve("app/src/main"))) return current
            current = current.parent
                ?: throw AssertionError("Could not locate Android project root")
        }
        throw AssertionError("Could not locate Android project root")
    }
}
