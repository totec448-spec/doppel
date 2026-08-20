package de.totec.doppel.commands

import java.util.Locale

/**
 * Single source of truth for parser registration and help rendering.
 *
 * There is deliberately no hand-written second command list. Adding or removing
 * a command here changes parser resolution and generated help together.
 */
class CommandRegistry(
    specs: List<CommandSpec> = defaultSpecs(),
) {
    val specs: List<CommandSpec> = specs.map { spec ->
        spec.copy(aliases = spec.aliases.toList())
    }

    private val byToken: Map<String, CommandSpec>

    init {
        require(this.specs.map(CommandSpec::id).toSet().size == this.specs.size) {
            "Command ids must be unique"
        }
        require(this.specs.map { normalize(it.name) }.toSet().size == this.specs.size) {
            "Canonical command names must be unique"
        }

        val entries = buildList {
            this@CommandRegistry.specs.forEach { spec ->
                require(spec.name.isNotBlank()) { "Command name must not be blank" }
                spec.tokens.forEach { token ->
                    require(token.isNotBlank()) { "Command token must not be blank" }
                    require(token.none(Char::isWhitespace)) {
                        "Command token must not contain whitespace: $token"
                    }
                    add(normalize(token) to spec)
                }
            }
        }
        val collisions = entries
            .groupBy({ it.first }, { it.second.id })
            .filterValues { it.size > 1 }
        require(collisions.isEmpty()) {
            "Command token collisions: ${collisions.keys.sorted().joinToString()}"
        }
        byToken = entries.associate { it.first to it.second }
    }

    val canonicalCount: Int
        get() = specs.size

    val aliasCount: Int
        get() = specs.sumOf { it.aliases.size }

    val tokenCount: Int
        get() = byToken.size

    fun resolve(token: String): CommandSpec? = byToken[normalize(token)]

    fun renderHelp(prefix: String, title: String = "WhatsApp-Bot – Admin-Befehle"): String {
        require(prefix.isNotEmpty()) { "Command prefix must not be empty" }
        val lines = mutableListOf("*$title*", "")
        specs.forEach { spec ->
            val syntax = buildString {
                append(prefix)
                append(spec.name)
                spec.usage?.takeIf(String::isNotBlank)?.let {
                    append(' ')
                    append(it)
                }
            }
            lines += "• *$syntax* — ${spec.description}"
            if (spec.aliases.isNotEmpty()) {
                lines += "  Aliase: ${spec.aliases.joinToString { "$prefix$it" }}"
            }
        }
        return lines.joinToString("\n")
    }

    companion object {
        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)

        fun defaultSpecs(): List<CommandSpec> = listOf(
            CommandSpec(
                CommandId.HELP,
                "help",
                listOf("tutorial", "start", "anleitung", "h", "commands"),
                "Diese aus der Registry erzeugte Referenz anzeigen.",
            ),
            CommandSpec(
                CommandId.STATUS,
                "status",
                listOf("stat", "state", "info"),
                "Laufzeit-, Verbindungs-, Safety- und Feature-Status anzeigen.",
            ),
            CommandSpec(
                CommandId.MOOD,
                "mood",
                listOf("stimmung"),
                "Geteilte Grundstimmung anzeigen oder schalten.",
                "[on|off]",
            ),
            CommandSpec(
                CommandId.META,
                "meta",
                listOf("metabot", "behavior", "verhalten"),
                "Persona-, Memory-, Medien-, Tool- und Realismus-Logik erklären.",
            ),
            CommandSpec(
                CommandId.ALLOWLIST,
                "allowlist",
                listOf("allowed"),
                "Erlaubte Telefonnummern anzeigen oder ändern.",
                "[list|add|remove|set] [Nummern]",
            ),
            CommandSpec(
                CommandId.GROUP_ALLOWLIST,
                "groupallowlist",
                listOf("groupallowedlist", "groupsallowed", "grouplist"),
                "Erlaubte Gruppen explizit anzeigen oder ändern.",
                "[list|add|remove|set] [Gruppen]",
            ),
            CommandSpec(
                CommandId.API_KEY,
                "apikey",
                listOf("key", "orkey"),
                "OpenRouter-Key im Admin-Direktchat maskiert anzeigen, ersetzen oder entfernen.",
                "[status|set <Key>|clear]",
            ),
            CommandSpec(
                CommandId.RESET,
                "reset",
                listOf("clear", "neu"),
                "Aktuellen Persona-Thread zurücksetzen; 'all' setzt auch globale Settings zurück.",
                "[all]",
            ),
            CommandSpec(
                CommandId.CLEAR_MEMORY,
                "clearmemory",
                listOf("clear_memory", "forgetme", "forget"),
                "Verlauf, Memory und proaktiven Zustand dieses Persona-Threads löschen.",
            ),
            CommandSpec(
                CommandId.CLEAR_ALL,
                "clearall",
                listOf("wipe", "clearallmemory", "wipeall"),
                "Persona- oder Komplett-Wipe mit zielgebundener Bestätigung.",
                "[<Persona>|all|<Code>]",
            ),
            CommandSpec(
                CommandId.SETTINGS,
                "settings",
                listOf("config", "cfg"),
                "Alle globalen Settings mit Wert, Default und Beschreibung anzeigen.",
            ),
            CommandSpec(
                CommandId.SET,
                "set",
                description = "Ein globales Setting typisiert setzen.",
                usage = "<Key> <Wert>",
            ),
            CommandSpec(
                CommandId.GET,
                "get",
                description = "Ein globales Setting anzeigen.",
                usage = "<Key>",
            ),
            CommandSpec(
                CommandId.MODEL,
                "model",
                description = "Antwortmodell anzeigen oder setzen.",
                usage = "[Modell-Slug]",
            ),
            CommandSpec(
                CommandId.MEDIA_MODEL,
                "media_model",
                listOf("mediamodel", "visionmodel", "audiomodel"),
                "Medienmodell anzeigen oder setzen.",
                "[Modell-Slug]",
            ),
            CommandSpec(
                CommandId.HISTORY,
                "history",
                listOf("hist", "history_limit"),
                "Direktes Verlaufsfenster anzeigen oder setzen.",
                "[Anzahl|all]",
            ),
            CommandSpec(
                CommandId.SYSTEM,
                "system",
                listOf("sys", "prompt"),
                "Custom-Systemtext anzeigen oder atomar aktivieren.",
                "[Text]",
            ),
            CommandSpec(
                CommandId.PERSONALITY,
                "personality",
                listOf("personalities", "vibe", "char"),
                "Globale Persönlichkeit anzeigen oder wechseln.",
                "[Key|custom <Text>]",
            ),
            CommandSpec(
                CommandId.OFF,
                "off",
                listOf("aus", "pause", "stop", "mute"),
                "Bot funktional pausieren; Verbindung und Admin-Steuerung bleiben aktiv.",
            ),
            CommandSpec(
                CommandId.ON,
                "on",
                listOf("an", "resume", "unmute"),
                "Funktional pausierten Bot fortsetzen.",
            ),
            CommandSpec(
                CommandId.CROSS_CHAT,
                "crosschat",
                listOf("crosschatsearch", "chatsuche", "datenschutz", "privacy"),
                "Persona-begrenzte Suche in anderen Chats anzeigen oder schalten.",
                "[on|off]",
            ),
            CommandSpec(
                CommandId.VOICE,
                "voice",
                listOf("tts", "voicenote", "sprachnachricht"),
                "Voice-Notes, Qualität und die globale Stimme verwalten.",
                "[on|off|quality <1-10>|voices|voice <Name>|<Persona> <Name>]",
            ),
            CommandSpec(
                CommandId.TRAITS,
                "traits",
                listOf("stats", "trait", "charakter"),
                "Neun Charakterregler von -3 bis +3 anzeigen oder setzen.",
                "[reset|<Name> [Wert]]",
            ),
            CommandSpec(
                CommandId.PERSONA,
                "persona",
                listOf("personas", "personae"),
                "Personas, Zuweisungen und Bilder verwalten.",
                "[list|create|assign|unassign|voice|images|delete] …",
            ),
            CommandSpec(
                CommandId.BLOCK,
                "block",
                listOf("blockieren"),
                "Blockliste anzeigen oder eine Nummer lokal und bei WhatsApp blockieren.",
                "[Nummer] [Grund]",
            ),
            CommandSpec(
                CommandId.UNBLOCK,
                "unblock",
                listOf("entblocken", "deblock"),
                "Eine blockierte Nummer wieder freigeben.",
                "<Nummer>",
            ),
            CommandSpec(
                CommandId.AUTOBLOCK,
                "autoblock",
                listOf("flood", "antispam"),
                "Flood-Guard, Modell-Blocktool und Schwellen steuern.",
                "[on|off|tool on|off|limits <1m> <5m> <10m>]",
            ),
            CommandSpec(
                CommandId.BLOCK_TOOL,
                "blocktool",
                listOf("blockmodel", "modelblock"),
                "Das letzte-Mittel-Modell-Blocktool anzeigen oder schalten.",
                "[on|off]",
            ),
            CommandSpec(
                CommandId.PROACTIVE,
                "proactive",
                listOf("proaktiv", "proactivity"),
                "Globale oder kontaktbezogene Proaktivität steuern.",
                "[global <0-10>|<Nummer> <0-10|off|clear>]",
            ),
            CommandSpec(
                CommandId.REACHOUT,
                "reachout",
                listOf("timelock", "reachoutlock", "463"),
                "WhatsApp-Reachout-Timelock und New-Chat-Cap read-only prüfen.",
            ),
            CommandSpec(
                CommandId.SAFETY,
                "safety",
                listOf("safe", "locks"),
                "Safety-Status, Events, Refresh, Locks und manuellen Hold steuern.",
                "[status|events|refresh|ack <Id>|clear <Id>|hold <Dauer> <Grund>]",
            ),
        )
    }
}
