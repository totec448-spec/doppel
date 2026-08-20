package de.totec.doppel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandRegistryAndParserTest {
    private val expectedCommands = linkedMapOf(
        CommandId.HELP to ("help" to listOf("tutorial", "start", "anleitung", "h", "commands")),
        CommandId.STATUS to ("status" to listOf("stat", "state", "info")),
        CommandId.MOOD to ("mood" to listOf("stimmung")),
        CommandId.META to ("meta" to listOf("metabot", "behavior", "verhalten")),
        CommandId.ALLOWLIST to ("allowlist" to listOf("allowed")),
        CommandId.GROUP_ALLOWLIST to (
            "groupallowlist" to listOf("groupallowedlist", "groupsallowed", "grouplist")
        ),
        CommandId.API_KEY to ("apikey" to listOf("key", "orkey")),
        CommandId.RESET to ("reset" to listOf("clear", "neu")),
        CommandId.CLEAR_MEMORY to ("clearmemory" to listOf("clear_memory", "forgetme", "forget")),
        CommandId.CLEAR_ALL to ("clearall" to listOf("wipe", "clearallmemory", "wipeall")),
        CommandId.SETTINGS to ("settings" to listOf("config", "cfg")),
        CommandId.SET to ("set" to emptyList()),
        CommandId.GET to ("get" to emptyList()),
        CommandId.MODEL to ("model" to emptyList()),
        CommandId.MEDIA_MODEL to ("media_model" to listOf("mediamodel", "visionmodel", "audiomodel")),
        CommandId.HISTORY to ("history" to listOf("hist", "history_limit")),
        CommandId.SYSTEM to ("system" to listOf("sys", "prompt")),
        CommandId.PERSONALITY to ("personality" to listOf("personalities", "vibe", "char")),
        CommandId.OFF to ("off" to listOf("aus", "pause", "stop", "mute")),
        CommandId.ON to ("on" to listOf("an", "resume", "unmute")),
        CommandId.CROSS_CHAT to (
            "crosschat" to listOf("crosschatsearch", "chatsuche", "datenschutz", "privacy")
        ),
        CommandId.VOICE to ("voice" to listOf("tts", "voicenote", "sprachnachricht")),
        CommandId.TRAITS to ("traits" to listOf("stats", "trait", "charakter")),
        CommandId.PERSONA to ("persona" to listOf("personas", "personae")),
        CommandId.BLOCK to ("block" to listOf("blockieren")),
        CommandId.UNBLOCK to ("unblock" to listOf("entblocken", "deblock")),
        CommandId.AUTOBLOCK to ("autoblock" to listOf("flood", "antispam")),
        CommandId.BLOCK_TOOL to ("blocktool" to listOf("blockmodel", "modelblock")),
        CommandId.PROACTIVE to ("proactive" to listOf("proaktiv", "proactivity")),
        CommandId.REACHOUT to ("reachout" to listOf("timelock", "reachoutlock", "463")),
        CommandId.SAFETY to ("safety" to listOf("safe", "locks")),
    )

    @Test
    fun registryContainsExactlyAll31CanonicalCommandsAnd71Aliases() {
        val registry = CommandRegistry()

        assertEquals(31, registry.canonicalCount)
        assertEquals(71, registry.aliasCount)
        assertEquals(102, registry.tokenCount)
        assertEquals(CommandId.entries.toSet(), expectedCommands.keys)
        assertEquals(expectedCommands.keys, registry.specs.map(CommandSpec::id).toSet())

        expectedCommands.forEach { (id, expected) ->
            val spec = registry.specs.single { it.id == id }
            assertEquals(expected.first, spec.name)
            assertEquals(expected.second, spec.aliases)
            spec.tokens.forEach { token ->
                assertSame(spec, registry.resolve(token))
                assertSame(spec, registry.resolve(token.uppercase()))
            }
        }
    }

    @Test
    fun generatedHelpCannotDriftFromRegistryAndUsesConfiguredPrefix() {
        val registry = CommandRegistry()
        val help = registry.renderHelp("!")

        registry.specs.forEach { spec ->
            assertTrue("canonical missing: ${spec.name}", help.contains("*!${spec.name}"))
            spec.aliases.forEach { alias ->
                assertTrue("alias missing: $alias", help.contains("!$alias"))
            }
        }
        assertFalse(help.contains("*/help"))
        assertFalse(help.contains(" /"))
    }

    @Test
    fun generatedGermanHelpAndRepliesHaveNoMojibake() {
        val registry = CommandRegistry()
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake, prefixProvider = { "!" }, registry = registry)
        val help = registry.renderHelp("!")
        val offReply = executor.executeBlocking(adminRequest("!off")).replyText()
        val combined = "$help\n$offReply"

        assertTrue(help.contains("WhatsApp-Bot \u2013 Admin-Befehle"))
        assertTrue(help.contains("erkl\u00E4ren"))
        assertTrue(help.contains("zur\u00FCcksetzen"))
        assertTrue(help.contains("Best\u00E4tigung"))
        assertTrue(help.contains("Pers\u00F6nlichkeit"))
        listOf('\u00C3', '\u00C2', '\u00E2', '\u00F0', '\uFFFD').forEach { brokenMarker ->
            assertFalse("mojibake marker U+${brokenMarker.code.toString(16)}", combined.contains(brokenMarker))
        }
    }

    @Test
    fun parserSupportsCustomPrefixCaseInsensitiveNamesAndLosslessRawArguments() {
        val registry = CommandRegistry()
        val parser = CommandParser(registry) { "!" }

        assertSame(CommandParseResult.NotACommand, parser.parse("/set model ignored"))
        val parsed = parser.parse(" \t!SeT  model   provider/model  ")
        assertTrue(parsed is CommandParseResult.Parsed)
        val invocation = (parsed as CommandParseResult.Parsed).invocation
        assertEquals(CommandId.SET, invocation.spec.id)
        assertEquals("set", invocation.invokedName)
        assertEquals(listOf("model", "provider/model"), invocation.arguments)
        assertEquals("model   provider/model", invocation.rawArguments)

        val alias = parser.parse("!An")
        assertEquals(CommandId.ON, (alias as CommandParseResult.Parsed).invocation.spec.id)
    }

    @Test
    fun parserSeparatesUnknownAndOrdinaryTextAndRejectsEmptyPrefix() {
        val parser = CommandParser(CommandRegistry()) { "/" }

        assertSame(CommandParseResult.NotACommand, parser.parse("normaler Text"))
        val unknown = parser.parse("/existiertnicht ein Argument")
        assertTrue(unknown is CommandParseResult.UnknownCommand)
        assertEquals("existiertnicht", (unknown as CommandParseResult.UnknownCommand).invokedName)
        assertEquals("ein Argument", unknown.rawArguments)
        assertNotNull(parser.parse("/"))
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("/help", "")
        }
    }

    private fun adminRequest(text: String): CommandRequest = CommandRequest(
        text = text,
        senderId = "admin",
        chatId = "admin@s.whatsapp.net",
        isGroup = false,
        isAdmin = true,
    )

    private fun CommandExecutionResult.replyText(): String {
        return (this as CommandExecutionResult.Replied).messages.joinToString("\n")
    }
}
